package com.syncshare.network;

import com.syncshare.approval.ApprovalHandler;
import com.syncshare.events.ConsoleTransferListener;
import com.syncshare.events.TransferSessionListener;
import com.syncshare.security.TlsSupport;
import com.syncshare.transfer.FileReceiver;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public class PeerServer implements AutoCloseable {

    private final ServerSocket serverSocket;
    private final Thread listenerThread;
    private final ThreadPoolExecutor workers;
    private final ApprovalHandler approvalHandler;
    private final TransferSessionListener sessionListener;
    private final ConcurrentHashMap<String, Socket> activeTransferSockets = new ConcurrentHashMap<>();

    private volatile boolean running = false;
    private volatile boolean closed = false;

    public PeerServer(
            int port,
            ApprovalHandler approvalHandler
    ) throws IOException {
        this(port, approvalHandler, TransferSessionListener.NO_OP);
    }

    public PeerServer(
            int port,
            ApprovalHandler approvalHandler,
            TransferSessionListener sessionListener
    ) throws IOException {

        this.approvalHandler = approvalHandler;
        this.sessionListener = sessionListener != null
            ? sessionListener
            : TransferSessionListener.NO_OP;

        this.workers = new ThreadPoolExecutor(
            4,
            4,
            0L,
            TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(8),
            new ThreadPoolExecutor.AbortPolicy()
        );

        this.listenerThread = new Thread(
            this::listen,
            "syncshare-listener"
        );

        this.listenerThread.setDaemon(true);

        try {
            this.serverSocket = TlsSupport.listen(port);
        } catch (IOException | RuntimeException | Error e) {
            this.workers.shutdownNow();
            throw e;
        }
    }

    public synchronized void start() {

        if (closed || running || serverSocket.isClosed()) {
            return;
        }

        running = true;
        listenerThread.start();

        System.out.println(
            "Background TCP Receiver started on port "
            + serverSocket.getLocalPort()
        );
    }

    public int getLocalPort() {
        return serverSocket.getLocalPort();
    }

    private void listen() {

        while (running && !closed && !serverSocket.isClosed() && !Thread.currentThread().isInterrupted()) {

            try {
                Socket client = serverSocket.accept();
                dispatchClient(client);

            } catch (IOException e) {

                if (running && !closed && !serverSocket.isClosed()) {
                    System.err.println(
                        "Listener error: " + e.getMessage()
                    );
                }
            }
        }
    }

    private void dispatchClient(Socket client) {
        String transferId = UUID.randomUUID().toString();
        activeTransferSockets.put(transferId, client);

        try {
            workers.execute(() -> {

                try (client) {

                    client.setSoTimeout(180_000);

                    System.out.println(
                        "\nConnected: "
                        + client.getInetAddress()
                    );

                    FileReceiver.receive(
                        client,
                        new ConsoleTransferListener(),
                        approvalHandler,
                        transferId,
                        sessionListener
                    );

                } catch (Exception e) {
                    System.err.println(
                        "Incoming transfer failed: "
                        + e.getMessage()
                    );
                } finally {
                    activeTransferSockets.remove(transferId, client);
                }
            });

        } catch (RejectedExecutionException e) {
            activeTransferSockets.remove(transferId, client);

            if (!closed && !workers.isShutdown()) {
                System.out.println(
                    "Server busy. Connection rejected."
                );
            }

            closeQuietly(client);

        } catch (RuntimeException | Error e) {
            activeTransferSockets.remove(transferId, client);

            closeQuietly(client);
            throw e;
        }
    }

    public boolean cancelTransfer(String transferId) {
        if (transferId == null || transferId.isBlank()) {
            return false;
        }
        Socket socket = activeTransferSockets.remove(transferId);
        if (socket != null) {
            closeQuietly(socket);
            return true;
        }
        return false;
    }

    public boolean isRunning() {
        return running && !closed && !serverSocket.isClosed();
    }

    @Override
    public synchronized void close() {

        if (closed) {
            return;
        }

        closed = true;
        running = false;

        try {
            if (!serverSocket.isClosed()) {
                serverSocket.close();
            }
        } catch (IOException ignored) {
        } finally {
            activeTransferSockets.values().forEach(PeerServer::closeQuietly);
            activeTransferSockets.clear();
            listenerThread.interrupt();
            workers.shutdownNow();
        }

        if (listenerThread.isAlive() && Thread.currentThread() != listenerThread) {
            try {
                listenerThread.join(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static void closeQuietly(Socket socket) {

        if (socket != null && !socket.isClosed()) {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
    }
}
