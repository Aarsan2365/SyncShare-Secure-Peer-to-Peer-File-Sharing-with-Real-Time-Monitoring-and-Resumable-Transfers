// src/main/java/com/syncshare/transfer/FileSender.java
package com.syncshare.transfer;

import com.syncshare.events.ConsoleTransferListener;
import com.syncshare.events.TransferListener;
import com.syncshare.events.TransferSessionListener;
import com.syncshare.model.TransferDirection;
import com.syncshare.network.Protocol;
import com.syncshare.network.ProtocolIO;
import com.syncshare.security.TlsSupport;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.UUID;

public class FileSender {

    public static void send(
            Socket socket,
            Path file
    ) throws IOException {
        send(socket, file, new ConsoleTransferListener());
    }

    public static void send(
            Socket socket,
            Path file,
            TransferListener listener
    ) throws IOException {
        send(socket, file, listener, UUID.randomUUID().toString(), TransferSessionListener.NO_OP, false);
    }

    public static void send(
            Socket socket,
            Path file,
            String transferId,
            TransferSessionListener sessionListener
    ) throws IOException {
        send(socket, file, new ConsoleTransferListener(), transferId, sessionListener, true);
    }

    public static void send(
            Socket socket,
            Path file,
            TransferListener listener,
            String transferId,
            TransferSessionListener sessionListener
    ) throws IOException {
        send(socket, file, listener, transferId, sessionListener, true);
    }

