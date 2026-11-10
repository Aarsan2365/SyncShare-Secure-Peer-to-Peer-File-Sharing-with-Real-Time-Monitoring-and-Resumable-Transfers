// src/main/java/com/syncshare/transfer/FileReceiver.java
package com.syncshare.transfer;

import com.syncshare.approval.ApprovalHandler;
import com.syncshare.events.ConsoleTransferListener;
import com.syncshare.events.TransferListener;
import com.syncshare.network.Protocol;
import com.syncshare.network.ProtocolIO;
import com.syncshare.resume.ResumeStore;

import java.io.*;
import java.net.Socket;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Scanner;

public class FileReceiver {

    private static final Object APPROVAL_LOCK = new Object();
    private static final Scanner CONSOLE = new Scanner(System.in);

    public static void receive(Socket socket) throws IOException {
        receive(socket, new ConsoleTransferListener(), null);
    }

    public static void receive(
            Socket socket,
            TransferListener listener
    ) throws IOException {
        receive(socket, listener, null);
    }

    public static void receive(
            Socket socket,
            TransferListener listener,
            ApprovalHandler approvalHandler
    ) throws IOException {

        Objects.requireNonNull(socket, "socket");
        Objects.requireNonNull(listener, "listener");

        String fileName = "unknown";

        DataInputStream input = new DataInputStream(
            socket.getInputStream()
        );

        DataOutputStream output = new DataOutputStream(
            socket.getOutputStream()
        );

        try {
            ProtocolIO.readHeader(input);
            ProtocolIO.expectMessageType(input, Protocol.FILE_OFFER);

            String rawName = input.readUTF();
            long fileSize = input.readLong();
            String expectedHash = input.readUTF();

            String safeName = rawName.replace('\\', '/');
            safeName = safeName.substring(safeName.lastIndexOf('/') + 1);
            fileName = safeName;

            if (safeName.isBlank()
                    || safeName.equals(".")
                    || safeName.equals("..")
                    || safeName.indexOf('\0') >= 0
                    || fileSize < 0
                    || fileSize > Protocol.MAX_FILE_SIZE
                    || !expectedHash.matches("[0-9a-f]{64}")) {

                output.writeByte(Protocol.REJECT);
                output.flush();

                throw new IOException("Invalid file offer");
            }

            boolean accepted;

            if (approvalHandler != null) {
                accepted = approvalHandler.approve(
                    safeName,
                    fileSize,
                    socket.getInetAddress()
                );
            } else {
                synchronized (APPROVAL_LOCK) {
                    System.out.println("\nIncoming file: " + safeName);
                    System.out.println("Size: " + fileSize + " bytes");
                    System.out.println("Sender: " + socket.getInetAddress());
                    System.out.print("1. Accept / 2. Reject: ");

                    accepted = CONSOLE.hasNextLine()
                        && CONSOLE.nextLine().trim().equals("1");
                }
            }

            output.writeByte(
                accepted ? Protocol.ACCEPT : Protocol.REJECT
            );
            output.flush();

            if (!accepted) {
                return;
            }

            Path folder = Path.of(
                System.getProperty(
                    "syncshare.receiveDir",
                    "received_files"
                )
            );

            try (ResumeStore store = new ResumeStore(
                    folder,
                    safeName,
                    fileSize,
                    expectedHash
            )) {
                long expectedOffset = store.offset();

                output.writeByte(Protocol.RESUME_REQUEST);
                output.writeLong(expectedOffset);
                output.flush();

                System.out.println(
                    "Resume request sent. Offset: " + expectedOffset
                );

                listener.onStarted(safeName, fileSize);
                listener.onProgress(safeName, expectedOffset, fileSize);

                byte[] buffer = new byte[Protocol.BUFFER_SIZE];

                while (expectedOffset < fileSize) {
                    ProtocolIO.expectMessageType(
                        input,
                        Protocol.FILE_CHUNK
                    );

                    long offset = input.readLong();
                    int length = input.readInt();

                    if (offset != expectedOffset
                            || length <= 0
                            || length > buffer.length
                            || length > fileSize - expectedOffset) {

                        throw new IOException(
                            "Invalid chunk offset or length"
                        );
                    }

                    input.readFully(buffer, 0, length);

                    // Persist both data and checkpoint before acknowledging.
                    store.append(buffer, length);
                    expectedOffset = store.offset();

                    output.writeByte(Protocol.CHUNK_ACK);
                    output.writeLong(expectedOffset);
                    output.flush();

                    listener.onProgress(
                        safeName,
                        expectedOffset,
                        fileSize
                    );
                }

                ProtocolIO.expectMessageType(input, Protocol.COMPLETE);

                Path destination = store.finish();

                output.writeByte(Protocol.VERIFIED);
                output.flush();

                System.out.println(
                    "File verified and saved: "
                    + destination.toAbsolutePath()
                );
            }

        } catch (IOException e) {
            // Preserve the partial file and checkpoint for another attempt.
            try {
                output.writeByte(Protocol.ERROR);
                output.flush();
            } catch (IOException ignored) {
            }

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