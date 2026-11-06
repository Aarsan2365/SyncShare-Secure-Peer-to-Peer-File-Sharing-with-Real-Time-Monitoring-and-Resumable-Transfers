
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

    // Shared lock for console approval requests
    private static final Object APPROVAL_LOCK = new Object();

    // Shared console input for all receiver workers
    private static final Scanner CONSOLE =
            new Scanner(System.in);

    public static void receive(Socket socket) throws IOException {

        DataInputStream input = new DataInputStream(
            socket.getInputStream()
        );

        DataOutputStream output = new DataOutputStream(
            socket.getOutputStream()
        );

        // STEP 1: Validate Protocol V2 header
        ProtocolIO.readHeader(input);

        // STEP 2: Check incoming message type
        ProtocolIO.expectMessageType(
            input,
            Protocol.FILE_OFFER
        );

        // STEP 3: Read incoming file metadata
        String rawFileName = input.readUTF();
        long fileSize = input.readLong();
        String expectedHash = input.readUTF();

        // STEP 4: Sanitize filename
        String safeFileName = rawFileName.replace('\\', '/');

        safeFileName = safeFileName.substring(
            safeFileName.lastIndexOf('/') + 1
        );

        // STEP 5: Validate metadata
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

        // STEP 6: Ask permission using approval lock
        boolean accepted;

        synchronized (APPROVAL_LOCK) {

            System.out.println(
                "\n===== Incoming File Request ====="
            );

            System.out.println(
                "Sender IP : " + socket.getInetAddress()
            );

            System.out.println(
                "File Name : " + safeFileName
            );

            System.out.println(
                "File Size : " + fileSize + " bytes"
            );

            System.out.println("\n1. Accept");
            System.out.println("2. Reject");
            System.out.print("Choose option: ");

            String choice = CONSOLE.nextLine().trim();

            accepted = choice.equals("1");
        }

        // STEP 7: Send ACCEPT or REJECT
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

        System.out.println("Transfer accepted!");

        // STEP 8: Create received files directory
        Path destinationFolder = Paths.get("received_files");

        Files.createDirectories(destinationFolder);

        // Write to temporary file until verification
        Path tempFile = Files.createTempFile(
            destinationFolder,
            "syncshare-",
            ".part"
        );

        boolean saved = false;

        try {
            MessageDigest digest = HashUtils.newSha256();

            // STEP 9: Receive file data
            try (OutputStream fileOutput =
                    Files.newOutputStream(tempFile)) {

                byte[] buffer = new byte[BUFFER_SIZE];

                long remaining = fileSize;
                long totalReceived = 0;

                while (remaining > 0) {

                    int bytesRead = input.read(
                        buffer,
                        0,
                        (int) Math.min(
                            buffer.length,
                            remaining
                        )
                    );

                    if (bytesRead == -1) {
                        throw new EOFException(
                            "Connection closed before transfer completed"
                        );
                    }

                    fileOutput.write(
                        buffer,
                        0,
                        bytesRead
                    );

                    // Calculate SHA-256 while receiving
                    digest.update(
                        buffer,
                        0,
                        bytesRead
                    );

                    totalReceived += bytesRead;
                    remaining -= bytesRead;

                    double progress =
                        totalReceived * 100.0
                        / Math.max(fileSize, 1);

                    System.out.printf(
                        "\rReceiving [%s]: %.2f%%",
                        safeFileName,
                        progress
                    );
                }
            }

            // STEP 10: Generate received file hash
            String actualHash = HexFormat.of()
                    .formatHex(digest.digest());

            // STEP 11: Compare hashes
            boolean verified =
                    actualHash.equals(expectedHash);

            if (verified) {

                Path destination = destinationFolder.resolve(
                    UUID.randomUUID()
                        + "-"
                        + safeFileName
                );

                Files.move(
                    tempFile,
                    destination
                );

                saved = true;

                System.out.println(
                    "\nSHA-256 MATCH: File verified successfully!"
                );

                System.out.println(
                    "File saved: "
                    + destination.toAbsolutePath()
                );

            } else {

                System.out.println(
                    "\nSHA-256 MISMATCH: Verification failed!"
                );

                System.out.println(
                    "Expected Hash: " + expectedHash
                );

                System.out.println(
                    "Actual Hash  : " + actualHash
                );
            }

            // STEP 12: Inform sender of verification result
            output.writeByte(
                verified
                    ? Protocol.VERIFIED
                    : Protocol.ERROR
            );

            output.flush();

        } finally {

            // Remove incomplete or corrupted files
            if (!saved) {
                Files.deleteIfExists(tempFile);
            }
        }
    }
}
