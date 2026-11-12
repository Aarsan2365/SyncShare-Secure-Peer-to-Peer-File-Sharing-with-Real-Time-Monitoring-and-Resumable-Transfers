package com.syncshare.service;

import com.syncshare.events.TransferListener;
import com.syncshare.events.TransferSessionListener;
import com.syncshare.model.TransferDirection;
import com.syncshare.model.TransferItem;
import com.syncshare.model.TransferStatus;
import com.syncshare.network.TcpClient;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyDoubleProperty;
import javafx.beans.property.ReadOnlyDoubleWrapper;
import javafx.beans.property.ReadOnlyIntegerProperty;
import javafx.beans.property.ReadOnlyIntegerWrapper;
import javafx.beans.property.ReadOnlyLongProperty;
import javafx.beans.property.ReadOnlyLongWrapper;
import javafx.collections.FXCollections;
import javafx.collections.ListChangeListener;
import javafx.collections.ObservableList;

import java.io.IOException;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

/**
 * Coordinates outgoing and incoming transfer orchestration, progress tracking,
 * and transfer history state without exposing socket/TCP code to UI controllers.
 */
public class TransferService {

    @FunctionalInterface
    public interface SendTransport {
        void send(String host, int port, Path file, TransferListener listener) throws Exception;
    }

    public record ValidationResult(boolean valid, String peerMessage, String portMessage, String fileMessage, int port, Path file) {
    }

    private static final Pattern HOST_OR_IP = Pattern.compile(
        "^((25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)(\\.(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)){3}|[a-zA-Z0-9.-]{2,})$"
    );

    private final SendTransport sendTransport;
    private final TransferMonitoringService monitoringService;
    private final ExecutorService executor;
    private final ConcurrentHashMap<String, Future<?>> runningTasks = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Socket> activeSockets = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, TransferItem> activeSnapshots = new ConcurrentHashMap<>();

    private final ObservableList<TransferItem> activeTransfers = FXCollections.observableArrayList();
    private final ObservableList<TransferItem> unmodifiableActiveTransfers =
        FXCollections.unmodifiableObservableList(activeTransfers);

    private final ObservableList<TransferItem> transferHistory = FXCollections.observableArrayList();
    private final ObservableList<TransferItem> unmodifiableTransferHistory =
        FXCollections.unmodifiableObservableList(transferHistory);

    private final ReadOnlyIntegerWrapper activeCount = new ReadOnlyIntegerWrapper(this, "activeCount", 0);
    private final ReadOnlyIntegerWrapper completedCount = new ReadOnlyIntegerWrapper(this, "completedCount", 0);
    private final ReadOnlyIntegerWrapper failedCount = new ReadOnlyIntegerWrapper(this, "failedCount", 0);
    private final ReadOnlyLongWrapper totalBytesTransferred = new ReadOnlyLongWrapper(this, "totalBytesTransferred", 0L);
    private final ReadOnlyDoubleWrapper averageActiveProgress = new ReadOnlyDoubleWrapper(this, "averageActiveProgress", 0.0);

    public TransferService() {
        this(new TransferMonitoringService());
    }

    public TransferService(TransferMonitoringService monitoringService) {
        this.sendTransport = null;
        this.monitoringService = Objects.requireNonNull(monitoringService, "monitoringService");
        this.executor = createWorkerExecutor();
        bindMetricListeners();
    }

    public TransferService(SendTransport sendTransport) {
        this(sendTransport, new TransferMonitoringService());
    }

    public TransferService(SendTransport sendTransport, TransferMonitoringService monitoringService) {
        this.sendTransport = Objects.requireNonNull(sendTransport, "sendTransport");
        this.monitoringService = Objects.requireNonNull(monitoringService, "monitoringService");
        this.executor = createWorkerExecutor();
        bindMetricListeners();
    }

    private static ExecutorService createWorkerExecutor() {
        return Executors.newFixedThreadPool(3, runnable -> {
            Thread thread = new Thread(runnable, "syncshare-transfer-worker");
            thread.setDaemon(true);
            return thread;
        });
    }

