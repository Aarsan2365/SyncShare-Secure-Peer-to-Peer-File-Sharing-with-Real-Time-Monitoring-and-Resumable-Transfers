package com.syncshare.transfer;

import com.syncshare.network.Protocol;
import com.syncshare.network.ProtocolIO;

import java.io.*;
import java.net.Socket;
import java.nio.file.*;

public class FileSender {

    private static final int CHUNK_SIZE = Protocol.BUFFER_SIZE;

    public static void send(Socket socket, Path file)
            throws IOException {

        // 1. Validate source file
        if (!Files.isRegularFile(file)) {
            throw new IOException("File not found: " + file);
        }

        long fileSize = Files.size(file);

        if (fileSize > Protocol.MAX_FILE_SIZE) {
            throw new IOException("File exceeds size limit");
        }

        DataOutputStream output = new DataOutputStream(
            socket.getOutputStream()
        );

        DataInputStream input = new DataInputStream(
            socket.getInputStream()
        );

        // 2. Calculate original file hash
        String senderHash = HashUtils.calculateSHA256(file);

        // 3. Send Protocol V2 header
        ProtocolIO.writeHeader(output);

        // 4. Send FILE_OFFER
        ProtocolIO.writeMessageType(
            output,
            Protocol.FILE_OFFER
        );

        output.writeUTF(file.getFileName().toString());
        output.writeLong(fileSize);
        output.writeUTF(senderHash);
        output.flush();

        System.out.println("File offer sent.");
        System.out.println("Waiting for receiver approval...");

        // 5. Receive ACCEPT / REJECT
        byte response = input.readByte();

        if (response == Protocol.REJECT) {
            System.out.println("Receiver rejected the file.");
            return;
        }

        if (response != Protocol.ACCEPT) {
            throw new IOException("Unexpected approval response");
        }

        
     System.out.println("Receiver accepted the file.");

      // Receive resume request from receiver
    byte resumeType = input.readByte();

       if (resumeType != Protocol.RESUME_REQUEST) {
               throw new IOException(
                "Expected RESUME_REQUEST, received: " + resumeType
          );
     }

// Receiver tells us where to continue
long resumeOffset = input.readLong();

// Validate the resume offset
if (resumeOffset < 0 || resumeOffset > fileSize) {
    throw new IOException(
        "Invalid resume offset: " + resumeOffset
    );
}

System.out.println(
    "Resume requested from byte: " + resumeOffset
);

// Confirm source file size
if (Files.size(file) != fileSize) {
    throw new IOException("Source file size changed");
}


        // 7. Send file in acknowledged chunks

long offset = resumeOffset;
int chunkNumber = 0;

try (InputStream fileInput = Files.newInputStream(file)) {

    // Skip bytes already acknowledged and saved by receiver
    fileInput.skipNBytes(resumeOffset);

    byte[] buffer = new byte[CHUNK_SIZE];


            while (offset < fileSize) {

                int toRead = (int) Math.min(
                    CHUNK_SIZE,
                    fileSize - offset
                );

                int bytesRead = fileInput.read(
                    buffer, 0, toRead
                );

                if (bytesRead <= 0) {
                    throw new EOFException(
                        "Source file ended unexpectedly"
                    );
                }

                // Send FILE_CHUNK frame
                output.writeByte(Protocol.FILE_CHUNK);
                output.writeLong(offset);
                output.writeInt(bytesRead);
                output.write(buffer, 0, bytesRead);
                output.flush();

                // Wait for receiver ACK
                byte ackType = input.readByte();

                if (ackType == Protocol.ERROR) {
                    throw new IOException(
                        "Receiver rejected chunk"
                    );
                }

                if (ackType != Protocol.CHUNK_ACK) {
                    throw new IOException(
                        "Expected CHUNK_ACK, received: "
                        + ackType
                    );
                }

                long acknowledgedOffset = input.readLong();
                long expectedOffset = offset + bytesRead;

                if (acknowledgedOffset != expectedOffset) {
                    throw new IOException(
                        "Invalid ACK offset: "
                        + acknowledgedOffset
                    );
                }

                offset = acknowledgedOffset;
                chunkNumber++;

                double progress =
                    offset * 100.0 / Math.max(fileSize, 1);

                System.out.printf(
                    "\rChunk %d ACK | Progress: %.2f%%",
                    chunkNumber,
                    progress
                );
            }
        }

        // 8. Tell receiver all file chunks are sent
        output.writeByte(Protocol.COMPLETE);
        output.flush();

        System.out.println("\nAll chunks acknowledged.");
        System.out.println("Waiting for SHA-256 verification...");

        // 9. Receive final verification result
        byte result = input.readByte();

        if (result == Protocol.VERIFIED) {

            System.out.println(
                "SUCCESS: File transferred and verified!"
            );

        } else if (result == Protocol.ERROR) {

            throw new IOException(
                "Receiver reported verification failure"
            );

        } else {

            throw new IOException(
                "Unexpected final response: " + result
            );
        }
    }
}
