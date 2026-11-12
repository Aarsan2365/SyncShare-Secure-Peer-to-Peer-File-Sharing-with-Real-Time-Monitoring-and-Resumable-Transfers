package com.syncshare.network;

import com.syncshare.events.ConsoleTransferListener;
import com.syncshare.events.TransferListener;
import com.syncshare.events.TransferSessionListener;
import com.syncshare.model.TransferDirection;
import com.syncshare.security.TlsSupport;
import com.syncshare.transfer.FileSender;

import java.io.IOException;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.function.Consumer;

public class TcpClient {
    public static void send(
        String ip,
        int port,
        Path file
    ) throws Exception {
        send(ip, port, file, new ConsoleTransferListener());
    }

    public static void send(
        String ip,
        int port,
        Path file,
        TransferListener listener
    ) throws Exception {

        try (Socket socket = TlsSupport.connect(ip, port)) {

            socket.setSoTimeout(180_000);

            FileSender.send(socket, file, listener);
        }
    }

    public static void send(
        String ip,
        int port,
        Path file,
        String transferId,
        TransferSessionListener sessionListener,
        Consumer<Socket> activeSocketConsumer
    ) throws Exception {
        String effectiveId = (transferId != null && !transferId.isBlank())
            ? transferId
            : UUID.randomUUID().toString();
        TransferSessionListener effectiveListener = sessionListener != null
            ? sessionListener
            : TransferSessionListener.NO_OP;
        String fileName = file != null && file.getFileName() != null
            ? file.getFileName().toString()
            : "unknown";
        long knownSize = -1L;
        if (file != null) {
            try {
                if (Files.isRegularFile(file)) {
                    knownSize = Files.size(file);
                }
            } catch (IOException ignored) {
            }
        }
        String endpoint = ip + ":" + port;

        try {
            effectiveListener.onConnecting(
                effectiveId,
                TransferDirection.OUTGOING,
                fileName,
                knownSize,
                endpoint,
                "Establishing mTLS 1.3 connection"
            );
        } catch (RuntimeException ignored) {
        }

        try (Socket socket = TlsSupport.connect(ip, port)) {
            if (activeSocketConsumer != null) {
                activeSocketConsumer.accept(socket);
            }
            socket.setSoTimeout(180_000);
            FileSender.send(socket, file, effectiveId, effectiveListener);
        } catch (Exception error) {
            // If connection failed before FileSender.send entered its try-block, report failure here.
            // Duplicate terminal calls are ignored by TransferMonitoringService.
            try {
                if (Thread.currentThread().isInterrupted()) {
                    effectiveListener.onCancelled(
                        effectiveId,
                        TransferDirection.OUTGOING,
                        fileName,
                        error.getMessage() != null ? error.getMessage() : "Transfer cancelled"
                    );
                } else {
                    effectiveListener.onFailed(
                        effectiveId,
                        TransferDirection.OUTGOING,
                        fileName,
                        error.getMessage() != null ? error.getMessage() : error.getClass().getSimpleName(),
                        error
                    );
                }
            } catch (RuntimeException ignored) {
            }
            throw error;
        } finally {
            if (activeSocketConsumer != null) {
                activeSocketConsumer.accept(null);
            }
        }
    }
}
