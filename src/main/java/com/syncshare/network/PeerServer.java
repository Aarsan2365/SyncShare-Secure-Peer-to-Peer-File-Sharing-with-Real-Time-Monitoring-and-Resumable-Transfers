package com.syncshare.network;

import com.syncshare.approval.ApprovalHandler;
import com.syncshare.events.ConsoleTransferListener;
import com.syncshare.events.IncomingTransferObserver;
import com.syncshare.events.TransferListener;
import com.syncshare.events.TransferSessionListener;
import com.syncshare.model.TransferDirection;
import com.syncshare.security.TlsSupport;
import com.syncshare.transfer.FileReceiver;

import javax.net.ssl.SSLContext;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class PeerServer implements AutoCloseable {

    private static final AtomicInteger WORKER_SEQ = new AtomicInteger(1);

    private final ServerSocket serverSocket;
    private final Thread listenerThread;
    private final ThreadPoolExecutor workers;
    private final ApprovalHandler approvalHandler;
    private final TransferListener transferListener;
    private final Path receiveDirectory;
    private final IncomingTransferObserver observer;
    private final TransferSessionListener sessionListener;
    private final ConcurrentHashMap<String, Socket> activeTransferSockets = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Thread> activeWorkerThreads = new ConcurrentHashMap<>();

    private volatile boolean running = false;
    private volatile boolean closed = false;

    public PeerServer(
            int port,
            ApprovalHandler approvalHandler
    ) throws IOException {
        this(port, approvalHandler, new ConsoleTransferListener(), null, null, null, null);
    }

    public PeerServer(
            int port,
            ApprovalHandler approvalHandler,
            TransferSessionListener sessionListener
    ) throws IOException {
        this(port, approvalHandler, new ConsoleTransferListener(), null, null, null, sessionListener);
    }

    public PeerServer(
            int port,
            ApprovalHandler approvalHandler,
            TransferListener transferListener,
            Path receiveDirectory,
            IncomingTransferObserver observer
    ) throws IOException {
        this(port, approvalHandler, transferListener, receiveDirectory, observer, null, null);
    }

    public PeerServer(
            int port,
            ApprovalHandler approvalHandler,
            TransferListener transferListener,
            Path receiveDirectory,
            IncomingTransferObserver observer,
            SSLContext sslContext
    ) throws IOException {
        this(port, approvalHandler, transferListener, receiveDirectory, observer, sslContext, null);
    }

    public PeerServer(
            int port,
            ApprovalHandler approvalHandler,
            TransferListener transferListener,
            Path receiveDirectory,
            IncomingTransferObserver observer,
            SSLContext sslContext,
            TransferSessionListener sessionListener
    ) throws IOException {

        this.approvalHandler = approvalHandler;
        this.transferListener = transferListener != null ? transferListener : new ConsoleTransferListener();
        this.receiveDirectory = receiveDirectory;
        this.observer = observer != null ? observer : IncomingTransferObserver.NO_OP;
        this.sessionListener = sessionListener != null ? sessionListener : TransferSessionListener.NO_OP;

        this.workers = new ThreadPoolExecutor(
            4,
            4,
            0L,
            TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(8),
            runnable -> {
                Thread thread = new Thread(
                    runnable,
                    "syncshare-receiver-worker-" + WORKER_SEQ.getAndIncrement()
                );
                thread.setDaemon(true);
                return thread;
            },
            new ThreadPoolExecutor.AbortPolicy()
        );

        this.listenerThread = new Thread(
            this::listen,
            "syncshare-listener"
        );

        this.listenerThread.setDaemon(true);

        try {
            this.serverSocket = sslContext != null
                ? TlsSupport.listen(port, sslContext)
                : TlsSupport.listen(port);
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
        String senderEndpoint = client.getInetAddress() != null
            ? client.getInetAddress().getHostAddress() + ":" + client.getPort()
            : "unknown";
        activeTransferSockets.put(transferId, client);

        try {
            workers.execute(() -> {
                Thread currentThread = Thread.currentThread();
                Thread.interrupted();
                activeWorkerThreads.put(transferId, currentThread);

                try (client) {

                    client.setSoTimeout(180_000);

                    System.out.println(
                        "\nConnected: "
                        + client.getInetAddress()
                    );

                    FileReceiver.receive(
                        client,
                        transferListener,
                        approvalHandler,
                        receiveDirectory,
                        observer,
                        transferId,
                        sessionListener
                    );

                } catch (Exception e) {
                    System.err.println(
                        "Incoming transfer failed: "
                        + e.getMessage()
                    );
                } finally {
                    activeWorkerThreads.remove(transferId, currentThread);
                    activeTransferSockets.remove(transferId, client);
                    Thread.interrupted();
                }
            });

        } catch (RejectedExecutionException e) {

            activeTransferSockets.remove(transferId, client);
            if (!closed && !workers.isShutdown()) {
                System.out.println(
                    "Server busy. Connection rejected."
                );
                IOException rejected = new IOException("Receiver busy; connection rejected", e);
                try {
                    observer.onError(transferId, "unknown", senderEndpoint, rejected);
                } catch (RuntimeException ignored) {
                }
                try {
                    sessionListener.onFailed(
                        transferId,
                        TransferDirection.INCOMING,
                        "unknown",
                        "Receiver busy; connection rejected",
                        rejected
                    );
                } catch (RuntimeException ignored) {
                }
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
        Thread worker = activeWorkerThreads.remove(transferId);
        if (worker != null) {
            worker.interrupt();
        }
        if (socket != null) {
            closeQuietly(socket);
            return true;
        }
        return worker != null;
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
            activeWorkerThreads.clear();
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
        try {
            workers.awaitTermination(1000, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
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
