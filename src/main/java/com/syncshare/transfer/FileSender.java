package com.syncshare.transfer;

import java.io.*;
import java.net.Socket;
import java.nio.file.*;

public class FileSender {

     public static void send(
        Socket socket,
        Path file
    ) throws IOException {

        if (!Files.isRegularFile(file)) {
            throw new IOException("File not found: " + file);
        }

        long fileSize = Files.size(file);

        if (fileSize > 1_073_741_824L) {
            throw new IOException("Demo file limit is 1 GB");
        }

        DataOutputStream output =
            new DataOutputStream(socket.getOutputStream());

        // Send file information first
        output.writeUTF(file.getFileName().toString());
        output.writeLong(fileSize);

        try (InputStream input = Files.newInputStream(file)) {

            byte[] buffer = new byte[8192];
            int bytesRead;
            long sent = 0;

            while ((bytesRead = input.read(buffer)) != -1) {

                output.write(buffer, 0, bytesRead);

                sent += bytesRead;

                double progress =
                    (sent * 100.0) / Math.max(fileSize, 1);

                System.out.printf(
                    "\rSending: %.2f%%", progress
                );
            }
        }

        output.flush();

        System.out.println("\nFile data sent!");
    }
    
}
