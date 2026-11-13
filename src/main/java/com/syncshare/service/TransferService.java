package com.syncshare.service;

import com.syncshare.events.TransferListener;
import com.syncshare.events.TransferSessionListener;
import com.syncshare.model.TransferDirection;
import com.syncshare.model.TransferItem;
import com.syncshare.model.TransferStatus;
import com.syncshare.network.Protocol;
import com.syncshare.network.TcpClient;
import com.syncshare.reliability.CentralErrorHandler;
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

import javax.net.ssl.SSLException;
import javax.net.ssl.SSLHandshakeException;
import java.io.EOFException;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.net.ConnectException;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.regex.Pattern;

/**
 * Coordinates outgoing and incoming transfer orchestration, real-time monitoring
 * integration, progress tracking, and transfer history state without exposing
 * socket/TCP code to UI controllers.
 */
public class TransferService implements OutgoingTransferGateway {

    @FunctionalInterface
    public interface SendTransport {
        void send(String host, int port, Path file, TransferListener listener) throws Exception;
    }

    public record ValidationResult(
        boolean valid,
        String peerMessage,
        String portMessage,
        String fileMessage,
        int port,
        Path file
    ) {
    }

    public record FileSummary(
        boolean valid,
        String fileName,
        long sizeBytes,
        String formattedSize,
        String statusLabel,
        String detailMessage,
        Path resolvedPath
    ) {
        public static FileSummary empty() {
            return new FileSummary(
                false,
                "No file selected",
                0L,
                "--",
                "No File",
                "Select a local file using Browse... or enter a file path.",
                null
            );
        }
    }

    private static final Pattern IPV4_PATTERN = Pattern.compile(
        "^(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)(\\.(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)){3}$"
    );
    private static final Pattern NUMERIC_DOT_ATTEMPT = Pattern.compile("^[0-9.]+$");
    private static final Pattern HOSTNAME_LABEL = Pattern.compile(
        "^[a-zA-Z0-9]([a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?$"
    );
    private static final Pattern CONTAINS_ASCII_LETTER = Pattern.compile(".*[a-zA-Z].*");
    private static final long PROGRESS_UI_INTERVAL_NANOS = 40_000_000L;

    private static final ProgressObserver NO_OP_OBSERVER = new ProgressObserver() {
        @Override
        public void onStageChanged(TransferStatus status, String headline, String detail) {
        }

        @Override
        public void onProgress(String fileName, long transferredBytes, long totalBytes) {
        }
    };

    private static final class ActiveTransferContext {
        final String transferId;
        final String peerAddress;
        final Path normalizedFile;
        final String fileName;
        final long initialSize;
        final CompletableFuture<TransferItem> completion;
        final AtomicBoolean cancelled = new AtomicBoolean(false);
        final AtomicBoolean terminalHandled = new AtomicBoolean(false);
        final AtomicBoolean backendCompletedCalled = new AtomicBoolean(false);
        final AtomicBoolean backendErrorCalled = new AtomicBoolean(false);
        final AtomicLong lastUiProgressNanos = new AtomicLong(0L);
        final AtomicReference<TransferItem> latestItem;
        final AtomicReference<Thread> workerThread = new AtomicReference<>();
        volatile Future<?> future;

        ActiveTransferContext(
            String transferId,
            String peerAddress,
            Path normalizedFile,
            String fileName,
            long initialSize,
            TransferItem initialItem,
            CompletableFuture<TransferItem> completion
        ) {
            this.transferId = transferId;
            this.peerAddress = peerAddress;
            this.normalizedFile = normalizedFile;
            this.fileName = fileName;
            this.initialSize = initialSize;
            this.latestItem = new AtomicReference<>(initialItem);
            this.completion = completion;
        }

        void interruptExecution() {
            Future<?> runningFuture = this.future;
            if (runningFuture != null) {
                runningFuture.cancel(true);
            }
            Thread worker = workerThread.get();
            if (worker != null) {
                worker.interrupt();
            }
        }
    }

    private final SendTransport sendTransport;
    private final TransferMonitoringService monitoringService;
    private final ExecutorService executor;
    private final ConcurrentHashMap<String, ActiveTransferContext> runningTasks = new ConcurrentHashMap<>();
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

