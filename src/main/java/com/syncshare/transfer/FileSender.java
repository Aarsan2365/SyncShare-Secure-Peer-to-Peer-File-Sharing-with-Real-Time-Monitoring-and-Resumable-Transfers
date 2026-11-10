// src/main/java/com/syncshare/transfer/FileSender.java
package com.syncshare.transfer;

import com.syncshare.events.ConsoleTransferListener;
import com.syncshare.events.TransferListener;
import com.syncshare.network.Protocol;
import com.syncshare.network.ProtocolIO;


import java.io.*;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

import com.syncshare.security.TlsSupport;

public class FileSender {

    public static void send(
            Socket socket,
            Path file
    ) throws IOException {
        send(socket, file, new ConsoleTransferListener());
    }

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

            String peerId = TlsSupport.authenticate(socket);
            System.out.println("Authenticated receiver: " + peerId);
            if (!Files.isRegularFile(file)) {
                throw new IOException("File not found: " + file);
            }

            long fileSize = Files.size(file);

            if (fileSize > Protocol.MAX_FILE_SIZE) {
                throw new IOException("File exceeds size limit");
            }

            String senderHash = HashUtils.calculateSHA256(file);

            DataOutputStream output = new DataOutputStream(
                socket.getOutputStream()
            );

            DataInputStream input = new DataInputStream(
                socket.getInputStream()
            );

            // Existing Protocol V2 fields remain unchanged.
            ProtocolIO.writeHeader(output);

            output.writeByte(Protocol.FILE_OFFER);
            output.writeUTF(fileName);
            output.writeLong(fileSize);
            output.writeUTF(senderHash);
            output.flush();

            System.out.println("Waiting for receiver approval...");

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

            ProtocolIO.expectMessageType(
                input,
                Protocol.RESUME_REQUEST
            );

            long resumeOffset = input.readLong();

            if (resumeOffset < 0 || resumeOffset > fileSize) {
                throw new IOException(
                    "Invalid resume offset: " + resumeOffset
                );
            }

            if (Files.size(file) != fileSize) {
                throw new IOException("Source file size changed");
            }

            System.out.println(
                "Resume requested from byte: " + resumeOffset
            );

            listener.onStarted(fileName, fileSize);
            listener.onProgress(fileName, resumeOffset, fileSize);

            long offset = resumeOffset;

            try (InputStream fileInput = Files.newInputStream(file)) {
                // Start from the receiver's validated checkpoint.
                fileInput.skipNBytes(resumeOffset);

                byte[] buffer = new byte[Protocol.BUFFER_SIZE];

                while (offset < fileSize) {
                    int requested = (int) Math.min(
                        buffer.length,
                        fileSize - offset
                    );

                    int count = fileInput.read(buffer, 0, requested);

                    if (count < 0) {
                        throw new EOFException(
                            "Source file ended unexpectedly"
                        );
                    }

                    if (count == 0) {
                        continue;
                    }

                    output.writeByte(Protocol.FILE_CHUNK);
                    output.writeLong(offset);
                    output.writeInt(count);
                    output.write(buffer, 0, count);
                    output.flush();

                    byte ackType = input.readByte();

                    if (ackType == Protocol.ERROR) {
                        throw new IOException(
                            "Receiver reported a transfer error"
                        );
                    }

                    if (ackType != Protocol.CHUNK_ACK) {
                        throw new IOException(
                            "Expected CHUNK_ACK, received: " + ackType
                        );
                    }

                    long acknowledgedOffset = input.readLong();

                    if (acknowledgedOffset != offset + count) {
                        throw new IOException(
                            "Invalid ACK offset: " + acknowledgedOffset
                        );
                    }

                    offset = acknowledgedOffset;

                    listener.onProgress(
                        fileName,
                        offset,
                        fileSize
                    );
                }
            }

            output.writeByte(Protocol.COMPLETE);
            output.flush();

            byte result = input.readByte();

            if (result != Protocol.VERIFIED) {
                throw new IOException(
                    "Transfer verification failed. Response: " + result
                );
            }

            System.out.println(
                "SUCCESS: File transferred and verified!"
            );

        } catch (IOException e) {
            try {
                listener.onError(fileName, e);
            } catch (RuntimeException ignored) {
            }

            throw e;
        }

        try {
            listener.onCompleted(fileName);
        } catch (RuntimeException e) {
            System.err.println(
                "Completion listener error: " + e.getMessage()
            );
        }
    }
}