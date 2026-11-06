
package com.syncshare.transfer;

import com.syncshare.network.Protocol;
import com.syncshare.network.ProtocolIO;
import com.syncshare.events.TransferListener;
import com.syncshare.events.ConsoleTransferListener;

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

    // Prevent overlapping receiver approval prompts
    private static final Object APPROVAL_LOCK = new Object();

    // Shared console input for all receiver worker threads
    private static final Scanner CONSOLE = new Scanner(System.in);

    // METHOD 1: Existing method used by TcpServer
    public static void receive(Socket socket) throws IOException {
        receive(socket, new ConsoleTransferListener());
    }

    // METHOD 2: New method for custom listeners (JavaFX)
    public static void receive(
            Socket socket,
            TransferListener listener
    ) throws IOException {

        Objects.requireNonNull(socket, "socket");
        Objects.requireNonNull(listener, "listener");

        String fileName = "unknown";
        boolean started = false;
        boolean completed = false;

        try {
            DataInputStream input = new DataInputStream(
                socket.getInputStream()
            );

            DataOutputStream output = new DataOutputStream(
                socket.getOutputStream()
            );

            // STEP 1: Validate Protocol V2
            ProtocolIO.readHeader(input);

            ProtocolIO.expectMessageType(
                input,
                Protocol.FILE_OFFER
            );

            // STEP 2: Read incoming metadata
            String rawName = input.readUTF();
            long fileSize = input.readLong();
            String expectedHash = input.readUTF();

            // STEP 3: Sanitize filename
            String safeName = rawName.replace('\\', '/');
            safeName = safeName.substring(
                safeName.lastIndexOf('/') + 1
            );

            fileName = safeName;

            // STEP 4: Validate metadata
            if (safeName.isBlank()
                    || safeName.equals(".")
                    || safeName.equals("..")
                    || fileSize < 0
                    || fileSize > Protocol.MAX_FILE_SIZE
                    || !expectedHash.matches("[0-9a-f]{64}")) {

                output.writeByte(Protocol.REJECT);
                output.flush();

                throw new IOException("Invalid file offer");
            }

            // STEP 5: Receiver Accept / Reject
            boolean accepted;

            synchronized (APPROVAL_LOCK) {

                System.out.println(
                    "\n===== Incoming File Request ====="
                );

                System.out.println(
                    "Sender IP : " + socket.getInetAddress()
                );

                System.out.println("File Name : " + safeName);
                System.out.println("File Size : " + fileSize + " bytes");

                System.out.println("\n1. Accept");
                System.out.println("2. Reject");
                System.out.print("Choose option: ");

                accepted = CONSOLE.nextLine()
                        .trim()
                        .equals("1");
            }

            // STEP 6: Send approval response
            output.writeByte(
                accepted ? Protocol.ACCEPT : Protocol.REJECT
            );
            output.flush();

            if (!accepted) {
                System.out.println("Transfer rejected by receiver.");
                return;
            }

            System.out.println("Transfer accepted!");

            // STEP 7: Resume negotiation foundation
            // Real resume needs Member 2's verified checkpoint storage.
            long resumeOffset = 0L;

            output.writeByte(Protocol.RESUME_REQUEST);
            output.writeLong(resumeOffset);
            output.flush();

            System.out.println(
                "Resume request sent. Offset: " + resumeOffset
            );

            // STEP 8: Prepare destination directory
            Path folder = Paths.get("received_files");
            Files.createDirectories(folder);

            Path tempFile = Files.createTempFile(
                folder, "syncshare-", ".part"
            );

            boolean saved = false;

            try {
                MessageDigest digest = HashUtils.newSha256();

                long expectedOffset = resumeOffset;
                int chunkNumber = 0;

                // STEP 9: Start transfer event
                started = true;
                listener.onStarted(safeName, fileSize);

                // STEP 10: Receive chunks
                try (OutputStream fileOutput =
                        Files.newOutputStream(tempFile)) {

                    byte[] buffer = new byte[CHUNK_SIZE];

                    while (expectedOffset < fileSize) {

                        byte type = input.readByte();

                        if (type != Protocol.FILE_CHUNK) {
                            throw new IOException(
                                "Expected FILE_CHUNK, got: " + type
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

                        // Read the entire chunk
                        input.readFully(buffer, 0, length);

                        // Write the chunk to temporary file
                        fileOutput.write(buffer, 0, length);

                        // Calculate SHA-256 incrementally
                        digest.update(buffer, 0, length);

                        expectedOffset += length;
                        chunkNumber++;

                        // STEP 11: Acknowledge received chunk
                        output.writeByte(Protocol.CHUNK_ACK);
                        output.writeLong(expectedOffset);
                        output.flush();

                        // STEP 12: Progress event after ACK
                        listener.onProgress(
                            safeName,
                            expectedOffset,
                            fileSize
                        );

                        System.out.printf(
                            "\rReceiving [%s] Chunk %d: %.2f%%",
                            safeName,
                            chunkNumber,
                            expectedOffset * 100.0
                                / Math.max(fileSize, 1)
                        );
                    }

                    fileOutput.flush();
                }

                // STEP 13: Expect transfer completion
                byte completeType = input.readByte();

                if (completeType != Protocol.COMPLETE) {
                    throw new IOException(
                        "Expected COMPLETE, received: "
                        + completeType
                    );
                }

                // STEP 14: Verify SHA-256 hash
                String actualHash = HexFormat.of()
                        .formatHex(digest.digest());

                boolean verified = actualHash.equals(expectedHash);

                if (!verified) {
                    throw new IOException(
                        "SHA-256 MISMATCH: File verification failed"
                    );
                }

                // STEP 15: Save the verified file
                Path destination = folder.resolve(
                    UUID.randomUUID() + "-" + safeName
                );

                Files.move(tempFile, destination);
                saved = true;

                System.out.println(
                    "\nSHA-256 MATCH: File verified successfully!"
                );

                System.out.println(
                    "File saved: " + destination.toAbsolutePath()
                );

                // STEP 16: Send final verification result
                output.writeByte(Protocol.VERIFIED);
                output.flush();

                // STEP 17: Notify completion
                listener.onCompleted(safeName);
                completed = true;

            } catch (IOException e) {

                // Notify sender that a transfer error occurred
                try {
                    output.writeByte(Protocol.ERROR);
                    output.flush();
                } catch (IOException ignored) {
                    // Connection may already be closed
                }

                throw e;

            } finally {

                if (!saved) {
                    Files.deleteIfExists(tempFile);
                }
            }

        } catch (IOException e) {

            // Avoid reporting errors after successful completion
            if (!completed) {
                try {
                    listener.onError(fileName, e);
                } catch (RuntimeException callbackError) {
                    System.err.println(
                        "Error listener failed: "
                        + callbackError.getMessage()
                    );
                }
            }

            throw e;
        }
    }
}