    @Override
    public ValidationResult validateSendInput(String peerHost, String portText, String filePath) {
        String safeHost = peerHost != null ? peerHost.trim() : "";
        String safePort = portText != null ? portText.trim() : "";

        String peerMsg = null;
        if (!isValidHostOrIp(safeHost)) {
            peerMsg = "Enter a valid peer IPv4 address (e.g. 192.168.1.42) or hostname.";
        }

        int parsedPort = -1;
        String portMsg = null;
        if (safePort.isEmpty()) {
            portMsg = "Enter a destination TCP port (1 - 65535).";
        } else {
            try {
                parsedPort = Integer.parseInt(safePort);
                if (parsedPort < 1 || parsedPort > 65535) {
                    portMsg = "Port must be between 1 and 65535.";
                }
            } catch (NumberFormatException e) {
                portMsg = "Enter a valid numeric TCP port (1 - 65535).";
            }
        }

        FileSummary fileSummary = inspectSelectedFile(filePath);
        String fileMsg = fileSummary.valid() ? null : fileSummary.detailMessage();
        Path resolvedFile = fileSummary.valid() ? fileSummary.resolvedPath() : null;

        boolean valid = peerMsg == null && portMsg == null && fileMsg == null;
        return new ValidationResult(valid, peerMsg, portMsg, fileMsg, parsedPort, resolvedFile);
    }

    @Override
    public FileSummary inspectSelectedFile(String filePath) {
        String safeFile = filePath != null ? filePath.trim() : "";
        if (safeFile.isEmpty()) {
            return FileSummary.empty();
        }

        Path resolved;
        try {
            resolved = Path.of(safeFile);
        } catch (InvalidPathException e) {
            return new FileSummary(
                false,
                "Invalid path",
                0L,
                "--",
                "Invalid Path",
                "File path is invalid.",
                null
            );
        }

        String displayName = resolved.getFileName() != null ? resolved.getFileName().toString() : safeFile;

        if (!Files.exists(resolved)) {
            return new FileSummary(
                false,
                displayName,
                0L,
                "--",
                "Missing",
                "File does not exist or could not be found.",
                resolved
            );
        }

        if (Files.isDirectory(resolved) || !Files.isRegularFile(resolved)) {
            return new FileSummary(
                false,
                displayName,
                0L,
                "--",
                "Not a File",
                "Selected path is a directory or is not a regular file.",
                resolved
            );
        }

        if (!Files.isReadable(resolved)) {
            return new FileSummary(
                false,
                displayName,
                0L,
                "--",
                "Unreadable",
                "Selected file is not readable.",
                resolved
            );
        }

        try (InputStream in = Files.newInputStream(resolved)) {
            in.readNBytes(1);
        } catch (AccessDeniedException e) {
            return new FileSummary(
                false,
                displayName,
                0L,
                "--",
                "Permission Denied",
                "Permission denied accessing selected file.",
                resolved
            );
        } catch (IOException e) {
            return new FileSummary(
                false,
                displayName,
                0L,
                "--",
                "Unreadable",
                "Selected file cannot be read: " + e.getMessage(),
                resolved
            );
        }

        long sizeBytes;
        try {
            sizeBytes = Files.size(resolved);
        } catch (IOException e) {
            return new FileSummary(
                false,
                displayName,
                0L,
                "--",
                "Unreadable",
                "Unable to determine file size: " + e.getMessage(),
                resolved
            );
        }

        String readableSize = TransferItem.formatByteCount(sizeBytes);
        if (sizeBytes > Protocol.MAX_FILE_SIZE) {
            return new FileSummary(
                false,
                displayName,
                sizeBytes,
                readableSize,
                "Too Large",
                "File exceeds the maximum " + TransferItem.formatByteCount(Protocol.MAX_FILE_SIZE) + " protocol limit.",
                resolved
            );
        }

        return new FileSummary(
            true,
            displayName,
            sizeBytes,
            readableSize,
            "Ready",
            "Regular local file ready for single-file mTLS transfer.",
            resolved
        );
    }

