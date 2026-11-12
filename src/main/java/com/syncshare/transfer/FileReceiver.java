// src/main/java/com/syncshare/transfer/FileReceiver.java
package com.syncshare.transfer;

import com.syncshare.approval.ApprovalHandler;
import com.syncshare.events.ConsoleTransferListener;
import com.syncshare.events.TransferListener;
import com.syncshare.events.TransferSessionListener;
import com.syncshare.model.TransferDirection;
import com.syncshare.network.Protocol;
import com.syncshare.network.ProtocolIO;
import com.syncshare.resume.ResumeStore;
import com.syncshare.security.TlsSupport;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Objects;
import java.util.Scanner;
import java.util.UUID;

public class FileReceiver {

    private static final Object APPROVAL_LOCK = new Object();
    private static final Scanner CONSOLE = new Scanner(System.in);

    public static void receive(Socket socket) throws IOException {
        receive(socket, new ConsoleTransferListener(), null);
    }

    public static void receive(
            Socket socket,
            TransferListener listener
    ) throws IOException {
        receive(socket, listener, null);
    }

    public static void receive(
            Socket socket,
            TransferListener listener,
            ApprovalHandler approvalHandler
    ) throws IOException {
        receive(
            socket,
            listener,
            approvalHandler,
            UUID.randomUUID().toString(),
            TransferSessionListener.NO_OP
        );
    }