    private void bindMetricListeners() {
        activeTransfers.addListener((ListChangeListener<TransferItem>) change -> recalculateMetrics());
        transferHistory.addListener((ListChangeListener<TransferItem>) change -> recalculateMetrics());
    }

    public TransferMonitoringService getMonitoringService() {
        return monitoringService;
    }

    public ObservableList<TransferItem> getActiveTransfers() {
        return unmodifiableActiveTransfers;
    }

    public ObservableList<TransferItem> getTransferHistory() {
        return unmodifiableTransferHistory;
    }

    public ReadOnlyIntegerProperty activeCountProperty() {
        return activeCount.getReadOnlyProperty();
    }

    public ReadOnlyIntegerProperty completedCountProperty() {
        return completedCount.getReadOnlyProperty();
    }

    public ReadOnlyIntegerProperty failedCountProperty() {
        return failedCount.getReadOnlyProperty();
    }

    public ReadOnlyLongProperty totalBytesTransferredProperty() {
        return totalBytesTransferred.getReadOnlyProperty();
    }

    public ReadOnlyDoubleProperty averageActiveProgressProperty() {
        return averageActiveProgress.getReadOnlyProperty();
    }

    public ValidationResult validateSendInput(String peerHost, String portText, String filePath) {
        String safeHost = peerHost != null ? peerHost.trim() : "";
        String safePort = portText != null ? portText.trim() : "";
        String safeFile = filePath != null ? filePath.trim() : "";

        String peerMsg = null;
        if (safeHost.isEmpty() || !HOST_OR_IP.matcher(safeHost).matches()) {
            peerMsg = "Enter a valid peer IPv4 address or hostname.";
        }

        int parsedPort = -1;
        String portMsg = null;
        try {
            parsedPort = Integer.parseInt(safePort);
            if (parsedPort < 1 || parsedPort > 65535) {
                portMsg = "Port must be between 1 and 65535.";
            }
        } catch (NumberFormatException e) {
            portMsg = "Enter a valid numeric TCP port.";
        }

        String fileMsg = null;
        Path resolvedFile = null;
        if (safeFile.isEmpty()) {
            fileMsg = "Select a local file to send.";
        } else {
            try {
                resolvedFile = Path.of(safeFile);
                if (!Files.isRegularFile(resolvedFile)) {
                    fileMsg = "File does not exist or is not a regular file.";
                }
            } catch (Exception e) {
                fileMsg = "File path is invalid.";
            }
        }

        boolean valid = peerMsg == null && portMsg == null && fileMsg == null;
        return new ValidationResult(valid, peerMsg, portMsg, fileMsg, parsedPort, resolvedFile);
    }