    public static boolean isValidHostOrIp(String host) {
        if (host == null || host.isBlank() || host.length() > 253) {
            return false;
        }
        String trimmed = host.trim();
        if (IPV4_PATTERN.matcher(trimmed).matches()) {
            return true;
        }
        if (NUMERIC_DOT_ATTEMPT.matcher(trimmed).matches()) {
            return false;
        }
        if (trimmed.startsWith(".") || trimmed.endsWith(".")) {
            return false;
        }
        String[] labels = trimmed.split("\\.", -1);
        for (String label : labels) {
            if (!HOSTNAME_LABEL.matcher(label).matches()) {
                return false;
            }
        }
        String lastLabel = labels[labels.length - 1];
        return CONTAINS_ASCII_LETTER.matcher(lastLabel).matches();
    }

    public CompletableFuture<TransferItem> sendFile(String peerHost, int port, Path file) {
        Objects.requireNonNull(peerHost, "peerHost");
        Objects.requireNonNull(file, "file");

        String transferId = UUID.randomUUID().toString();
        CompletableFuture<TransferItem> completion = new CompletableFuture<>();
        ActiveTransferContext ctx = createContext(transferId, peerHost.trim(), port, file, completion);
        runningTasks.put(transferId, ctx);
        registerContextMonitoring(ctx);

        try {
            Future<?> task = executor.submit(() -> {
                try {
                    TransferItem result = runTransferWithContext(ctx, NO_OP_OBSERVER, () -> false);
                    completion.complete(result);
                } catch (Exception error) {
                    TransferItem failed = ctx.latestItem.get();
                    if (!ctx.terminalHandled.get()
                            && (failed.status() == TransferStatus.QUEUED
                                || failed.status() == TransferStatus.CONNECTING
                                || failed.status() == TransferStatus.TRANSFERRING)) {
                        monitoringService.onFailed(ctx.transferId, TransferDirection.OUTGOING, ctx.fileName, error.getMessage(), error);
                        failed = recordFailure(ctx, ctx.fileName, error, NO_OP_OBSERVER);
                    }
                    completion.complete(failed);
                } finally {
                    runningTasks.remove(transferId, ctx);
                    activeSockets.remove(transferId);
                }
            });
            ctx.future = task;
            if (ctx.cancelled.get()) {
                task.cancel(true);
            }
        } catch (RuntimeException submitError) {
            runningTasks.remove(transferId, ctx);
            monitoringService.onFailed(ctx.transferId, TransferDirection.OUTGOING, ctx.fileName, submitError.getMessage(), submitError);
            TransferItem failed = recordFailure(ctx, ctx.fileName, submitError, NO_OP_OBSERVER);
            completion.complete(failed);
        }

        return completion;
    }

    @Override
    public TransferItem sendFileBlocking(
        String transferId,
        String peerHost,
        int port,
        Path file,
        ProgressObserver observer,
        BooleanSupplier cancelledSupplier
    ) throws Exception {
        Objects.requireNonNull(peerHost, "peerHost");
        Objects.requireNonNull(file, "file");

        String resolvedId = transferId != null && !transferId.isBlank()
            ? transferId
            : UUID.randomUUID().toString();
        ProgressObserver safeObserver = observer != null ? observer : NO_OP_OBSERVER;
        BooleanSupplier safeCancelled = cancelledSupplier != null ? cancelledSupplier : () -> false;

        ActiveTransferContext ctx = createContext(resolvedId, peerHost.trim(), port, file, null);
        runningTasks.put(resolvedId, ctx);
        registerContextMonitoring(ctx);
        try {
            return runTransferWithContext(ctx, safeObserver, safeCancelled);
        } finally {
            runningTasks.remove(resolvedId, ctx);
            activeSockets.remove(resolvedId);
        }
    }

