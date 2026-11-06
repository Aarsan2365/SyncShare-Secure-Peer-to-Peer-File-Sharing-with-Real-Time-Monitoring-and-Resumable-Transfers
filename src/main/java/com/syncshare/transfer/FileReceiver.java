
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

    private static final int BUFFER_SIZE = Protocol.BUFFER_SIZE;

    // Prevent overlapping console approval prompts.
    private static final Object APPROVAL_LOCK = new Object();

    public static void receive(Socket socket) throws IOException {

        DataInputStream input = new DataInputStream(
            socket.getInputStream()
        );

        DataOutputStream output = new DataOutputStream(
            socket.getOutputStream()
        );

        // STEP 1: Read Protocol V2 header
        ProtocolIO.readHeader(input);

        // STEP 2: Verify incoming message type
        ProtocolIO.expectMessageType(
            input,
            Protocol.FILE_OFFER
        );

        // STEP 3: Receive file information
        String rawFileName = input.readUTF();
        long fileSize = input.readLong();
        String expectedHash = input.readUTF();

        // STEP 4: Sanitize file name
        String safeFileName = rawFileName.replace('\\', '/');

        safeFileName = safeFileName.substring(
            safeFileName.lastIndexOf('/') + 1
        );

        // STEP 5: Validate file information
        if (safeFileName.isBlank()
                || safeFileName.equals(".")
                || safeFileName.equals("..")
                || fileSize < 0
                || fileSize > Protocol.MAX_FILE_SIZE
                || !expectedHash.matches("[0-9a-f]{64}")) {

            output.writeByte(Protocol.REJECT);
            output.flush();

            throw new IOException("Invalid file offer");
        }

        // STEP 6: Ask user to Accept or Reject
        String choice;

        synchronized (APPROVAL_LOCK) {

            System.out.println("\n===== Incoming File Request =====");

            System.out.println(
                "Sender IP : " + socket.getInetAddress()
            );

            System.out.println("File Name : " + safeFileName);
            System.out.println("File Size : " + fileSize + " bytes");

            System.out.println("\n1. Accept");
            System.out.println("2. Reject");
            System.out.print("Choose option: ");

            Scanner scanner = new Scanner(System.in);
            choice = scanner.nextLine().trim();
        }

        boolean accepted = choice.equals("1");

        // STEP 7: Send approval response
        output.writeByte(
            accepted ? Protocol.ACCEPT : Protocol.REJECT
        );

        output.flush();

        if (!accepted) {
            System.out.println("Transfer rejected by receiver.");
            return;
        }

        System.out.println("Transfer accepted!");

        // STEP 8: Create destination folder
        Path destinationFolder = Paths.get("received_files");

        Files.createDirectories(destinationFolder);

        // Receive into a temporary file first
        Path tempFile = Files.createTempFile(
            destinationFolder,
            "syncshare-",
            ".part"
        );

        boolean saved = false;

        try {

            MessageDigest digest = HashUtils.newSha256();

            // STEP 9: Receive file bytes
            try (OutputStream fileOutput =
                    Files.newOutputStream(tempFile)) {

                byte[] buffer = new byte[BUFFER_SIZE];

                long remaining = fileSize;
                long totalReceived = 0;

                while (remaining > 0) {

                    int bytesRead = input.read(
                        buffer,
                        0,
                        (int) Math.min(buffer.length, remaining)
                    );

                    if (bytesRead == -1) {
                        throw new EOFException(
                            "Connection closed before transfer completed"
                        );
                    }

                    fileOutput.write(buffer, 0, bytesRead);

                    // Update SHA-256 using received bytes
                    digest.update(buffer, 0, bytesRead);

                    totalReceived += bytesRead;
                    remaining -= bytesRead;

                    double progress = (
                        totalReceived * 100.0
                    ) / Math.max(fileSize, 1);

                    System.out.printf(
                        "\rReceiving: %.2f%%",
                        progress
                    );
                }
            }

            // STEP 10: Calculate received file hash
            String actualHash = HexFormat.of().formatHex(
                digest.digest()
            );

            // STEP 11: Compare expected and actual hash
            boolean verified = actualHash.equals(expectedHash);

            if (verified) {

                // Save only a verified file
                Path destination = destinationFolder.resolve(
                    UUID.randomUUID() + "-" + safeFileName
                );

                Files.move(tempFile, destination);

                saved = true;

                System.out.println(
                    "\nSHA-256 MATCH: File verified successfully!"
                );

                System.out.println(
                    "File saved: " + destination.toAbsolutePath()
                );

            } else {

                System.out.println(
                    "\nSHA-256 MISMATCH: File verification failed!"
                );

                System.out.println(
                    "Expected: " + expectedHash
                );

                System.out.println(
                    "Actual  : " + actualHash
                );
            }

            // STEP 12: Send final verification response
            output.writeByte(
                verified ? Protocol.VERIFIED : Protocol.ERROR
            );

            output.flush();

        } finally {

            // Delete incomplete or corrupted temporary files
            if (!saved) {
                Files.deleteIfExists(tempFile);
            }
        }
    }
}
