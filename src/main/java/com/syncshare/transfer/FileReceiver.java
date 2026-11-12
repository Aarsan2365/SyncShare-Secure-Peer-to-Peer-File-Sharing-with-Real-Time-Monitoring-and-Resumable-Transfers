// src/main/java/com/syncshare/transfer/FileReceiver.java
package com.syncshare.transfer;

import com.syncshare.approval.ApprovalHandler;
import com.syncshare.events.ConsoleTransferListener;
import com.syncshare.events.IncomingTransferObserver;
import com.syncshare.events.TransferListener;
import com.syncshare.events.TransferSessionListener;
import com.syncshare.model.TransferDirection;
import com.syncshare.network.Protocol;
import com.syncshare.network.ProtocolIO;
import com.syncshare.resume.ResumeStore;
import com.syncshare.security.TlsSupport;
import com.syncshare.service.ReceiveDestinationValidator;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.InetAddress;
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
        receive(socket, listener, approvalHandler, null, null, null, null);
    }

    public static void receive(
            Socket socket,
            TransferListener listener,
            ApprovalHandler approvalHandler,
            String transferId,
            TransferSessionListener sessionListener
    ) throws IOException {
        receive(socket, listener, approvalHandler, null, null, transferId, sessionListener);
    }

    public static void receive(
            Socket socket,
            TransferListener listener,
            ApprovalHandler approvalHandler,
            Path receiveRootOverride,
            IncomingTransferObserver observer
    ) throws IOException {
        receive(socket, listener, approvalHandler, receiveRootOverride, observer, null, null);
    }

    public static void receive(
            Socket socket,
            TransferListener listener,
            ApprovalHandler approvalHandler,
            Path receiveRootOverride,
            IncomingTransferObserver observer,
            String transferId
    ) throws IOException {
        receive(socket, listener, approvalHandler, receiveRootOverride, observer, transferId, null);
    }

    public static void receive(
            Socket socket,
            TransferListener listener,
            ApprovalHandler approvalHandler,
            Path receiveRootOverride,
            IncomingTransferObserver observer,
            String transferId,
            TransferSessionListener sessionListener
    ) throws IOException {

        Objects.requireNonNull(socket, "socket");
        Objects.requireNonNull(listener, "listener");

        String activeTransferId = (transferId != null && !transferId.isBlank())
            ? transferId
            : UUID.randomUUID().toString();
        IncomingTransferObserver effectiveObserver =
            observer != null ? observer : IncomingTransferObserver.NO_OP;
        TransferSessionListener effectiveSession =
            sessionListener != null ? sessionListener : TransferSessionListener.NO_OP;

        String fileName = "unknown";
        String senderInfo = formatSenderAddress(socket);
        String peerEndpoint = formatPeerEndpoint(socket);
        Path savedDestination = null;

        DataInputStream input = new DataInputStream(
            socket.getInputStream()
        );

        DataOutputStream output = new DataOutputStream(
            socket.getOutputStream()
        );

        safeCall(() -> effectiveSession.onConnecting(
            activeTransferId,
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
            senderInfo = formatSenderIdentity(
                senderInfo,
                TlsSupport.extractPeerSubject(socket),
                peerId
            );
            System.out.println("Authenticated sender: " + peerId);
            final String openedSender = senderInfo;
            safeCall(() -> effectiveObserver.onConnectionOpened(
                activeTransferId,
                openedSender,
                peerId
            ));

            ProtocolIO.readHeader(input);
            ProtocolIO.expectMessageType(input, Protocol.FILE_OFFER);

            String rawName = input.readUTF();
            long fileSize = input.readLong();
            String expectedHash = input.readUTF();

            if (!ReceiveDestinationValidator.isSafeSingleFileName(rawName)) {
                output.writeByte(Protocol.REJECT);
                output.flush();
                throw new IOException("Invalid or unsafe file offer: " + rawName);
            }

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
            final String offeredSender = senderInfo;
            safeCall(() -> effectiveObserver.onFileOffered(
                activeTransferId,
                offeredName,
                fileSize,
                offeredSender,
                peerId
            ));
            safeCall(() -> effectiveSession.onConnecting(
                activeTransferId,
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

            final boolean decisionAccepted = accepted;
            safeCall(() -> effectiveObserver.onApprovalDecision(
                activeTransferId,
                offeredName,
                decisionAccepted
            ));

            output.writeByte(
                accepted ? Protocol.ACCEPT : Protocol.REJECT
            );
            output.flush();

            if (!accepted) {
                IOException rejectedError = new IOException("Incoming transfer rejected locally");
                safeCall(() -> effectiveSession.onFailed(
                    activeTransferId,
                    TransferDirection.INCOMING,
                    offeredName,
                    "Rejected locally",
                    rejectedError
                ));
                return;
            }

            Path receiveRoot = receiveRootOverride != null
                ? receiveRootOverride
                : Paths.get(
                    System.getProperty("syncshare.receiveDir", "received_files")
                );
            Path normalizedRoot = receiveRoot.toAbsolutePath().normalize();
            Path folder = normalizedRoot.resolve(peerId).normalize();
            if (!folder.startsWith(normalizedRoot)
                    || folder.getParent() == null
                    || !folder.getParent().equals(normalizedRoot)) {
                throw new IOException("Unsafe peer destination directory: " + folder);
            }

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
                safeCall(() -> effectiveObserver.onStarted(
                    activeTransferId,
                    offeredName,
                    fileSize,
                    offeredSender,
                    peerId
                ));

                final long startOffset = expectedOffset;
                safeCall(() -> effectiveSession.onStarted(
                    activeTransferId,
                    TransferDirection.INCOMING,
                    offeredName,
                    fileSize,
                    startOffset,
                    peerEndpoint
                ));

                listener.onProgress(safeName, expectedOffset, fileSize);
                safeCall(() -> effectiveObserver.onProgress(
                    activeTransferId,
                    offeredName,
                    startOffset,
                    fileSize
                ));
                safeCall(() -> effectiveSession.onProgress(
                    activeTransferId,
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
                    safeCall(() -> effectiveObserver.onProgress(
                        activeTransferId,
                        offeredName,
                        progressOffset,
                        fileSize
                    ));
                    safeCall(() -> effectiveSession.onProgress(
                        activeTransferId,
                        TransferDirection.INCOMING,
                        offeredName,
                        progressOffset,
                        fileSize
                    ));
                }

                ProtocolIO.expectMessageType(input, Protocol.COMPLETE);

                Path destination = store.finish();
                savedDestination = destination;

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
            final String failedSender = senderInfo;
            if (Thread.currentThread().isInterrupted()) {
                safeCall(() -> effectiveSession.onCancelled(
                    activeTransferId,
                    TransferDirection.INCOMING,
                    failedName,
                    e.getMessage() != null ? e.getMessage() : "Incoming transfer cancelled"
                ));
            } else {
                String reason = e.getMessage() != null ? e.getMessage() : "Incoming transfer failed";
                safeCall(() -> effectiveSession.onFailed(
                    activeTransferId,
                    TransferDirection.INCOMING,
                    failedName,
                    reason,
                    e
                ));
            }
            safeCall(() -> effectiveObserver.onError(
                activeTransferId,
                failedName,
                failedSender,
                e
            ));

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
        final Path finalSavedPath = savedDestination;
        safeCall(() -> effectiveSession.onCompleted(
            activeTransferId,
            TransferDirection.INCOMING,
            completedName
        ));
        safeCall(() -> effectiveObserver.onCompleted(
            activeTransferId,
            completedName,
            finalSavedPath
        ));
    }

    private static String formatSenderAddress(Socket socket) {
        InetAddress address = socket.getInetAddress();
        if (address == null) {
            return "unknown";
        }
        int port = socket.getPort();
        return port > 0 ? address.getHostAddress() + ":" + port : address.getHostAddress();
    }

    private static String formatSenderIdentity(String address, String subject, String peerId) {
        String shortId = peerId != null && peerId.length() > 12
            ? peerId.substring(0, 12)
            : (peerId != null ? peerId : "");
        if (subject != null && !subject.isBlank()) {
            return address + " (" + subject + ", " + shortId + ")";
        }
        if (!shortId.isBlank()) {
            return address + " (" + shortId + ")";
        }
        return address;
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

    private static void safeCall(Runnable action) {
        try {
            action.run();
        } catch (RuntimeException ignored) {
        }
    }
}