    @Override
    public boolean hasActiveOutgoingTransfer(String peerHost, int port, Path file) {
        if (peerHost == null || file == null) {
            return false;
        }
        String peerAddress = peerHost.trim() + ":" + port;
        Path normalized = safeNormalize(file);
        for (ActiveTransferContext ctx : runningTasks.values()) {
            if (!ctx.cancelled.get()
                && !ctx.terminalHandled.get()
                && ctx.peerAddress.equalsIgnoreCase(peerAddress)
                && ctx.normalizedFile.equals(normalized)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean cancelActiveTransfer(String transferId) {
        return cancelActiveTransfer(transferId, true);
    }

    @Override
    public boolean cancelActiveTransfer(String transferId, boolean interruptWorker) {
        if (transferId == null || transferId.isBlank()) {
            return false;
        }

        boolean cancelledInMonitor = monitoringService.cancelTransfer(transferId);

        Socket socket = activeSockets.remove(transferId);
        if (socket != null) {
            closeQuietly(socket);
        }

        ActiveTransferContext ctx = runningTasks.remove(transferId);
        if (ctx != null) {
            ctx.cancelled.set(true);
            TransferItem cancelledItem = finishCancelled(ctx, NO_OP_OBSERVER);
            if (interruptWorker) {
                ctx.interruptExecution();
            }
            if (ctx.completion != null) {
                ctx.completion.complete(cancelledItem);
            }
            return true;
        }

        TransferItem snapshot = activeSnapshots.get(transferId);
        if (snapshot != null) {
            TransferItem cancelled = new TransferItem(
                snapshot.id(),
                snapshot.fileName(),
                snapshot.totalBytes(),
                snapshot.transferredBytes(),
                snapshot.direction(),
                TransferStatus.CANCELLED,
                snapshot.peerAddress(),
                "Cancelled",
                Instant.now(),
                "Cancelled by user"
            );
            moveToHistoryLocal(cancelled);
            return true;
        }
        return cancelledInMonitor || socket != null;
    }

    private ActiveTransferContext createContext(
        String transferId,
        String peerHost,
        int port,
        Path file,
        CompletableFuture<TransferItem> completion
    ) {
        String fileName = file.getFileName() != null ? file.getFileName().toString() : file.toString();
        long initialSize = 0L;
        try {
            if (Files.isRegularFile(file)) {
                initialSize = Files.size(file);
            }
        } catch (Exception ignored) {
        }

        String peerAddress = peerHost + ":" + port;
        TransferItem queued = new TransferItem(
            transferId,
            fileName,
            initialSize,
            0L,
            TransferDirection.OUTGOING,
            TransferStatus.QUEUED,
            peerAddress,
            "Pending SHA-256",
            Instant.now(),
            "Connecting over Mutual TLS 1.3"
        );
        return new ActiveTransferContext(
            transferId,
            peerAddress,
            safeNormalize(file),
            fileName,
            initialSize,
            queued,
            completion
        );
    }

    private void registerContextMonitoring(ActiveTransferContext ctx) {
        upsertActiveTransferLocal(ctx.latestItem.get());
        monitoringService.registerQueued(
            ctx.transferId,
            TransferDirection.OUTGOING,
            ctx.fileName,
            ctx.initialSize,
            ctx.peerAddress,
            "Queued for mTLS 1.3 handshake"
        );
        monitoringService.registerCancelHandler(ctx.transferId, () -> cancelActiveTransfer(ctx.transferId, true));
    }

    private TransferItem runTransferWithContext(
        ActiveTransferContext ctx,
        ProgressObserver observer,
        BooleanSupplier cancelledSupplier
    ) throws Exception {
        ctx.workerThread.set(Thread.currentThread());
        try {
            if (isCancelled(ctx, cancelledSupplier)) {
                return finishCancelled(ctx, observer);
            }

            if (!Files.exists(ctx.normalizedFile)) {
                throw new NoSuchFileException(ctx.normalizedFile.toString());
            }
            if (!Files.isRegularFile(ctx.normalizedFile)) {
                throw new IOException("Path is not a regular file: " + ctx.normalizedFile);
            }
            try (InputStream in = Files.newInputStream(ctx.normalizedFile)) {
                in.readNBytes(1);
            } catch (AccessDeniedException e) {
                throw e;
            } catch (IOException e) {
                throw new IOException("Cannot read source file: " + ctx.normalizedFile, e);
            }

            upsertActiveTransferLocal(ctx.latestItem.get());
            observer.onStageChanged(
                TransferStatus.QUEUED,
                "Connecting & Authenticating",
                "Connecting to " + ctx.peerAddress + " over Mutual TLS 1.3 and awaiting peer approval..."
            );

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
                    if (isCancelled(ctx, cancelledSupplier)) {
                        return;
                    }
                    monitoringService.onConnecting(id, direction, name, totalBytes, endpoint, statusNote);
                    long resolvedTotal = totalBytes >= 0L ? totalBytes : ctx.initialSize;
                    TransferItem connecting = new TransferItem(
                        ctx.transferId,
                        name,
                        resolvedTotal,
                        0L,
                        TransferDirection.OUTGOING,
                        TransferStatus.CONNECTING,
                        ctx.peerAddress,
                        "Pending SHA-256",
                        Instant.now(),
                        statusNote != null && !statusNote.isBlank() ? statusNote : "Connecting over Mutual TLS 1.3"
                    );
                    ctx.latestItem.set(connecting);
                    upsertActiveTransferLocal(connecting);
                    observer.onStageChanged(
                        TransferStatus.CONNECTING,
                        "Connecting & Authenticating",
                        statusNote != null && !statusNote.isBlank()
                            ? statusNote
                            : "Connecting to " + ctx.peerAddress + " over Mutual TLS 1.3..."
                    );
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
                    if (isCancelled(ctx, cancelledSupplier)) {
                        return;
                    }
                    monitoringService.onStarted(id, direction, name, totalBytes, initialBytes, endpoint);
                    long safeInitial = Math.max(0L, initialBytes);
                    TransferItem started = new TransferItem(
                        ctx.transferId,
                        name,
                        Math.max(0L, totalBytes),
                        safeInitial,
                        TransferDirection.OUTGOING,
                        TransferStatus.TRANSFERRING,
                        ctx.peerAddress,
                        "SHA-256 active",
                        Instant.now(),
                        safeInitial > 0L ? "Resuming encrypted transfer" : "Connected • Streaming encrypted chunks"
                    );
                    ctx.latestItem.set(started);
                    upsertActiveTransferLocal(started);
                    observer.onStageChanged(
                        TransferStatus.TRANSFERRING,
                        "Connected • Transferring",
                        "Streaming encrypted 64 KB chunks to " + ctx.peerAddress + "."
                    );
                    observer.onProgress(name, safeInitial, totalBytes);
                }

                @Override
                public void onProgress(
                    String id,
                    TransferDirection direction,
                    String name,
                    long transferredBytes,
                    long totalBytes
                ) {
                    if (isCancelled(ctx, cancelledSupplier)) {
                        return;
                    }
                    monitoringService.onProgress(id, direction, name, transferredBytes, totalBytes);
                    boolean awaitingVerification = totalBytes > 0 && transferredBytes >= totalBytes;
                    String note = awaitingVerification
                        ? "All bytes acknowledged • Awaiting SHA-256 verification"
                        : "Streaming encrypted chunks";
                    TransferItem progressItem = new TransferItem(
                        ctx.transferId,
                        name,
                        Math.max(0L, totalBytes),
                        Math.max(0L, transferredBytes),
                        TransferDirection.OUTGOING,
                        TransferStatus.TRANSFERRING,
                        ctx.peerAddress,
                        "SHA-256 active",
                        Instant.now(),
                        note
                    );
                    ctx.latestItem.set(progressItem);

                    long now = System.nanoTime();
                    long previous = ctx.lastUiProgressNanos.get();
                    if (transferredBytes == 0L
                        || awaitingVerification
                        || now - previous >= PROGRESS_UI_INTERVAL_NANOS) {
                        ctx.lastUiProgressNanos.set(now);
                        upsertActiveTransferLocal(progressItem);
                    }

                    if (awaitingVerification) {
                        observer.onStageChanged(
                            TransferStatus.TRANSFERRING,
                            "Verifying Integrity",
                            "All bytes transmitted; awaiting remote SHA-256 confirmation..."
                        );
                    }
                    observer.onProgress(name, transferredBytes, totalBytes);
                }

                @Override
                public void onCompleted(String id, TransferDirection direction, String name) {
                    ctx.backendCompletedCalled.set(true);
                    if (isCancelled(ctx, cancelledSupplier)) {
                        return;
                    }
                    monitoringService.onCompleted(id, direction, name);
                    recordCompleted(ctx, name, observer);
                }

                @Override
                public void onFailed(
                    String id,
                    TransferDirection direction,
                    String name,
                    String reason,
                    Exception error
                ) {
                    ctx.backendErrorCalled.set(true);
                    if (isCancelled(ctx, cancelledSupplier)) {
                        finishCancelled(ctx, observer);
                        return;
                    }
                    if (isRejectionError(error) || isRejectionMessage(reason)) {
                        monitoringService.onFailed(
                            id,
                            direction,
                            name,
                            reason != null && !reason.isBlank() ? reason : "Transfer rejected by receiver",
                            error
                        );
                        recordRejected(ctx, name, observer);
                        return;
                    }
                    monitoringService.onFailed(id, direction, name, reason, error);
                    Throwable effectiveError = error != null
                        ? error
                        : new IOException(reason != null && !reason.isBlank() ? reason : "Transfer failed");
                    recordFailure(ctx, name, effectiveError, observer);
                }

                @Override
                public void onCancelled(String id, TransferDirection direction, String name, String reason) {
                    ctx.backendErrorCalled.set(true);
                    finishCancelled(ctx, observer);
                }
            };

            TransferListener legacyListener = new TransferListener() {
                @Override
                public void onStarted(String name, long totalBytes) {
                    sessionBridge.onStarted(ctx.transferId, TransferDirection.OUTGOING, name, totalBytes, 0L, ctx.peerAddress);
                }

                @Override
                public void onProgress(String name, long transferredBytes, long totalBytes) {
                    sessionBridge.onProgress(ctx.transferId, TransferDirection.OUTGOING, name, transferredBytes, totalBytes);
                }

                @Override
                public void onCompleted(String name) {
                    sessionBridge.onCompleted(ctx.transferId, TransferDirection.OUTGOING, name);
                }

                @Override
                public void onError(String name, Exception error) {
                    String msg = error != null && error.getMessage() != null ? error.getMessage() : "Transfer failed";
                    sessionBridge.onFailed(ctx.transferId, TransferDirection.OUTGOING, name, msg, error);
                }
            };

            String hostOnly = ctx.peerAddress.substring(0, ctx.peerAddress.lastIndexOf(':'));
            int port = Integer.parseInt(ctx.peerAddress.substring(ctx.peerAddress.lastIndexOf(':') + 1));

            if (sendTransport != null) {
                sendTransport.send(hostOnly, port, ctx.normalizedFile, legacyListener);
            } else {
                    TcpClient.send(
                        hostOnly,
                        port,
                        ctx.normalizedFile,
                        ctx.transferId,
                        sessionBridge,
                        socket -> {
                            if (socket != null) {
                                activeSockets.put(ctx.transferId, socket);
                                if (isCancelled(ctx, cancelledSupplier)) {
                                    closeQuietly(socket);
                                }
                            } else {
                                activeSockets.remove(ctx.transferId);
                            }
                        }
                    );
                }

                if (isCancelled(ctx, cancelledSupplier)) {
                    return finishCancelled(ctx, observer);
                }

                // A legacy transport or FileSender implementation may return without invoking onCompleted/onError on Protocol.REJECT.
                if (!ctx.backendCompletedCalled.get() && !ctx.backendErrorCalled.get()) {
                    monitoringService.onFailed(
                        ctx.transferId,
                        TransferDirection.OUTGOING,
                        ctx.fileName,
                        "Transfer rejected by peer",
                        null
                    );
                    return recordRejected(ctx, ctx.fileName, observer);
                }

                return ctx.latestItem.get();
            } catch (Exception error) {
                if (isCancelled(ctx, cancelledSupplier)
                    || (ctx.cancelled.get() && (error instanceof InterruptedException || error instanceof InterruptedIOException))) {
                    return finishCancelled(ctx, observer);
                }
                if (isRejectionError(error)) {
                    monitoringService.onFailed(
                        ctx.transferId,
                        TransferDirection.OUTGOING,
                        ctx.fileName,
                        error.getMessage(),
                        error
                    );
                    return recordRejected(ctx, ctx.fileName, observer);
                }
                monitoringService.onFailed(
                    ctx.transferId,
                    TransferDirection.OUTGOING,
                    ctx.fileName,
                    error.getMessage() != null ? error.getMessage() : error.getClass().getSimpleName(),
                    error
                );
                recordFailure(ctx, ctx.fileName, error, observer);
                throw error;
            } finally {
            activeSockets.remove(ctx.transferId);
            ctx.workerThread.set(null);
            if (!ctx.terminalHandled.get() && isCancelled(ctx, cancelledSupplier)) {
                finishCancelled(ctx, observer);
            }
        }
    }

    private static boolean isRejectionError(Throwable error) {
        return error instanceof IOException && isRejectionMessage(error.getMessage());
    }

    private static boolean isRejectionMessage(String message) {
        return "Transfer rejected by receiver".equals(message);
    }

    private boolean isCancelled(ActiveTransferContext ctx, BooleanSupplier cancelledSupplier) {
        return ctx.cancelled.get()
            || cancelledSupplier.getAsBoolean()
            || Thread.currentThread().isInterrupted();
    }

    private TransferItem recordCompleted(ActiveTransferContext ctx, String name, ProgressObserver observer) {
        if (ctx.terminalHandled.compareAndSet(false, true)) {
            TransferItem current = ctx.latestItem.get();
            long size = current != null && current.totalBytes() > 0 ? current.totalBytes() : ctx.initialSize;
            TransferItem finished = new TransferItem(
                ctx.transferId,
                name,
                size,
                size,
                TransferDirection.OUTGOING,
                TransferStatus.COMPLETED,
                ctx.peerAddress,
                "SHA-256 verified",
                Instant.now(),
                "Transfer completed and verified"
            );
            ctx.latestItem.set(finished);
            moveToHistoryLocal(finished);
            observer.onStageChanged(
                TransferStatus.COMPLETED,
                "Verified & Completed",
                name + " transmitted and verified with SHA-256."
            );
            if (ctx.completion != null) {
                ctx.completion.complete(finished);
            }
            return finished;
        }
        return ctx.latestItem.get();
    }

    private TransferItem recordRejected(ActiveTransferContext ctx, String name, ProgressObserver observer) {
        if (ctx.terminalHandled.compareAndSet(false, true)) {
            TransferItem current = ctx.latestItem.get();
            long size = current != null && current.totalBytes() > 0 ? current.totalBytes() : ctx.initialSize;
            TransferItem rejected = new TransferItem(
                ctx.transferId,
                name,
                size,
                0L,
                TransferDirection.OUTGOING,
                TransferStatus.REJECTED,
                ctx.peerAddress,
                "Rejected by peer",
                Instant.now(),
                "Transfer offer was rejected by " + ctx.peerAddress + "."
            );
            ctx.latestItem.set(rejected);
            moveToHistoryLocal(rejected);
            observer.onStageChanged(
                TransferStatus.REJECTED,
                "Rejected by Peer",
                rejected.note()
            );
            if (ctx.completion != null) {
                ctx.completion.complete(rejected);
            }
            return rejected;
        }
        return ctx.latestItem.get();
    }

    private TransferItem recordFailure(
        ActiveTransferContext ctx,
        String name,
        Throwable error,
        ProgressObserver observer
    ) {
        if (ctx.terminalHandled.compareAndSet(false, true)) {
            TransferItem current = ctx.latestItem.get();
            long size = current != null && current.totalBytes() > 0 ? current.totalBytes() : ctx.initialSize;
            long done = current != null ? current.transferredBytes() : 0L;
            int splitIdx = ctx.peerAddress.lastIndexOf(':');
            String host = splitIdx > 0 ? ctx.peerAddress.substring(0, splitIdx) : ctx.peerAddress;
            int port = splitIdx > 0 ? Integer.parseInt(ctx.peerAddress.substring(splitIdx + 1)) : 0;
            String msg = describeNetworkError(error, host, port);
            TransferItem failed = new TransferItem(
                ctx.transferId,
                name,
                size,
                done,
                TransferDirection.OUTGOING,
                TransferStatus.FAILED,
                ctx.peerAddress,
                "Interrupted",
                Instant.now(),
                msg
            );
            ctx.latestItem.set(failed);
            moveToHistoryLocal(failed);
            observer.onStageChanged(TransferStatus.FAILED, "Transfer Failed", msg);
            CentralErrorHandler.getInstance().handleError(error, name);
            if (ctx.completion != null) {
                ctx.completion.complete(failed);
            }
            return failed;
        }
        return ctx.latestItem.get();
    }

    private TransferItem finishCancelled(ActiveTransferContext ctx, ProgressObserver observer) {
        ctx.cancelled.set(true);
        Socket socket = activeSockets.remove(ctx.transferId);
        if (socket != null) {
            closeQuietly(socket);
        }
        monitoringService.onCancelled(
            ctx.transferId,
            TransferDirection.OUTGOING,
            ctx.fileName,
            "Cancelled by user"
        );
        if (ctx.terminalHandled.compareAndSet(false, true)) {
            TransferItem current = ctx.latestItem.get();
            long size = current != null && current.totalBytes() > 0 ? current.totalBytes() : ctx.initialSize;
            long done = current != null ? current.transferredBytes() : 0L;
            TransferItem cancelledItem = new TransferItem(
                ctx.transferId,
                ctx.fileName,
                size,
                done,
                TransferDirection.OUTGOING,
                TransferStatus.CANCELLED,
                ctx.peerAddress,
                "Cancelled",
                Instant.now(),
                "Cancelled by user"
            );
            ctx.latestItem.set(cancelledItem);
            moveToHistoryLocal(cancelledItem);
            observer.onStageChanged(
                TransferStatus.CANCELLED,
                "Transfer Cancelled",
                "Outgoing transfer was cancelled and cleaned up."
            );
            if (ctx.completion != null) {
                ctx.completion.complete(cancelledItem);
            }
            return cancelledItem;
        }
        return ctx.latestItem.get();
    }

    public static String describeNetworkError(Throwable error, String peerHost, int port) {
        if (error == null) {
            return "Transfer failed due to an unexpected error.";
        }
        Throwable cause = error;
        if (cause instanceof ExecutionException && cause.getCause() != null) {
            cause = cause.getCause();
        }
        String rawMsg = cause.getMessage() != null && !cause.getMessage().isBlank()
            ? cause.getMessage()
            : cause.getClass().getSimpleName();

        String endpoint = (peerHost != null && !peerHost.isBlank() ? peerHost : "peer")
            + (port > 0 ? ":" + port : "");

        if (cause instanceof NoSuchFileException || cause instanceof FileNotFoundException) {
            return "File does not exist: " + rawMsg;
        }
        if (cause instanceof AccessDeniedException) {
            return "Permission denied accessing file: " + rawMsg;
        }
        if (cause instanceof ConnectException) {
            return "Unable to connect to " + endpoint + " (" + rawMsg + "). Confirm the peer receiver is running.";
        }
        if (cause instanceof UnknownHostException) {
            return "Cannot resolve destination host \"" + (peerHost != null ? peerHost : rawMsg) + "\".";
        }
        if (cause instanceof SocketTimeoutException) {
            return "Connection or approval timed out for " + endpoint + " (" + rawMsg + ").";
        }
        if (cause instanceof SSLHandshakeException || cause instanceof SSLException) {
            return "Mutual TLS 1.3 handshake failed with " + endpoint + ": " + rawMsg;
        }
        if (cause instanceof EOFException) {
            return "Connection closed unexpectedly by " + endpoint + " before verification completed.";
        }
        return rawMsg;
    }

    private static Path safeNormalize(Path file) {
        try {
            return file.toAbsolutePath().normalize();
        } catch (Exception ignored) {
            return file;
        }
    }

    public void upsertActiveTransfer(TransferItem item) {
        upsertActiveTransferLocal(item);
        monitoringService.syncTransferItem(item);
    }

    public void moveToHistory(TransferItem finishedItem) {
        moveToHistoryLocal(finishedItem);
        monitoringService.syncTransferItem(finishedItem);
    }

    void upsertActiveTransferLocal(TransferItem item) {
        Objects.requireNonNull(item, "item");
        activeSnapshots.put(item.id(), item);
        runOnFxThread(() -> {
            if (!activeSnapshots.containsKey(item.id())) {
                return;
            }
            for (int i = 0; i < activeTransfers.size(); i++) {
                if (activeTransfers.get(i).id().equals(item.id())) {
                    activeTransfers.set(i, item);
                    return;
                }
            }
            activeTransfers.add(0, item);
        });
    }

    void moveToHistoryLocal(TransferItem finishedItem) {
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
        for (String id : runningTasks.keySet()) {
            cancelActiveTransfer(id);
        }
        activeSockets.values().forEach(TransferService::closeQuietly);
        activeSockets.clear();
        runningTasks.clear();
        executor.shutdownNow();
        try {
            executor.awaitTermination(2, java.util.concurrent.TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
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