    public CompletableFuture<TransferItem> sendFile(String peerHost, int port, Path file) {
        Objects.requireNonNull(peerHost, "peerHost");
        Objects.requireNonNull(file, "file");

        String transferId = UUID.randomUUID().toString();
        String fileName = file.getFileName() != null ? file.getFileName().toString() : file.toString();
        String peerEndpoint = peerHost + ":" + port;
        long initialSize = 0L;
        try {
            if (Files.isRegularFile(file)) {
                initialSize = Files.size(file);
            }
        } catch (Exception ignored) {
        }

        TransferItem queued = new TransferItem(
            transferId,
            fileName,
            initialSize,
            0L,
            TransferDirection.OUTGOING,
            TransferStatus.QUEUED,
            peerEndpoint,
            "Pending SHA-256",
            Instant.now(),
            "Queued for mTLS 1.3 handshake"
        );
        upsertActiveTransferLocal(queued);
        monitoringService.registerQueued(
            transferId,
            TransferDirection.OUTGOING,
            fileName,
            initialSize,
            peerEndpoint,
            "Queued for mTLS 1.3 handshake"
        );

        AtomicBoolean cancelledFlag = new AtomicBoolean(false);
        CompletableFuture<TransferItem> completion = new CompletableFuture<>();

        monitoringService.registerCancelHandler(transferId, () -> {
            if (cancelledFlag.compareAndSet(false, true)) {
                abortOutgoingExecution(transferId, completion);
            }
        });

        TransferSessionListener sessionBridge = new TransferSessionListener() {
            @Override
            public void onConnecting(
                String id,
                TransferDirection direction,
                String name,
                long totalBytes,
                String endpoint,
                String statusNote
            ) {
                monitoringService.onConnecting(id, direction, name, totalBytes, endpoint, statusNote);
                if (!completion.isDone()) {
                    upsertActiveTransferLocal(new TransferItem(
                        transferId,
                        name,
                        Math.max(0L, totalBytes),
                        0L,
                        TransferDirection.OUTGOING,
                        TransferStatus.CONNECTING,
                        peerEndpoint,
                        "Pending SHA-256",
                        Instant.now(),
                        statusNote
                    ));
                }
            }

            @Override
            public void onStarted(
                String id,
                TransferDirection direction,
                String name,
                long totalBytes,
                long initialBytes,
                String endpoint
            ) {
                monitoringService.onStarted(id, direction, name, totalBytes, initialBytes, endpoint);
                if (!completion.isDone()) {
                    upsertActiveTransferLocal(new TransferItem(
                        transferId,
                        name,
                        Math.max(0L, totalBytes),
                        Math.max(0L, initialBytes),
                        TransferDirection.OUTGOING,
                        TransferStatus.TRANSFERRING,
                        peerEndpoint,
                        "SHA-256 active",
                        Instant.now(),
                        "Transfer started"
                    ));
                }
            }

            @Override
            public void onProgress(
                String id,
                TransferDirection direction,
                String name,
                long transferredBytes,
                long totalBytes
            ) {
                monitoringService.onProgress(id, direction, name, transferredBytes, totalBytes);
                if (!completion.isDone()) {
                    upsertActiveTransferLocal(new TransferItem(
                        transferId,
                        name,
                        Math.max(0L, totalBytes),
                        Math.max(0L, transferredBytes),
                        TransferDirection.OUTGOING,
                        TransferStatus.TRANSFERRING,
                        peerEndpoint,
                        "SHA-256 active",
                        Instant.now(),
                        "Streaming encrypted chunks"
                    ));
                }
            }

            @Override
            public void onCompleted(String id, TransferDirection direction, String name) {
                monitoringService.onCompleted(id, direction, name);
                if (!completion.isDone()) {
                    TransferItem current = activeSnapshots.get(transferId);
                    long size = current != null ? current.totalBytes() : 0L;
                    TransferItem finished = new TransferItem(
                        transferId,
                        name,
                        size,
                        size,
                        TransferDirection.OUTGOING,
                        TransferStatus.COMPLETED,
                        peerEndpoint,
                        "SHA-256 verified",
                        Instant.now(),
                        "Transfer completed and verified"
                    );
                    moveToHistoryLocal(finished);
                    completion.complete(finished);
                }
            }

            @Override
            public void onFailed(
                String id,
                TransferDirection direction,
                String name,
                String reason,
                Exception error
            ) {
                if (cancelledFlag.get()) {
                    onCancelled(id, direction, name, "Cancelled by user");
                    return;
                }
                monitoringService.onFailed(id, direction, name, reason, error);
                if (!completion.isDone()) {
                    TransferItem current = activeSnapshots.get(transferId);
                    long size = current != null ? current.totalBytes() : 0L;
                    long done = current != null ? current.transferredBytes() : 0L;
                    String msg = (reason != null && !reason.isBlank()) ? reason : "Transfer failed";
                    TransferItem failed = new TransferItem(
                        transferId,
                        name,
                        size,
                        done,
                        TransferDirection.OUTGOING,
                        TransferStatus.FAILED,
                        peerEndpoint,
                        "Interrupted",
                        Instant.now(),
                        msg
                    );
                    moveToHistoryLocal(failed);
                    completion.complete(failed);
                }
            }

            @Override
            public void onCancelled(String id, TransferDirection direction, String name, String reason) {
                monitoringService.onCancelled(id, direction, name, reason);
                if (!completion.isDone()) {
                    TransferItem current = activeSnapshots.get(transferId);
                    long size = current != null ? current.totalBytes() : 0L;
                    long done = current != null ? current.transferredBytes() : 0L;
                    TransferItem cancelled = new TransferItem(
                        transferId,
                        name,
                        size,
                        done,
                        TransferDirection.OUTGOING,
                        TransferStatus.CANCELLED,
                        peerEndpoint,
                        "Cancelled",
                        Instant.now(),
                        reason != null && !reason.isBlank() ? reason : "Cancelled by user"
                    );
                    moveToHistoryLocal(cancelled);
                    completion.complete(cancelled);
                }
            }
        };

        TransferListener legacyListener = new TransferListener() {
            @Override
            public void onStarted(String name, long totalBytes) {
                sessionBridge.onStarted(transferId, TransferDirection.OUTGOING, name, totalBytes, 0L, peerEndpoint);
            }

            @Override
            public void onProgress(String name, long transferredBytes, long totalBytes) {
                sessionBridge.onProgress(transferId, TransferDirection.OUTGOING, name, transferredBytes, totalBytes);
            }

            @Override
            public void onCompleted(String name) {
                sessionBridge.onCompleted(transferId, TransferDirection.OUTGOING, name);
            }

            @Override
            public void onError(String name, Exception error) {
                String msg = error != null && error.getMessage() != null ? error.getMessage() : "Transfer failed";
                sessionBridge.onFailed(transferId, TransferDirection.OUTGOING, name, msg, error);
            }
        };

        Future<?> task = executor.submit(() -> {
            try {
                if (cancelledFlag.get() || Thread.currentThread().isInterrupted()) {
                    sessionBridge.onCancelled(transferId, TransferDirection.OUTGOING, fileName, "Cancelled by user");
                    return;
                }
                if (sendTransport != null) {
                    sendTransport.send(peerHost, port, file, legacyListener);
                    if (!completion.isDone()) {
                        sessionBridge.onCompleted(transferId, TransferDirection.OUTGOING, fileName);
                    }
                } else {
                    TcpClient.send(
                        peerHost,
                        port,
                        file,
                        transferId,
                        sessionBridge,
                        socket -> {
                            if (socket != null) {
                                activeSockets.put(transferId, socket);
                                if (cancelledFlag.get()) {
                                    closeQuietly(socket);
                                }
                            } else {
                                activeSockets.remove(transferId);
                            }
                        }
                    );
                }
            } catch (Exception error) {
                if (!completion.isDone()) {
                    if (cancelledFlag.get() || Thread.currentThread().isInterrupted()) {
                        sessionBridge.onCancelled(transferId, TransferDirection.OUTGOING, fileName, "Cancelled by user");
                    } else {
                        String msg = error.getMessage() != null ? error.getMessage() : error.getClass().getSimpleName();
                        sessionBridge.onFailed(transferId, TransferDirection.OUTGOING, fileName, msg, error);
                    }
                }
            } finally {
                runningTasks.remove(transferId);
                activeSockets.remove(transferId);
            }
        });
        runningTasks.put(transferId, task);
        return completion;
    }

