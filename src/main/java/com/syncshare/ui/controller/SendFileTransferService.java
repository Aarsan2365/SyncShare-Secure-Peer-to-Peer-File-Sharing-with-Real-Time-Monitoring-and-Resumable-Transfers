package com.syncshare.ui.controller;

import com.syncshare.model.TransferItem;
import com.syncshare.model.TransferStatus;
import com.syncshare.service.OutgoingTransferGateway;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyLongProperty;
import javafx.beans.property.ReadOnlyLongWrapper;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.beans.property.ReadOnlyStringProperty;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.concurrent.Service;
import javafx.concurrent.Task;

import java.nio.file.Path;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * JavaFX {@link Service} wrapping blocking outgoing file transfers on background
 * daemon threads while delivering thread-safe progress and lifecycle updates to the UI.
 */
public class SendFileTransferService extends Service<TransferItem> {

    private static final AtomicInteger THREAD_SEQ = new AtomicInteger(1);

    private final ReadOnlyObjectWrapper<TransferStatus> stageStatus =
        new ReadOnlyObjectWrapper<>(this, "stageStatus", TransferStatus.QUEUED);
    private final ReadOnlyStringWrapper stageHeadline =
        new ReadOnlyStringWrapper(this, "stageHeadline", "Idle");
    private final ReadOnlyStringWrapper stageDetail =
        new ReadOnlyStringWrapper(this, "stageDetail", "");
    private final ReadOnlyLongWrapper transferredBytes =
        new ReadOnlyLongWrapper(this, "transferredBytes", 0L);
    private final ReadOnlyLongWrapper totalBytes =
        new ReadOnlyLongWrapper(this, "totalBytes", 0L);

    private final java.util.concurrent.ExecutorService executorService;
    private OutgoingTransferGateway gateway;
    private String peerHost;
    private int port;
    private Path file;
    private volatile String currentTransferId;

    public SendFileTransferService() {
        java.util.concurrent.ExecutorService pool = Executors.newCachedThreadPool(runnable -> {
            Thread thread = new Thread(runnable, "syncshare-send-fx-" + THREAD_SEQ.getAndIncrement());
            thread.setDaemon(true);
            return thread;
        });
        this.executorService = pool;
        setExecutor(pool);
    }

    public void configure(OutgoingTransferGateway gateway, String peerHost, int port, Path file) {
        this.gateway = Objects.requireNonNull(gateway, "gateway");
        this.peerHost = Objects.requireNonNull(peerHost, "peerHost");
        this.port = port;
        this.file = Objects.requireNonNull(file, "file");
        this.currentTransferId = UUID.randomUUID().toString();

        stageStatus.set(TransferStatus.QUEUED);
        stageHeadline.set("Connecting");
        stageDetail.set("Preparing Mutual TLS 1.3 connection to " + peerHost + ":" + port + "...");
        transferredBytes.set(0L);
        long initialTotal = 0L;
        try {
            initialTotal = java.nio.file.Files.size(file);
        } catch (Exception ignored) {
        }
        totalBytes.set(initialTotal);
    }

    public String getCurrentTransferId() {
        return currentTransferId;
    }

    public ReadOnlyObjectProperty<TransferStatus> stageStatusProperty() {
        return stageStatus.getReadOnlyProperty();
    }

    public ReadOnlyStringProperty stageHeadlineProperty() {
        return stageHeadline.getReadOnlyProperty();
    }

    public ReadOnlyStringProperty stageDetailProperty() {
        return stageDetail.getReadOnlyProperty();
    }

    public ReadOnlyLongProperty transferredBytesProperty() {
        return transferredBytes.getReadOnlyProperty();
    }

    public ReadOnlyLongProperty totalBytesProperty() {
        return totalBytes.getReadOnlyProperty();
    }

    @Override
    public boolean cancel() {
        String id = currentTransferId;
        boolean gatewayCancelled = gateway != null && id != null && gateway.cancelActiveTransfer(id, false);
        boolean serviceCancelled = super.cancel();
        return serviceCancelled || gatewayCancelled;
    }

    public boolean cancelCurrentTransfer() {
        return cancel();
    }

    public void shutdown() {
        cancel();
        if (executorService != null && !executorService.isShutdown()) {
            executorService.shutdownNow();
        }
    }

    public void dispose() {
        shutdown();
    }

    @Override
    protected Task<TransferItem> createTask() {
        final OutgoingTransferGateway activeGateway = Objects.requireNonNull(this.gateway, "gateway");
        final String host = Objects.requireNonNull(this.peerHost, "peerHost");
        final int targetPort = this.port;
        final Path targetFile = Objects.requireNonNull(this.file, "file");
        final String transferId = this.currentTransferId != null
            ? this.currentTransferId
            : UUID.randomUUID().toString();
        this.currentTransferId = transferId;

        return new Task<>() {
            @Override
            protected TransferItem call() throws Exception {
                updateMessage("Connecting to " + host + ":" + targetPort + " over Mutual TLS 1.3...");
                updateProgress(-1, 1);

                OutgoingTransferGateway.ProgressObserver observer = new OutgoingTransferGateway.ProgressObserver() {
                    @Override
                    public void onStageChanged(TransferStatus status, String headline, String detail) {
                        if (isCancelled()) {
                            return;
                        }
                        if (detail != null && !detail.isBlank()) {
                            updateMessage(detail);
                        }
                        runOnFxThread(() -> {
                            if (!isCancelled()) {
                                stageStatus.set(status);
                                stageHeadline.set(headline != null ? headline : status.displayName());
                                stageDetail.set(detail != null ? detail : "");
                            }
                        });
                    }

                    @Override
                    public void onProgress(String fileName, long transferred, long total) {
                        if (isCancelled()) {
                            return;
                        }
                        if (total > 0) {
                            updateProgress(transferred, total);
                        } else {
                            updateProgress(-1, 1);
                        }
                        runOnFxThread(() -> {
                            if (!isCancelled()) {
                                transferredBytes.set(transferred);
                                totalBytes.set(total);
                            }
                        });
                    }
                };

                TransferItem result = activeGateway.sendFileBlocking(
                    transferId,
                    host,
                    targetPort,
                    targetFile,
                    observer,
                    this::isCancelled
                );

                if (!isCancelled() && result != null && result.status() == TransferStatus.COMPLETED) {
                    long total = result.totalBytes() > 0 ? result.totalBytes() : 1L;
                    updateProgress(total, total);
                }
                return result;
            }
        };
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
