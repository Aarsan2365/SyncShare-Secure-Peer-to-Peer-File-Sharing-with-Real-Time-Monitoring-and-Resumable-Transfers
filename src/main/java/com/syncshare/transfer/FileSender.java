
package com.syncshare.transfer;

import com.syncshare.network.Protocol;
import com.syncshare.network.ProtocolIO;

import java.io.*;
import java.net.Socket;
import java.nio.file.*;

public class FileSender {

    private static final int BUFFER_SIZE = Protocol.BUFFER_SIZE;

    public static void send(Socket socket, Path file)
            throws IOException {

        // Step 1: Validate source file
        if (!Files.isRegularFile(file)) {
            throw new IOException("File not found: " + file);
        }

        long fileSize = Files.size(file);

        if (fileSize > Protocol.MAX_FILE_SIZE) {
            throw new IOException("File exceeds demo size limit");
        }

        DataOutputStream output = new DataOutputStream(
            socket.getOutputStream()
        );

        DataInputStream input = new DataInputStream(
            socket.getInputStream()
        );

        // Step 2: Calculate SHA-256
        String senderHash = HashUtils.calculateSHA256(file);

        // Step 3: Send Protocol V2 header
        ProtocolIO.writeHeader(output);

        // Step 4: Send FILE_OFFER message type
        ProtocolIO.writeMessageType(
            output,
            Protocol.FILE_OFFER
        );

        // Step 5: Send file metadata
        output.writeUTF(file.getFileName().toString());
        output.writeLong(fileSize);
        output.writeUTF(senderHash);
        output.flush();

        System.out.println("Protocol V2 file offer sent.");
        System.out.println("Waiting for receiver approval...");

        // Step 6: Receive ACCEPT or REJECT
        byte response = input.readByte();

        if (response == Protocol.REJECT) {
            System.out.println("Receiver rejected the file.");
            return;
        }

        if (response != Protocol.ACCEPT) {
            throw new IOException(
                "Unexpected approval response: " + response
            );
        }

        System.out.println("Receiver accepted the file.");

        if (Files.size(file) != fileSize) {
            throw new IOException("Source file size changed");
        }

        // Step 7: Send file contents
        try (InputStream fileInput =
                Files.newInputStream(file)) {

            byte[] buffer = new byte[BUFFER_SIZE];
            long remaining = fileSize;
            long totalSent = 0;

            while (remaining > 0) {

                int bytesRead = fileInput.read(
                    buffer,
                    0,
                    (int) Math.min(buffer.length, remaining)
                );

                if (bytesRead == -1) {
                    throw new EOFException(
                        "Source file changed during transfer"
                    );
                }

                output.write(buffer, 0, bytesRead);

                totalSent += bytesRead;
                remaining -= bytesRead;

                double progress =
                    totalSent * 100.0 / Math.max(fileSize, 1);

                System.out.printf(
                    "\rSending: %.2f%%", progress
                );
            }
        }

        output.flush();

        System.out.println("\nFile bytes sent.");
        System.out.println("Waiting for verification...");

        // Step 8: Receive VERIFIED or ERROR
        byte result = input.readByte();

        if (result == Protocol.VERIFIED) {
            System.out.println(
                "SUCCESS: File received and SHA-256 verified!"
            );

        } else if (result == Protocol.ERROR) {
            throw new IOException(
                "Receiver reported verification failure"
            );

        } else {
            throw new IOException(
                "Unexpected verification response: " + result
            );
        }
    }
}

