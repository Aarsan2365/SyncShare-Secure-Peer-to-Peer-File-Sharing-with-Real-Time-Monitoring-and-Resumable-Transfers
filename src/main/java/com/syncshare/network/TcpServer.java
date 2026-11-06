
package com.syncshare.network;

import com.syncshare.transfer.FileReceiver;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public class TcpServer {

    private static final int MAX_WORKERS = 4;
    private static final int QUEUE_CAPACITY = 8;

    public static void start(int port) throws IOException {

        ThreadPoolExecutor workers = new ThreadPoolExecutor(
            MAX_WORKERS,
            MAX_WORKERS,
            0L,
            TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(QUEUE_CAPACITY),
            new ThreadPoolExecutor.AbortPolicy()
        );

        try (ServerSocket server = new ServerSocket(port)) {

            System.out.println("SyncShare Receiver Started!");
            System.out.println("Listening on port: " + port);
            System.out.println("Maximum workers: " + MAX_WORKERS);

            while (!Thread.currentThread().isInterrupted()) {

                Socket client = server.accept();

                try {
                    workers.execute(() -> {

                        try (client) {

                            System.out.println(
                                "Worker: " +
                                Thread.currentThread().getName()
                            );

                            System.out.println(
                                "Connected: " +
                                client.getInetAddress()
                            );

                            FileReceiver.receive(client);

                        } catch (Exception e) {

                            System.out.println(
                                "Transfer failed: " + e.getMessage()
                            );
                        }
                    });

                } catch (RejectedExecutionException e) {

                    System.out.println(
                        "Server busy. Connection rejected."
                    );

                    client.close();
                }
            }

        } finally {
            workers.shutdownNow();
        }
    }
}
