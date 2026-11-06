
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

    private static final int CHUNK_SIZE = Protocol.BUFFER_SIZE;

    private static final Object APPROVAL_LOCK = new Object();

    private static final Scanner CONSOLE =
            new Scanner(System.in);

    public static void receive(Socket socket)
            throws IOException {

        DataInputStream input = new DataInputStream(
            socket.getInputStream()
        );

        DataOutputStream output = new DataOutputStream(
            socket.getOutputStream()
        );

        // 1. Validate Protocol V2
        ProtocolIO.readHeader(input);

        ProtocolIO.expectMessageType(
            input,
            Protocol.FILE_OFFER
        );

        // 2. Receive file metadata
        String rawName = input.readUTF();
        long fileSize = input.readLong();
        String expectedHash = input.readUTF();

        String safeName = rawName.replace('\\', '/');

        safeName = safeName.substring(
            safeName.lastIndexOf('/') + 1
        );

        // 3. Validate the file offer
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

        // 4. Ask receiver approval
        boolean accepted;

        synchronized (APPROVAL_LOCK) {

            System.out.println(
                "\n===== Incoming File Request ====="
            );

            System.out.println(
                "Sender IP: " + socket.getInetAddress()
            );

            System.out.println("File: " + safeName);
            System.out.println("Size: " + fileSize + " bytes");

            System.out.println("1. Accept");
            System.out.println("2. Reject");
            System.out.print("Choose option: ");

            accepted = CONSOLE.nextLine()
                .trim().equals("1");
        }

        // 5. Send approval response
        output.writeByte(
            accepted ? Protocol.ACCEPT : Protocol.REJECT
        );
        output.flush();

        if (!accepted) {
            System.out.println("Transfer rejected.");
            return;
        }

        System.out.println("Transfer accepted.");

        // 6. Create temporary destination
        Path folder = Paths.get("received_files");
        Files.createDirectories(folder);

        Path tempFile = Files.createTempFile(
            folder, "syncshare-", ".part"
        );

        boolean saved = false;

        try {
            MessageDigest digest = HashUtils.newSha256();

            long expectedOffset = 0;
            int chunksReceived = 0;

            // 7. Read and validate each chunk
            try (OutputStream fileOutput =
                    Files.newOutputStream(tempFile)) {

                while (expectedOffset < fileSize) {

                    byte messageType = input.readByte();

                    if (messageType != Protocol.FILE_CHUNK) {
                        throw new IOException(
                            "Expected FILE_CHUNK"
                        );
                    }

                    long offset = input.readLong();
                    int length = input.readInt();

                    // Verify chunk location and size
                    if (offset != expectedOffset
                            || length <= 0
                            || length > CHUNK_SIZE
                            || length > fileSize - expectedOffset) {

                        throw new IOException(
                            "Invalid chunk offset or length"
                        );
                    }

                    // Read exactly this chunk's data
                    byte[] buffer = new byte[length];
                    input.readFully(buffer);

                    // Write chunk to temporary file
                    fileOutput.write(buffer);

                    // Update SHA-256
                    digest.update(buffer);

                    expectedOffset += length;
                    chunksReceived++;

                    // Send ACK for received chunk
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

            // 8. Sender must explicitly finish the transfer
            byte completeType = input.readByte();

            if (completeType != Protocol.COMPLETE) {
                throw new IOException(
                    "Expected COMPLETE message"
                );
            }

            // 9. Verify final file hash
            String actualHash = HexFormat.of()
                .formatHex(digest.digest());

            boolean verified =
                actualHash.equals(expectedHash);

            if (!verified) {

                System.out.println(
                    "\nSHA-256 MISMATCH: Transfer failed!"
                );

                output.writeByte(Protocol.ERROR);
                output.flush();
                return;
            }

            // 10. Save verified file
            Path destination = folder.resolve(
                UUID.randomUUID() + "-" + safeName
            );

            Files.move(tempFile, destination);
            saved = true;

            System.out.println(
                "\nSHA-256 MATCH: File verified!"
            );

            System.out.println(
                "File saved: " + destination.toAbsolutePath()
            );

            // 11. Send final success response
            output.writeByte(Protocol.VERIFIED);
            output.flush();

        } catch (IOException e) {

            // Best-effort protocol error response
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
    }
}
