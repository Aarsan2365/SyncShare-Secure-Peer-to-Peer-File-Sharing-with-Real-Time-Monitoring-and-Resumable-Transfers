
package com.syncshare.network;

import com.syncshare.approval.ApprovalHandler;
import com.syncshare.events.ConsoleTransferListener;
import com.syncshare.transfer.FileReceiver;

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

    public PeerServer(
            int port,
            ApprovalHandler approvalHandler
    ) throws IOException {

        this.serverSocket = new ServerSocket(port);
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
    }

    public void start() {

        if (running) {
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

        while (running) {

            try {
                Socket client = serverSocket.accept();

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

                    System.out.println(
                        "Server busy. Connection rejected."
                    );

                    client.close();
                }

            } catch (IOException e) {

                if (running) {
                    System.err.println(
                        "Listener error: " + e.getMessage()
                    );
                }
            }
        }
    }

    public boolean isRunning() {
        return running && !serverSocket.isClosed();
    }

    @Override
    public void close() {

        running = false;

        try {
            serverSocket.close();
        } catch (IOException ignored) {
        }

        workers.shutdownNow();
    }
}
