
package com.syncshare.transfer;

import com.syncshare.network.Protocol;
import com.syncshare.network.ProtocolIO;

import java.io.*;
import java.net.Socket;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Scanner;
import java.util.UUID;

public class FileReceiver {

    // Each chunk can contain up to 64 KiB
    private static final int CHUNK_SIZE = Protocol.BUFFER_SIZE;

    // Prevent multiple workers from asking for approval simultaneously
    private static final Object APPROVAL_LOCK = new Object();

    // Shared console input
    private static final Scanner CONSOLE = new Scanner(System.in);

    public static void receive(Socket socket) throws IOException {

        DataInputStream input = new DataInputStream(
            socket.getInputStream()
        );

        DataOutputStream output = new DataOutputStream(
            socket.getOutputStream()
        );

        // STEP 1: Validate SyncShare Protocol V2
        ProtocolIO.readHeader(input);

        ProtocolIO.expectMessageType(
            input,
            Protocol.FILE_OFFER
        );

        // STEP 2: Read incoming file information
        String rawName = input.readUTF();
        long fileSize = input.readLong();
        String expectedHash = input.readUTF();

        // STEP 3: Sanitize incoming filename
        String safeName = rawName.replace('\\', '/');

        safeName = safeName.substring(
            safeName.lastIndexOf('/') + 1
        );

        // STEP 4: Validate file information
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

        // STEP 5: Ask receiver to accept or reject
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

        // STEP 6: Send ACCEPT / REJECT response
        output.writeByte(
            accepted ? Protocol.ACCEPT : Protocol.REJECT
        );

        output.flush();

        if (!accepted) {
            System.out.println("Transfer rejected by receiver.");
            return;
        }

        System.out.println("Transfer accepted!");

        // STEP 7: Send RESUME_REQUEST to sender
        // Member 2 will later supply a verified checkpoint offset.
        long resumeOffset = 0L;

        output.writeByte(Protocol.RESUME_REQUEST);
        output.writeLong(resumeOffset);
        output.flush();

        System.out.println(
            "Resume request sent. Offset: " + resumeOffset
        );

        // STEP 8: Create received files directory
        Path destinationFolder = Paths.get("received_files");

        Files.createDirectories(destinationFolder);

        // Temporary file used until complete verification
        Path tempFile = Files.createTempFile(
            destinationFolder,
            "syncshare-",
            ".part"
        );

        boolean saved = false;

        try {

            MessageDigest digest = HashUtils.newSha256();

            long expectedOffset = resumeOffset;
            int chunksReceived = 0;

            // STEP 9: Receive chunks
            try (OutputStream fileOutput =
                    Files.newOutputStream(tempFile)) {

                byte[] buffer = new byte[CHUNK_SIZE];

                while (expectedOffset < fileSize) {

                    // Read message type
                    byte messageType = input.readByte();

                    if (messageType != Protocol.FILE_CHUNK) {
                        throw new IOException(
                            "Expected FILE_CHUNK, received: "
                            + messageType
                        );
                    }

                    // Read chunk metadata
                    long offset = input.readLong();
                    int length = input.readInt();

                    // Validate offset and length
                    if (offset != expectedOffset
                            || length <= 0
                            || length > CHUNK_SIZE
                            || length > fileSize - expectedOffset) {

                        throw new IOException(
                            "Invalid chunk offset or length"
                        );
                    }

                    // Read exactly the announced chunk length
                    input.readFully(buffer, 0, length);

                    // Save received bytes
                    fileOutput.write(buffer, 0, length);

                    // Update SHA-256
                    digest.update(buffer, 0, length);

                    expectedOffset += length;
                    chunksReceived++;

                    // STEP 10: Send chunk ACK
                    output.writeByte(Protocol.CHUNK_ACK);
                    output.writeLong(expectedOffset);
                    output.flush();

                    double progress =
                        expectedOffset * 100.0
                        / Math.max(fileSize, 1);

                    System.out.printf(
                        "\rReceiving [%s] Chunk %d: %.2f%%",
                        safeName,
                        chunksReceived,
                        progress
                    );
                }

                fileOutput.flush();
            }

            // STEP 11: Expect COMPLETE message
            byte completeType = input.readByte();

            if (completeType != Protocol.COMPLETE) {
                throw new IOException(
                    "Expected COMPLETE, received: "
                    + completeType
                );
            }

            // STEP 12: Calculate received file SHA-256
            String actualHash = HexFormat.of()
                    .formatHex(digest.digest());

            boolean verified =
                    actualHash.equals(expectedHash);

            if (!verified) {
                System.out.println(
                    "\nSHA-256 MISMATCH: Verification failed!"
                );

                output.writeByte(Protocol.ERROR);
                output.flush();
                return;
            }

            // STEP 13: Save verified file
            Path destination = destinationFolder.resolve(
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

            // STEP 14: Send final verification response
            output.writeByte(Protocol.VERIFIED);
            output.flush();

        } catch (IOException e) {

            // Send ERROR response when possible
            try {
                output.writeByte(Protocol.ERROR);
                output.flush();
            } catch (IOException ignored) {
                // Connection may already be closed
            }

            throw e;

        } finally {

            // Delete any incomplete or corrupted temporary file
            if (!saved) {
                Files.deleteIfExists(tempFile);
            }
        }
    }
}