    public boolean cancelActiveTransfer(String transferId) {
        if (transferId == null || transferId.isBlank()) {
            return false;
        }
        boolean cancelledInMonitor = monitoringService.cancelTransfer(transferId);
        boolean cancelledTask = abortOutgoingExecution(transferId, null);
        TransferItem existing = activeSnapshots.get(transferId);
        if (existing != null) {
            TransferItem cancelled = new TransferItem(
                existing.id(),
                existing.fileName(),
                existing.totalBytes(),
                existing.transferredBytes(),
                existing.direction(),
                TransferStatus.CANCELLED,
                existing.peerAddress(),
                existing.sha256Preview(),
                Instant.now(),
                "Cancelled by user"
            );
            moveToHistory(cancelled);
            return true;
        }
        return cancelledInMonitor || cancelledTask;
    }

    private boolean abortOutgoingExecution(String transferId, CompletableFuture<TransferItem> completion) {
        Socket socket = activeSockets.remove(transferId);
        if (socket != null) {
            closeQuietly(socket);
        }
        Future<?> task = runningTasks.remove(transferId);
        boolean cancelled = false;
        if (task != null) {
            cancelled = task.cancel(true);
        }
        if (completion != null && !completion.isDone()) {
            TransferItem existing = activeSnapshots.get(transferId);
            if (existing != null) {
                TransferItem item = new TransferItem(
                    existing.id(),
                    existing.fileName(),
                    existing.totalBytes(),
                    existing.transferredBytes(),
                    existing.direction(),
                    TransferStatus.CANCELLED,
                    existing.peerAddress(),
                    "Cancelled",
                    Instant.now(),
                    "Cancelled by user"
                );
                moveToHistory(item);
                completion.complete(item);
            }
        }
        return cancelled || socket != null;
    }

