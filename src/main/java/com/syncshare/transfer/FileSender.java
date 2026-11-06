
package com.syncshare.transfer;

import com.syncshare.network.Protocol;
import com.syncshare.network.ProtocolIO;
import com.syncshare.events.TransferListener;
import com.syncshare.events.ConsoleTransferListener;

import java.io.*;
import java.net.Socket;
import java.nio.file.*;
import java.util.Objects;

public class FileSender {

    private static final int CHUNK_SIZE = Protocol.BUFFER_SIZE;

    // Existing method: keeps TcpClient working
    public static void send(Socket socket, Path file)
            throws IOException {

        send(socket, file, new ConsoleTransferListener());
    }

    // New method: supports Member 3's JavaFX listener
    public static void send(
            Socket socket,
            Path file,
            TransferListener listener
    ) throws IOException {

        Objects.requireNonNull(socket, "socket");
        Objects.requireNonNull(file, "file");
        Objects.requireNonNull(listener, "listener");

        String fileName = file.getFileName().toString();

        try {
            // STEP 1: Validate file
            if (!Files.isRegularFile(file)) {
                throw new IOException("File not found: " + file);
            }

            long fileSize = Files.size(file);

            if (fileSize > Protocol.MAX_FILE_SIZE) {
                throw new IOException("File exceeds size limit");
            }

            // STEP 2: Open TCP streams
            DataOutputStream output = new DataOutputStream(
                socket.getOutputStream()
            );

            DataInputStream input = new DataInputStream(
                socket.getInputStream()
            );

            // STEP 3: Calculate SHA-256
            String senderHash = HashUtils.calculateSHA256(file);

            // STEP 4: Send Protocol V2 header and offer
            ProtocolIO.writeHeader(output);
            ProtocolIO.writeMessageType(
                output,
                Protocol.FILE_OFFER
            );

            output.writeUTF(fileName);
            output.writeLong(fileSize);
            output.writeUTF(senderHash);
            output.flush();

            System.out.println("File offer sent.");
            System.out.println("Waiting for receiver approval...");

            // STEP 5: Accept or Reject
            byte response = input.readByte();

            if (response == Protocol.REJECT) {
                System.out.println("Receiver rejected the file.");
                return;
            }

            if (response != Protocol.ACCEPT) {
                throw new IOException(
                    "Unexpected response: " + response
                );
            }

            System.out.println("Receiver accepted the file.");

            // STEP 6: Resume negotiation
            byte resumeType = input.readByte();

            if (resumeType != Protocol.RESUME_REQUEST) {
                throw new IOException(
                    "Expected RESUME_REQUEST, received: "
                    + resumeType
                );
            }

            long resumeOffset = input.readLong();

            if (resumeOffset < 0 || resumeOffset > fileSize) {
                throw new IOException(
                    "Invalid resume offset: " + resumeOffset
                );
            }

            // Until checkpoint integration, only zero is safe.
            if (resumeOffset != 0L) {
                throw new IOException(
                    "Non-zero resume requires verified checkpoint integration"
                );
            }

            if (Files.size(file) != fileSize) {
                throw new IOException("Source file size changed");
            }

            System.out.println(
                "Resume requested from byte: " + resumeOffset
            );

            // STEP 7: Started event
            listener.onStarted(fileName, fileSize);

            long offset = resumeOffset;
            int chunkNumber = 0;

            // STEP 8: Transfer chunks
            try (InputStream fileInput = Files.newInputStream(file)) {

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

                    // Send FILE_CHUNK message
                    output.writeByte(Protocol.FILE_CHUNK);
                    output.writeLong(offset);
                    output.writeInt(bytesRead);
                    output.write(buffer, 0, bytesRead);
                    output.flush();

                    // Wait for CHUNK_ACK
                    byte ackType = input.readByte();

                    if (ackType == Protocol.ERROR) {
                        throw new IOException(
                            "Receiver reported chunk error"
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

                    // STEP 9: Progress event after ACK
                    listener.onProgress(
                        fileName,
                        offset,
                        fileSize
                    );

                    System.out.printf(
                        "\rChunk %d ACK | %.2f%%",
                        chunkNumber,
                        offset * 100.0 / Math.max(fileSize, 1)
                    );
                }
            }

            // STEP 10: Send COMPLETE
            output.writeByte(Protocol.COMPLETE);
            output.flush();

            System.out.println("\nAll chunks acknowledged.");
            System.out.println(
                "Waiting for SHA-256 verification..."
            );

            // STEP 11: Final verification
            byte result = input.readByte();

            if (result != Protocol.VERIFIED) {
                throw new IOException(
                    "Transfer not verified. Response: "
                    + result
                );
            }

            // Ensure empty files also report 100% progress
            if (fileSize == 0) {
                listener.onProgress(fileName, 0, 0);
            }

            // STEP 12: Completion event
            listener.onCompleted(fileName);

            System.out.println(
                "SUCCESS: File transferred and verified!"
            );

        } catch (IOException e) {

            // STEP 13: Error event
            listener.onError(fileName, e);
            throw e;
        }
    }
}
