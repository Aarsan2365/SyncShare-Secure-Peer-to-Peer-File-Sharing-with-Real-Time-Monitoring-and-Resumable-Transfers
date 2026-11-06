
package com.syncshare.transfer;

import com.syncshare.approval.ApprovalHandler;
import com.syncshare.events.ConsoleTransferListener;
import com.syncshare.events.TransferListener;
import com.syncshare.network.Protocol;
import com.syncshare.network.ProtocolIO;

import java.io.*;
import java.net.Socket;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Scanner;
import java.util.UUID;

public class FileReceiver {

    private static final int CHUNK_SIZE = Protocol.BUFFER_SIZE;

    // Used only for the old interactive console mode.
    private static final Object APPROVAL_LOCK = new Object();
    private static final Scanner CONSOLE = new Scanner(System.in);

    // Method 1: Existing TcpServer compatibility
    public static void receive(Socket socket) throws IOException {
        receive(socket, new ConsoleTransferListener(), null);
    }

    // Method 2: Custom listener compatibility
    public static void receive(
            Socket socket,
            TransferListener listener
    ) throws IOException {
        receive(socket, listener, null);
    }

    // Method 3: Background peer mode with ApprovalManager
    public static void receive(
            Socket socket,
            TransferListener listener,
            ApprovalHandler approvalHandler
    ) throws IOException {

        Objects.requireNonNull(socket, "socket");
        Objects.requireNonNull(listener, "listener");

        String fileName = "unknown";
        boolean completed = false;

        try {
            DataInputStream input = new DataInputStream(
                socket.getInputStream()
            );

            DataOutputStream output = new DataOutputStream(
                socket.getOutputStream()
            );

            // STEP 1: Validate protocol header
            ProtocolIO.readHeader(input);

            // STEP 2: Expect FILE_OFFER
            ProtocolIO.expectMessageType(
                input,
                Protocol.FILE_OFFER
            );

            // STEP 3: Read file metadata
            String rawName = input.readUTF();
            long fileSize = input.readLong();
            String expectedHash = input.readUTF();

            String safeName = rawName.replace('\\', '/');
            safeName = safeName.substring(
                safeName.lastIndexOf('/') + 1
            );

            fileName = safeName;

            // STEP 4: Validate file metadata
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

            // STEP 5: Request user approval
            boolean accepted;

            if (approvalHandler != null) {

                // Background mode: approval via ApprovalManager.
                // No worker reads keyboard input.
                accepted = approvalHandler.approve(
                    safeName,
                    fileSize,
                    socket.getInetAddress()
                );

            } else {

                // Legacy console mode: one approval prompt at a time.
                synchronized (APPROVAL_LOCK) {

                    System.out.println(
                        "\n===== Incoming File Request ====="
                    );

                    System.out.println(
                        "Sender IP : " + socket.getInetAddress()
                    );

                    System.out.println("File Name : " + safeName);
                    System.out.println(
                        "File Size : " + fileSize + " bytes"
                    );

                    System.out.println("\n1. Accept");
                    System.out.println("2. Reject");
                    System.out.print("Choose option: ");

                    accepted = CONSOLE.nextLine()
                        .trim()
                        .equals("1");
                }
            }

            // STEP 6: Send ACCEPT or REJECT
            output.writeByte(
                accepted ? Protocol.ACCEPT : Protocol.REJECT
            );
            output.flush();

            if (!accepted) {
                System.out.println(
                    "Transfer rejected by receiver."
                );
                return;
            }

            System.out.println(
                "Transfer accepted: " + safeName
            );

            // STEP 7: Resume negotiation foundation
            // Member 2 will provide verified checkpoint offsets.
            long resumeOffset = 0L;

            output.writeByte(Protocol.RESUME_REQUEST);
            output.writeLong(resumeOffset);
            output.flush();

            System.out.println(
                "Resume request sent. Offset: " + resumeOffset
            );

            // STEP 8: Prepare file storage
            Path folder = Paths.get("received_files");
            Files.createDirectories(folder);

            Path tempFile = Files.createTempFile(
                folder,
                "syncshare-",
                ".part"
            );

            boolean saved = false;

            try {
                MessageDigest digest = HashUtils.newSha256();

                long expectedOffset = resumeOffset;
                int chunksReceived = 0;

                // STEP 9: Notify listeners
                listener.onStarted(safeName, fileSize);

                // STEP 10: Receive chunks
                try (OutputStream fileOutput =
                        Files.newOutputStream(tempFile)) {

                    byte[] buffer = new byte[CHUNK_SIZE];

                    while (expectedOffset < fileSize) {

                        byte messageType = input.readByte();

                        if (messageType != Protocol.FILE_CHUNK) {
                            throw new IOException(
                                "Expected FILE_CHUNK, received: "
                                + messageType
                            );
                        }

                        long offset = input.readLong();
                        int length = input.readInt();

                        if (offset != expectedOffset
                                || length <= 0
                                || length > CHUNK_SIZE
                                || length > fileSize - expectedOffset) {

                            throw new IOException(
                                "Invalid chunk offset or length"
                            );
                        }

                        // Read exactly the announced chunk bytes
                        input.readFully(buffer, 0, length);

                        // Write the chunk
                        fileOutput.write(buffer, 0, length);

                        // Incrementally calculate SHA-256
                        digest.update(buffer, 0, length);

                        expectedOffset += length;
                        chunksReceived++;

                        // STEP 11: Send chunk acknowledgement
                        output.writeByte(Protocol.CHUNK_ACK);
                        output.writeLong(expectedOffset);
                        output.flush();

                        // STEP 12: Progress event
                        listener.onProgress(
                            safeName,
                            expectedOffset,
                            fileSize
                        );

                        System.out.printf(
                            "\rReceiving [%s] Chunk %d: %.2f%%",
                            safeName,
                            chunksReceived,
                            expectedOffset * 100.0
                                / Math.max(fileSize, 1)
                        );
                    }

                    fileOutput.flush();
                }

                // STEP 13: Expect COMPLETE
                byte completeType = input.readByte();

                if (completeType != Protocol.COMPLETE) {
                    throw new IOException(
                        "Expected COMPLETE, received: "
                        + completeType
                    );
                }

                // STEP 14: Verify SHA-256
                String actualHash = HexFormat.of()
                    .formatHex(digest.digest());

                if (!actualHash.equals(expectedHash)) {
                    throw new IOException(
                        "SHA-256 MISMATCH: File verification failed"
                    );
                }

                // STEP 15: Save verified file
                Path destination = folder.resolve(
                    UUID.randomUUID() + "-" + safeName
                );

                Files.move(tempFile, destination);
                saved = true;

                System.out.println(
                    "\nSHA-256 MATCH: File verified successfully!"
                );

                System.out.println(
                    "File saved: "
                    + destination.toAbsolutePath()
                );

                // STEP 16: Inform sender
                output.writeByte(Protocol.VERIFIED);
                output.flush();

                // STEP 17: Notify completion
                completed = true;
                notifyCompleted(listener, safeName);

            } catch (IOException e) {

                // Best-effort ERROR response
                try {
                    output.writeByte(Protocol.ERROR);
                    output.flush();
                } catch (IOException ignored) {
                    // Connection might be closed
                }

                throw e;

            } finally {

                if (!saved) {
                    Files.deleteIfExists(tempFile);
                }
            }

        } catch (IOException e) {

            if (!completed) {
                notifyError(listener, fileName, e);
            }

            throw e;
        }
    }

    // Protect network workers from callback exceptions.
    private static void notifyCompleted(
            TransferListener listener,
            String fileName
    ) {
        try {
            listener.onCompleted(fileName);
        } catch (RuntimeException e) {
            System.err.println(
                "Completion listener error: " + e.getMessage()
            );
        }
    }

    private static void notifyError(
            TransferListener listener,
            String fileName,
            Exception error
    ) {
        try {
            listener.onError(fileName, error);
        } catch (RuntimeException e) {
            System.err.println(
                "Error listener failed: " + e.getMessage()
            );
        }
    }
}