    private static void send(
            Socket socket,
            Path file,
            TransferListener listener,
            String transferId,
            TransferSessionListener sessionListener,
            boolean throwOnReject
    ) throws IOException {

        Objects.requireNonNull(socket, "socket");
        Objects.requireNonNull(file, "file");
        Objects.requireNonNull(listener, "listener");
        String effectiveId = (transferId != null && !transferId.isBlank())
            ? transferId
            : UUID.randomUUID().toString();
        TransferSessionListener effectiveSession = sessionListener != null
            ? sessionListener
            : TransferSessionListener.NO_OP;

        String fileName = file.getFileName() != null ? file.getFileName().toString() : file.toString();
        String peerEndpoint = formatPeerEndpoint(socket);
        long initialKnownSize = resolveFileSizeQuietly(file);

        safeSessionCall(() -> effectiveSession.onConnecting(
            effectiveId,
            TransferDirection.OUTGOING,
            fileName,
            initialKnownSize,
            peerEndpoint,
            "Authenticating peer (mTLS 1.3)"
        ));

        try {
            if (Thread.currentThread().isInterrupted()) {
                throw new InterruptedIOException("Transfer cancelled before authentication");
            }

            String peerId = TlsSupport.authenticate(socket);
            System.out.println("Authenticated receiver: " + peerId);
            if (!Files.isRegularFile(file)) {
                throw new IOException("File not found: " + file);
            }

            long fileSize = Files.size(file);

            if (fileSize > Protocol.MAX_FILE_SIZE) {
                throw new IOException("File exceeds size limit");
            }

            String senderHash = HashUtils.calculateSHA256(file);

            DataOutputStream output = new DataOutputStream(
                socket.getOutputStream()
            );

            DataInputStream input = new DataInputStream(
                socket.getInputStream()
            );

            // Existing Protocol V2 fields remain unchanged.
            ProtocolIO.writeHeader(output);

            output.writeByte(Protocol.FILE_OFFER);
            output.writeUTF(fileName);
            output.writeLong(fileSize);
            output.writeUTF(senderHash);
            output.flush();

            System.out.println("Waiting for receiver approval...");
            safeSessionCall(() -> effectiveSession.onConnecting(
                effectiveId,
                TransferDirection.OUTGOING,
                fileName,
                fileSize,
                peerEndpoint,
                "Waiting for receiver approval"
            ));

            byte response = input.readByte();

            if (response == Protocol.REJECT) {
                System.out.println("Receiver rejected the file.");
                if (throwOnReject) {
                    throw new IOException("Transfer rejected by receiver");
                }
                safeSessionCall(() -> effectiveSession.onFailed(
                    effectiveId,
                    TransferDirection.OUTGOING,
                    fileName,
                    "Transfer rejected by receiver",
                    null
                ));
                return;
            }

            if (response != Protocol.ACCEPT) {
                throw new IOException(
                    "Unexpected approval response: " + response
                );
            }

            ProtocolIO.expectMessageType(
                input,
                Protocol.RESUME_REQUEST
            );

            long resumeOffset = input.readLong();

            if (resumeOffset < 0 || resumeOffset > fileSize) {
                throw new IOException(
                    "Invalid resume offset: " + resumeOffset
                );
            }

            if (Files.size(file) != fileSize) {
                throw new IOException("Source file size changed");
            }

            System.out.println(
                "Resume requested from byte: " + resumeOffset
            );

            listener.onStarted(fileName, fileSize);
            listener.onProgress(fileName, resumeOffset, fileSize);
            final long startOffset = resumeOffset;
            safeSessionCall(() -> effectiveSession.onStarted(
                effectiveId,
                TransferDirection.OUTGOING,
                fileName,
                fileSize,
                startOffset,
                peerEndpoint
            ));
            safeSessionCall(() -> effectiveSession.onProgress(
                effectiveId,
                TransferDirection.OUTGOING,
                fileName,
                startOffset,
                fileSize
            ));

            long offset = resumeOffset;

            try (InputStream fileInput = Files.newInputStream(file)) {
                // Start from the receiver's validated checkpoint.
                fileInput.skipNBytes(resumeOffset);

                byte[] buffer = new byte[Protocol.BUFFER_SIZE];

                while (offset < fileSize) {
                    if (Thread.currentThread().isInterrupted()) {
                        throw new InterruptedIOException("Transfer cancelled");
                    }

                    int requested = (int) Math.min(
                        buffer.length,
                        fileSize - offset
                    );

                    int count = fileInput.read(buffer, 0, requested);

                    if (count < 0) {
                        throw new EOFException(
                            "Source file ended unexpectedly"
                        );
                    }

                    if (count == 0) {
                        continue;
                    }

                    output.writeByte(Protocol.FILE_CHUNK);
                    output.writeLong(offset);
                    output.writeInt(count);
                    output.write(buffer, 0, count);
                    output.flush();

                    byte ackType = input.readByte();

                    if (ackType == Protocol.ERROR) {
                        throw new IOException(
                            "Receiver reported a transfer error"
                        );
                    }

                    if (ackType != Protocol.CHUNK_ACK) {
                        throw new IOException(
                            "Expected CHUNK_ACK, received: " + ackType
                        );
                    }

                    long acknowledgedOffset = input.readLong();

                    if (acknowledgedOffset != offset + count) {
                        throw new IOException(
                            "Invalid ACK offset: " + acknowledgedOffset
                        );
                    }

                    offset = acknowledgedOffset;

                    listener.onProgress(
                        fileName,
                        offset,
                        fileSize
                    );
                    final long acked = offset;
                    safeSessionCall(() -> effectiveSession.onProgress(
                        effectiveId,
                        TransferDirection.OUTGOING,
                        fileName,
                        acked,
                        fileSize
                    ));
                }
            }

            output.writeByte(Protocol.COMPLETE);
            output.flush();

            byte result = input.readByte();

            if (result != Protocol.VERIFIED) {
                throw new IOException(
                    "Transfer verification failed. Response: " + result
                );
            }

            System.out.println(
                "SUCCESS: File transferred and verified!"
            );

        } catch (IOException e) {
            try {
                listener.onError(fileName, e);
            } catch (RuntimeException ignored) {
            }

            if (Thread.currentThread().isInterrupted()) {
                safeSessionCall(() -> effectiveSession.onCancelled(
                    effectiveId,
                    TransferDirection.OUTGOING,
                    fileName,
                    e.getMessage() != null ? e.getMessage() : "Transfer cancelled"
                ));
            } else {
                String reason = e.getMessage() != null ? e.getMessage() : "Outgoing transfer failed";
                safeSessionCall(() -> effectiveSession.onFailed(
                    effectiveId,
                    TransferDirection.OUTGOING,
                    fileName,
                    reason,
                    e
                ));
            }

            throw e;
        }

        try {
            listener.onCompleted(fileName);
        } catch (RuntimeException e) {
            System.err.println(
                "Completion listener error: " + e.getMessage()
            );
        }

        safeSessionCall(() -> effectiveSession.onCompleted(
            effectiveId,
            TransferDirection.OUTGOING,
            fileName
        ));
    }

    private static long resolveFileSizeQuietly(Path file) {
        try {
            if (Files.isRegularFile(file)) {
                return Files.size(file);
            }
        } catch (IOException ignored) {
        }
        return -1L;
    }

    private static String formatPeerEndpoint(Socket socket) {
        SocketAddress remote = socket.getRemoteSocketAddress();
        if (remote instanceof InetSocketAddress inet) {
            String host = inet.getAddress() != null
                ? inet.getAddress().getHostAddress()
                : inet.getHostString();
            return inet.getPort() > 0 ? host + ":" + inet.getPort() : host;
        }
        if (socket.getInetAddress() != null) {
            int port = socket.getPort();
            String host = socket.getInetAddress().getHostAddress();
            return port > 0 ? host + ":" + port : host;
        }
        return "unknown";
    }

    private static void safeSessionCall(Runnable action) {
        try {
            action.run();
        } catch (RuntimeException ignored) {
        }
    }
}