    public void upsertActiveTransfer(TransferItem item) {
        upsertActiveTransferLocal(item);
        monitoringService.syncTransferItem(item);
    }

    public void moveToHistory(TransferItem finishedItem) {
        moveToHistoryLocal(finishedItem);
        monitoringService.syncTransferItem(finishedItem);
    }

    private void upsertActiveTransferLocal(TransferItem item) {
        Objects.requireNonNull(item, "item");
        activeSnapshots.put(item.id(), item);
        runOnFxThread(() -> {
            for (int i = 0; i < activeTransfers.size(); i++) {
                if (activeTransfers.get(i).id().equals(item.id())) {
                    activeTransfers.set(i, item);
                    return;
                }
            }
            activeTransfers.add(0, item);
        });
    }

    private void moveToHistoryLocal(TransferItem finishedItem) {
        Objects.requireNonNull(finishedItem, "finishedItem");
        activeSnapshots.remove(finishedItem.id());
        runOnFxThread(() -> {
            activeTransfers.removeIf(item -> item.id().equals(finishedItem.id()));
            transferHistory.removeIf(item -> item.id().equals(finishedItem.id()));
            transferHistory.add(0, finishedItem);
        });
    }

    public void clearHistory() {
        runOnFxThread(transferHistory::clear);
    }

    private void recalculateMetrics() {
        int active = activeTransfers.size();
        int completed = 0;
        int failed = 0;
        long bytes = 0L;

        for (TransferItem item : activeTransfers) {
            bytes += item.transferredBytes();
        }
        for (TransferItem item : transferHistory) {
            if (item.status() == TransferStatus.COMPLETED) {
                completed++;
                bytes += item.totalBytes();
            } else if (item.status() == TransferStatus.FAILED
                    || item.status() == TransferStatus.REJECTED
                    || item.status() == TransferStatus.CANCELLED) {
                failed++;
                bytes += item.transferredBytes();
            }
        }

        double avg = 0.0;
        if (active > 0) {
            double sum = 0.0;
            int counted = 0;
            for (TransferItem item : activeTransfers) {
                double p = item.progress();
                if (p >= 0.0) {
                    sum += p;
                    counted++;
                }
            }
            avg = counted > 0 ? sum / counted : 0.0;
        }

        activeCount.set(active);
        completedCount.set(completed);
        failedCount.set(failed);
        totalBytesTransferred.set(bytes);
        averageActiveProgress.set(avg);
    }

    public void shutdown() {
        activeSockets.values().forEach(TransferService::closeQuietly);
        activeSockets.clear();
        runningTasks.values().forEach(task -> task.cancel(true));
        runningTasks.clear();
        executor.shutdownNow();
        monitoringService.shutdown();
    }

    private static void closeQuietly(Socket socket) {
        if (socket != null && !socket.isClosed()) {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
    }

    private static void runOnFxThread(Runnable task) {
        if (Platform.isFxApplicationThread()) {
            task.run();
        } else {
            try {
                Platform.runLater(task);
            } catch (IllegalStateException toolkitNotStarted) {
                task.run();
            }
        }
    }
}
