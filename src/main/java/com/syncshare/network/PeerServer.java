
package com.syncshare.network;

import com.syncshare.approval.ApprovalHandler;
import com.syncshare.events.ConsoleTransferListener;
import com.syncshare.transfer.FileReceiver;
import com.syncshare.security.TlsSupport;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.*;

public class PeerServer implements AutoCloseable {

    private final ServerSocket serverSocket;
    private final Thread listenerThread;
    private final ThreadPoolExecutor workers;
    private final ApprovalHandler approvalHandler;

    private volatile boolean running = false;
    private volatile boolean closed = false;

    public PeerServer(
            int port,
            ApprovalHandler approvalHandler
    ) throws IOException {

        this.approvalHandler = approvalHandler;

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
                        approvalHandler
                    );

                } catch (Exception e) {
                    System.err.println(
                        "Incoming transfer failed: "
                        + e.getMessage()
                    );
                }
            });

        } catch (RejectedExecutionException e) {

            if (!closed && !workers.isShutdown()) {
                System.out.println(
                    "Server busy. Connection rejected."
                );
            }

            closeQuietly(client);

        } catch (RuntimeException | Error e) {

            closeQuietly(client);
            throw e;
        }
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