    public static void receive(
            Socket socket,
            TransferListener listener,
            ApprovalHandler approvalHandler,
            String transferId,
            TransferSessionListener sessionListener
    ) throws IOException {

        Objects.requireNonNull(socket, "socket");
        Objects.requireNonNull(listener, "listener");
        String effectiveId = (transferId != null && !transferId.isBlank())
            ? transferId
            : UUID.randomUUID().toString();
        TransferSessionListener effectiveSession = sessionListener != null
            ? sessionListener
            : TransferSessionListener.NO_OP;

        String fileName = "unknown";
        String peerEndpoint = formatPeerEndpoint(socket);

        DataInputStream input = new DataInputStream(
            socket.getInputStream()
        );

        DataOutputStream output = new DataOutputStream(
            socket.getOutputStream()
        );

        safeSessionCall(() -> effectiveSession.onConnecting(
            effectiveId,
            TransferDirection.INCOMING,
            "Incoming file",
            -1L,
            peerEndpoint,
            "Authenticating sender (mTLS 1.3)"
        ));

        try {
            if (Thread.currentThread().isInterrupted()) {
                throw new InterruptedIOException("Incoming transfer cancelled before authentication");
            }

            String peerId = TlsSupport.authenticate(socket);
            System.out.println("Authenticated sender: " + peerId);

            ProtocolIO.readHeader(input);
            ProtocolIO.expectMessageType(input, Protocol.FILE_OFFER);

            String rawName = input.readUTF();
            long fileSize = input.readLong();
            String expectedHash = input.readUTF();

            String safeName = rawName.replace('\\', '/');
            safeName = safeName.substring(safeName.lastIndexOf('/') + 1);
            fileName = safeName;

            if (safeName.isBlank()
                    || safeName.equals(".")
                    || safeName.equals("..")
                    || safeName.indexOf('\0') >= 0
                    || fileSize < 0
                    || fileSize > Protocol.MAX_FILE_SIZE
                    || !expectedHash.matches("[0-9a-f]{64}")) {

                output.writeByte(Protocol.REJECT);
                output.flush();

                throw new IOException("Invalid file offer");
            }

            final String offeredName = safeName;
            safeSessionCall(() -> effectiveSession.onConnecting(
                effectiveId,
                TransferDirection.INCOMING,
                offeredName,
                fileSize,
                peerEndpoint,
                "Awaiting local approval"
            ));

            boolean accepted;

            if (approvalHandler != null) {
                accepted = approvalHandler.approve(
                    safeName,
                    fileSize,
                    socket.getInetAddress()
                );
            } else {
                synchronized (APPROVAL_LOCK) {
                    System.out.println("\nIncoming file: " + safeName);
                    System.out.println("Size: " + fileSize + " bytes");
                    System.out.println("Sender: " + socket.getInetAddress());
                    System.out.print("1. Accept / 2. Reject: ");

                    accepted = CONSOLE.hasNextLine()
                        && CONSOLE.nextLine().trim().equals("1");
                }
            }

            output.writeByte(
                accepted ? Protocol.ACCEPT : Protocol.REJECT
            );
            output.flush();

            if (!accepted) {
                IOException rejectedError = new IOException("Incoming transfer rejected locally");
                safeSessionCall(() -> effectiveSession.onFailed(
                    effectiveId,
                    TransferDirection.INCOMING,
                    offeredName,
                    "Rejected locally",
                    rejectedError
                ));
                return;
            }

            Path receiveRoot = Paths.get(
                System.getProperty("syncshare.receiveDir", "received_files")
            );
            Path folder = receiveRoot.resolve(peerId);
            try (ResumeStore store = new ResumeStore(
                    folder,
                    safeName,
                    fileSize,
                    expectedHash
            )) {
                long expectedOffset = store.offset();

                output.writeByte(Protocol.RESUME_REQUEST);
                output.writeLong(expectedOffset);
                output.flush();

                System.out.println(
                    "Resume request sent. Offset: " + expectedOffset
                );

                listener.onStarted(safeName, fileSize);
                listener.onProgress(safeName, expectedOffset, fileSize);

                final long startOffset = expectedOffset;
                safeSessionCall(() -> effectiveSession.onStarted(
                    effectiveId,
                    TransferDirection.INCOMING,
                    offeredName,
                    fileSize,
                    startOffset,
                    peerEndpoint
                ));
                safeSessionCall(() -> effectiveSession.onProgress(
                    effectiveId,
                    TransferDirection.INCOMING,
                    offeredName,
                    startOffset,
                    fileSize
                ));

                byte[] buffer = new byte[Protocol.BUFFER_SIZE];

                while (expectedOffset < fileSize) {
                    if (Thread.currentThread().isInterrupted()) {
                        throw new InterruptedIOException("Incoming transfer cancelled");
                    }

                    ProtocolIO.expectMessageType(
                        input,
                        Protocol.FILE_CHUNK
                    );

                    long offset = input.readLong();
                    int length = input.readInt();

                    if (offset != expectedOffset
                            || length <= 0
                            || length > buffer.length
                            || length > fileSize - expectedOffset) {

                        throw new IOException(
                            "Invalid chunk offset or length"
                        );
                    }

                    input.readFully(buffer, 0, length);

                    // Persist both data and checkpoint before acknowledging.
                    store.append(buffer, length);
                    expectedOffset = store.offset();

                    output.writeByte(Protocol.CHUNK_ACK);
                    output.writeLong(expectedOffset);
                    output.flush();

                    listener.onProgress(
                        safeName,
                        expectedOffset,
                        fileSize
                    );
                    final long progressOffset = expectedOffset;
                    safeSessionCall(() -> effectiveSession.onProgress(
                        effectiveId,
                        TransferDirection.INCOMING,
                        offeredName,
                        progressOffset,
                        fileSize
                    ));
                }

                ProtocolIO.expectMessageType(input, Protocol.COMPLETE);

                Path destination = store.finish();

                output.writeByte(Protocol.VERIFIED);
                output.flush();

                System.out.println(
                    "File verified and saved: "
                    + destination.toAbsolutePath()
                );
            }

        } catch (IOException e) {
            // Preserve the partial file and checkpoint for another attempt.
            try {
                output.writeByte(Protocol.ERROR);
                output.flush();
            } catch (IOException ignored) {
            }

            try {
                listener.onError(fileName, e);
            } catch (RuntimeException ignored) {
            }

            final String failedName = fileName;
            if (Thread.currentThread().isInterrupted()) {
                safeSessionCall(() -> effectiveSession.onCancelled(
                    effectiveId,
                    TransferDirection.INCOMING,
                    failedName,
                    e.getMessage() != null ? e.getMessage() : "Incoming transfer cancelled"
                ));
            } else {
                String reason = e.getMessage() != null ? e.getMessage() : "Incoming transfer failed";
                safeSessionCall(() -> effectiveSession.onFailed(
                    effectiveId,
                    TransferDirection.INCOMING,
                    failedName,
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

        final String completedName = fileName;
        safeSessionCall(() -> effectiveSession.onCompleted(
            effectiveId,
            TransferDirection.INCOMING,
            completedName
        ));
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
