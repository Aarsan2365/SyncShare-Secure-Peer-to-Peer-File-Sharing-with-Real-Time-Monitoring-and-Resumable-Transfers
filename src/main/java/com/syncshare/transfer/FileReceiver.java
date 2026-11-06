package com.syncshare.transfer;

import java.io.*;
import java.net.Socket;
import java.nio.file.*;

public class FileReceiver {
    public static void receive(Socket socket) throws IOException {

        DataInputStream input = new DataInputStream(
            socket.getInputStream()
        );

        // Receive file information
        String fileName = input.readUTF();
        long fileSize = input.readLong();

        // Basic validation
        if (fileSize < 0 || fileSize > 1_073_741_824L) {
            throw new IOException("Invalid file size");
        }

        // Ignore directory paths sent by a remote peer
        String safeName = fileName.replace('\\', '/');
        safeName = safeName.substring(
            safeName.lastIndexOf('/') + 1
        );

        if (safeName.isBlank() ||
            safeName.equals(".") ||
            safeName.equals("..")) {
            throw new IOException("Invalid file name");
        }

        System.out.println("Receiving: " + safeName);
        System.out.println("File size: " + fileSize + " bytes");

        Path folder = Paths.get("received_files");
        Files.createDirectories(folder);

        Path tempFile = Files.createTempFile(
            folder, "syncshare-", ".part"
        );

        boolean completed = false;

        try {
            try (OutputStream output =
                    Files.newOutputStream(tempFile)) {

                byte[] buffer = new byte[8192];
                long remaining = fileSize;
                long received = 0;

                while (remaining > 0) {

                    int bytesRead = input.read(
                        buffer,
                        0,
                        (int) Math.min(buffer.length, remaining)
                    );

                    if (bytesRead == -1) {
                        throw new EOFException(
                            "Connection closed before file completed"
                        );
                    }

                    output.write(buffer, 0, bytesRead);

                    received += bytesRead;
                    remaining -= bytesRead;

                    double progress =
                        (received * 100.0) / fileSize;

                    System.out.printf(
                        "\rReceiving: %.2f%%", progress
                    );
                }
            }

            Path destination = folder.resolve(
                java.util.UUID.randomUUID() + "-" + safeName
            );

            Files.move(tempFile, destination);
            completed = true;

            System.out.println(
                "\nFile saved: " + destination.toAbsolutePath()
            );

        } finally {
            if (!completed) {
                Files.deleteIfExists(tempFile);
            }
        }
    }

}
