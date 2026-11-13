package com.syncshare.service;

import com.syncshare.events.TransferSessionListener;
import com.syncshare.model.TransferDirection;
import com.syncshare.model.TransferItem;
import com.syncshare.model.TransferStatus;
import com.syncshare.monitoring.MonitoredTransfer;
import com.syncshare.monitoring.TransferMetricsCalculator;
import com.syncshare.monitoring.TransferMonitorListener;
import com.syncshare.monitoring.TransferMonitorState;
import com.syncshare.monitoring.TransferSpeedEstimator;
import com.syncshare.reliability.AppErrorCategory;
import com.syncshare.reliability.AppException;
import com.syncshare.reliability.CentralErrorHandler;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyIntegerProperty;
import javafx.beans.property.ReadOnlyIntegerWrapper;
import javafx.beans.property.ReadOnlyLongProperty;
import javafx.beans.property.ReadOnlyLongWrapper;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * Central concurrent transfer monitoring service that tracks live outgoing and
 * incoming transfers, enforces valid state transitions, computes real-time speed
 * and ETA from actual byte deltas, throttles JavaFX UI refreshes, and manages
 * listener subscriptions and resource cleanup.
 */
public class TransferMonitoringService implements TransferSessionListener {

    private static final long DEFAULT_UI_THROTTLE_NANOS = 80_000_000L; // 80 ms
    private static final int MAX_RECENT_TRANSFERS = 100;

    private static final class TransferEntry {
        final String transferId;
        final TransferDirection direction;
        final TransferSpeedEstimator speedEstimator;
        Instant startedAt;

        String fileName;
        String peerAddress;
        long totalBytes;
        long transferredBytes;
        TransferMonitorState state;
        Instant completedAt;
        Instant updatedAt;
        String errorMessage;
        String statusNote;
        Runnable cancelHandler;

        TransferEntry(
            String transferId,
            TransferDirection direction,
            String fileName,
            long totalBytes,
            String peerAddress,
            TransferMonitorState initialState,
            String statusNote,
            Instant now,
            LongSupplier nanoClock
        ) {
            this.transferId = transferId;
            this.direction = Objects.requireNonNullElse(direction, TransferDirection.OUTGOING);
            this.fileName = sanitizeFileName(fileName);
            this.totalBytes = normalizeTotalBytes(totalBytes);
            this.peerAddress = sanitizePeer(peerAddress);
            this.state = Objects.requireNonNullElse(initialState, TransferMonitorState.QUEUED);
            this.transferredBytes = 0L;
            this.startedAt = now;
            this.updatedAt = now;
            this.errorMessage = "";
            this.statusNote = statusNote != null ? statusNote : "";
            this.speedEstimator = new TransferSpeedEstimator(
                nanoClock,
                TransferSpeedEstimator.DEFAULT_MIN_WINDOW_NANOS,
                TransferSpeedEstimator.DEFAULT_STALL_TIMEOUT_NANOS,
                TransferSpeedEstimator.DEFAULT_EMA_ALPHA
            );
        }

        synchronized MonitoredTransfer snapshot(long nowNanos) {
            double speed = state == TransferMonitorState.RUNNING
                ? speedEstimator.refreshAt(nowNanos)
                : TransferMetricsCalculator.UNAVAILABLE_SPEED;
            double progress = TransferMetricsCalculator.calculateProgress(transferredBytes, totalBytes, state);
            long eta = TransferMetricsCalculator.calculateEtaSeconds(transferredBytes, totalBytes, speed, state);
            return new MonitoredTransfer(
                transferId,
                fileName,
                direction,
                peerAddress,
                totalBytes,
                transferredBytes,
                progress,
                speed,
                eta,
                state,
                startedAt,
                completedAt,
                updatedAt,
                errorMessage,
                statusNote
            );
        }
    }

    @FunctionalInterface
    public interface TerminalTransferListener {
        void onTransferTerminal(MonitoredTransfer snapshot);
    }

    private final LongSupplier nanoClock;
    private final long uiThrottleNanos;
    private final ConcurrentHashMap<String, TransferEntry> entries = new ConcurrentHashMap<>();
    private final CopyOnWriteArrayList<TransferMonitorListener> listeners = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<TerminalTransferListener> terminalListeners = new CopyOnWriteArrayList<>();

    private final ObservableList<MonitoredTransfer> activeTransfers = FXCollections.observableArrayList();
    private final ObservableList<MonitoredTransfer> unmodifiableActiveTransfers =
        FXCollections.unmodifiableObservableList(activeTransfers);

    private final ObservableList<MonitoredTransfer> recentTransfers = FXCollections.observableArrayList();
    private final ObservableList<MonitoredTransfer> unmodifiableRecentTransfers =
        FXCollections.unmodifiableObservableList(recentTransfers);

    private final ReadOnlyIntegerWrapper activeCount = new ReadOnlyIntegerWrapper(this, "activeCount", 0);
    private final ReadOnlyIntegerWrapper completedCount = new ReadOnlyIntegerWrapper(this, "completedCount", 0);
    private final ReadOnlyIntegerWrapper failedCount = new ReadOnlyIntegerWrapper(this, "failedCount", 0);
    private final ReadOnlyLongWrapper totalTransferredBytes = new ReadOnlyLongWrapper(this, "totalTransferredBytes", 0L);

    private final AtomicBoolean uiPublishPending = new AtomicBoolean(false);
    private final AtomicBoolean immediatePublishRequested = new AtomicBoolean(false);
    private final AtomicBoolean deferredPublishScheduled = new AtomicBoolean(false);
    private final AtomicLong lastUiPublishNanos = new AtomicLong(0L);
    private final AtomicBoolean shutDown = new AtomicBoolean(false);
    private final ScheduledExecutorService maintenanceExecutor;

    public TransferMonitoringService() {
        this(System::nanoTime, DEFAULT_UI_THROTTLE_NANOS, true);
    }

    public TransferMonitoringService(LongSupplier nanoClock, long uiThrottleNanos, boolean enableStallTicker) {
        this.nanoClock = Objects.requireNonNull(nanoClock, "nanoClock");
        this.uiThrottleNanos = Math.max(0L, uiThrottleNanos);
        if (enableStallTicker) {
            this.maintenanceExecutor = Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "syncshare-monitor-ticker");
                thread.setDaemon(true);
                return thread;
            });
            this.maintenanceExecutor.scheduleAtFixedRate(
                this::tickActiveTransfers,
                1000L,
                1000L,
                TimeUnit.MILLISECONDS
            );
        } else {
            this.maintenanceExecutor = null;
        }
    }

    public ObservableList<MonitoredTransfer> getActiveTransfers() {
        return unmodifiableActiveTransfers;
    }

    public ObservableList<MonitoredTransfer> getRecentTransfers() {
        return unmodifiableRecentTransfers;
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

    public ReadOnlyLongProperty totalTransferredBytesProperty() {
        return totalTransferredBytes.getReadOnlyProperty();
    }

    public Optional<MonitoredTransfer> getTransfer(String transferId) {
        if (transferId == null || transferId.isBlank()) {
            return Optional.empty();
        }
        TransferEntry entry = entries.get(transferId);
        return entry != null ? Optional.of(entry.snapshot(nanoClock.getAsLong())) : Optional.empty();
    }

    public List<MonitoredTransfer> getActiveSnapshot() {
        return buildSortedSnapshots(true);
    }

    public List<MonitoredTransfer> getRecentSnapshot() {
        return buildSortedSnapshots(false);
    }

    /**
     * Registers a listener if not already subscribed.
     *
     * @return {@code true} if added, {@code false} if null or already subscribed
     */
    public boolean addListener(TransferMonitorListener listener) {
        if (listener == null || shutDown.get()) {
            return false;
        }
        return listeners.addIfAbsent(listener);
    }

    public boolean removeListener(TransferMonitorListener listener) {
        if (listener == null) {
            return false;
        }
        return listeners.remove(listener);
    }

    public AutoCloseable subscribe(TransferMonitorListener listener) {
        Objects.requireNonNull(listener, "listener");
        addListener(listener);
        return () -> removeListener(listener);
    }

    public int listenerCount() {
        return listeners.size();
    }

    public boolean addTerminalListener(TerminalTransferListener listener) {
        if (listener == null || shutDown.get()) {
            return false;
        }
        return terminalListeners.addIfAbsent(listener);
    }

    public boolean removeTerminalListener(TerminalTransferListener listener) {
        if (listener == null) {
            return false;
        }
        return terminalListeners.remove(listener);
    }

    public int terminalListenerCount() {
        return terminalListeners.size();
    }

    public void registerQueued(
        String transferId,
        TransferDirection direction,
        String fileName,
        long totalBytes,
        String peerAddress,
        String statusNote
    ) {
        if (shutDown.get() || transferId == null || transferId.isBlank()) {
            return;
        }
        Instant now = Instant.now();
        entries.computeIfAbsent(transferId, id -> new TransferEntry(
            id,
            direction,
            fileName,
            totalBytes,
            peerAddress,
            TransferMonitorState.QUEUED,
            statusNote != null ? statusNote : "Queued",
            now,
            nanoClock
        ));
        requestPublish(true);
    }

    public void registerCancelHandler(String transferId, Runnable cancelHandler) {
        if (transferId == null || transferId.isBlank() || cancelHandler == null) {
            return;
        }
        TransferEntry entry = entries.get(transferId);
        if (entry == null) {
            return;
        }
        boolean invokeImmediately = false;
        synchronized (entry) {
            if (entry.state.isTerminal()) {
                invokeImmediately = entry.state == TransferMonitorState.CANCELLED;
            } else {
                entry.cancelHandler = cancelHandler;
            }
        }
        if (invokeImmediately) {
            runCancelSafely(cancelHandler);
        }
    }

    public boolean cancelTransfer(String transferId) {
        if (transferId == null || transferId.isBlank()) {
            return false;
        }
        TransferEntry entry = entries.get(transferId);
        if (entry == null) {
            return false;
        }
        Runnable handlerToRun = null;
        boolean changed = false;
        MonitoredTransfer terminalSnapshot = null;
        Instant now = Instant.now();
        synchronized (entry) {
            if (!entry.state.isTerminal() && entry.state.canTransitionTo(TransferMonitorState.CANCELLED)) {
                entry.state = TransferMonitorState.CANCELLED;
                entry.completedAt = now;
                entry.updatedAt = now;
                entry.statusNote = "Cancelled by user";
                entry.errorMessage = "Transfer cancelled";
                entry.speedEstimator.markInactive();
                handlerToRun = entry.cancelHandler;
                entry.cancelHandler = null;
                changed = true;
                terminalSnapshot = entry.snapshot(nanoClock.getAsLong());
            }
        }
        if (handlerToRun != null) {
            runCancelSafely(handlerToRun);
        }
        if (changed) {
            notifyTerminalListeners(terminalSnapshot);
            requestPublish(true);
        }
        return changed;
    }

    @Override
    public void onConnecting(
        String transferId,
        TransferDirection direction,
        String fileName,
        long totalBytes,
        String peerAddress,
        String statusNote
    ) {
        if (shutDown.get() || transferId == null || transferId.isBlank()) {
            return;
        }
        Instant now = Instant.now();
        TransferEntry entry = getOrCreateEntry(
            transferId,
            direction,
            fileName,
            totalBytes,
            peerAddress,
            TransferMonitorState.CONNECTING,
            statusNote,
            now
        );
        boolean changed = false;
        synchronized (entry) {
            if (entry.state.isTerminal()) {
                return;
            }
            if (entry.state == TransferMonitorState.QUEUED && entry.state.canTransitionTo(TransferMonitorState.CONNECTING)) {
                entry.state = TransferMonitorState.CONNECTING;
                changed = true;
            }
            if (entry.state == TransferMonitorState.CONNECTING) {
                if (fileName != null && !fileName.isBlank()) {
                    entry.fileName = fileName;
                }
                if (totalBytes >= 0L) {
                    entry.totalBytes = totalBytes;
                }
                if (peerAddress != null && !peerAddress.isBlank()) {
                    entry.peerAddress = peerAddress;
                }
                if (statusNote != null && !statusNote.isBlank()) {
                    entry.statusNote = statusNote;
                }
                entry.updatedAt = now;
                changed = true;
            }
        }
        if (changed) {
            requestPublish(true);
        }
    }

    @Override
    public void onStarted(
        String transferId,
        TransferDirection direction,
        String fileName,
        long totalBytes,
        long initialBytes,
        String peerAddress
    ) {
        if (shutDown.get() || transferId == null || transferId.isBlank()) {
            return;
        }
        Instant now = Instant.now();
        long nowNanos = nanoClock.getAsLong();
        TransferEntry entry = getOrCreateEntry(
            transferId,
            direction,
            fileName,
            totalBytes,
            peerAddress,
            TransferMonitorState.RUNNING,
            "Streaming encrypted chunks",
            now
        );
        boolean changed = false;
        synchronized (entry) {
            if (entry.state.isTerminal()) {
                return;
            }
            if (entry.state != TransferMonitorState.RUNNING) {
                if (!entry.state.canTransitionTo(TransferMonitorState.RUNNING)) {
                    return;
                }
                entry.state = TransferMonitorState.RUNNING;
                entry.startedAt = now;
            }
            if (fileName != null && !fileName.isBlank()) {
                entry.fileName = fileName;
            }
            if (totalBytes >= 0L) {
                entry.totalBytes = totalBytes;
            }
            if (peerAddress != null && !peerAddress.isBlank()) {
                entry.peerAddress = peerAddress;
            }
            long clampedInitial = TransferMetricsCalculator.clampTransferredBytes(initialBytes, entry.totalBytes);
            entry.transferredBytes = Math.max(entry.transferredBytes, clampedInitial);
            entry.speedEstimator.reset(entry.transferredBytes, nowNanos);
            entry.updatedAt = now;
            entry.statusNote = clampedInitial > 0L
                ? "Resumed from " + TransferMetricsCalculator.formatSize(clampedInitial)
                : "Transfer in progress";
            changed = true;
        }
        if (changed) {
            requestPublish(true);
        }
    }

    @Override
    public void onProgress(
        String transferId,
        TransferDirection direction,
        String fileName,
        long transferredBytes,
        long totalBytes
    ) {
        if (shutDown.get() || transferId == null || transferId.isBlank()) {
            return;
        }
        Instant now = Instant.now();
        long nowNanos = nanoClock.getAsLong();
        TransferEntry entry = getOrCreateEntry(
            transferId,
            direction,
            fileName,
            totalBytes,
            null,
            TransferMonitorState.RUNNING,
            "Transfer in progress",
            now
        );
        synchronized (entry) {
            if (entry.state.isTerminal()) {
                return;
            }
            if (entry.state != TransferMonitorState.RUNNING) {
                if (!entry.state.canTransitionTo(TransferMonitorState.RUNNING)) {
                    return;
                }
                entry.state = TransferMonitorState.RUNNING;
                entry.speedEstimator.reset(Math.max(0L, transferredBytes), nowNanos);
            }
            if (fileName != null && !fileName.isBlank()) {
                entry.fileName = fileName;
            }
            if (totalBytes >= 0L) {
                entry.totalBytes = totalBytes;
            }
            long clamped = TransferMetricsCalculator.clampTransferredBytes(transferredBytes, entry.totalBytes);
            if (clamped >= entry.transferredBytes) {
                entry.transferredBytes = clamped;
            }
            entry.speedEstimator.recordBytes(entry.transferredBytes, nowNanos);
            entry.updatedAt = now;
        }
        requestPublish(false);
    }

    @Override
    public void onCompleted(
        String transferId,
        TransferDirection direction,
        String fileName
    ) {
        if (transferId == null || transferId.isBlank()) {
            return;
        }
        Instant now = Instant.now();
        TransferEntry entry = getOrCreateEntry(
            transferId,
            direction,
            fileName,
            0L,
            null,
            TransferMonitorState.RUNNING,
            "Transfer verified",
            now
        );
        boolean transitioned = false;
        MonitoredTransfer terminalSnapshot = null;
        synchronized (entry) {
            if (entry.state.isTerminal()) {
                return;
            }
            if (entry.state == TransferMonitorState.QUEUED || entry.state == TransferMonitorState.CONNECTING) {
                if (entry.state.canTransitionTo(TransferMonitorState.RUNNING)) {
                    entry.state = TransferMonitorState.RUNNING;
                }
            }
            if (!entry.state.canTransitionTo(TransferMonitorState.COMPLETED)) {
                return;
            }
            if (fileName != null && !fileName.isBlank()) {
                entry.fileName = fileName;
            }
            if (entry.totalBytes >= 0L) {
                entry.transferredBytes = entry.totalBytes;
            }
            entry.state = TransferMonitorState.COMPLETED;
            entry.completedAt = now;
            entry.updatedAt = now;
            entry.statusNote = "Verified via SHA-256";
            entry.errorMessage = "";
            entry.speedEstimator.markInactive();
            entry.cancelHandler = null;
            transitioned = true;
            terminalSnapshot = entry.snapshot(nanoClock.getAsLong());
        }
        if (transitioned) {
            notifyTerminalListeners(terminalSnapshot);
            trimRecentHistory();
            requestPublish(true);
        }
    }

    @Override
    public void onFailed(
        String transferId,
        TransferDirection direction,
        String fileName,
        String reason,
        Exception error
    ) {
        if (transferId == null || transferId.isBlank()) {
            return;
        }
        Instant now = Instant.now();
        TransferEntry entry = getOrCreateEntry(
            transferId,
            direction,
            fileName,
            TransferMetricsCalculator.UNKNOWN_SIZE,
            null,
            TransferMonitorState.CONNECTING,
            "Transfer failed",
            now
        );
        boolean transitioned = false;
        MonitoredTransfer terminalSnapshot = null;
        synchronized (entry) {
            if (entry.state.isTerminal()) {
                return;
            }
            if (!entry.state.canTransitionTo(TransferMonitorState.FAILED)) {
                return;
            }
            if (fileName != null && !fileName.isBlank() && !"unknown".equals(fileName)) {
                entry.fileName = fileName;
            }
            String message = resolveFailureMessage(reason, error);
            entry.state = TransferMonitorState.FAILED;
            entry.completedAt = now;
            entry.updatedAt = now;
            entry.errorMessage = message;
            entry.statusNote = message;
            entry.speedEstimator.markInactive();
            entry.cancelHandler = null;
            transitioned = true;
            terminalSnapshot = entry.snapshot(nanoClock.getAsLong());
        }
        if (transitioned) {
            notifyTerminalListeners(terminalSnapshot);
            trimRecentHistory();
            requestPublish(true);
            if (error != null) {
                CentralErrorHandler.getInstance().handleError(error, fileName);
            } else if (reason != null && !reason.isBlank()
                    && !reason.toLowerCase().contains("reject")
                    && !reason.equalsIgnoreCase("cancelled by user")) {
                CentralErrorHandler.getInstance().handleError(
                    new AppException(AppErrorCategory.TRANSFER_INTERRUPTED, reason, null),
                    fileName
                );
            }
        }
    }

    @Override
    public void onCancelled(
        String transferId,
        TransferDirection direction,
        String fileName,
        String reason
    ) {
        if (transferId == null || transferId.isBlank()) {
            return;
        }
        Instant now = Instant.now();
        TransferEntry entry = getOrCreateEntry(
            transferId,
            direction,
            fileName,
            TransferMetricsCalculator.UNKNOWN_SIZE,
            null,
            TransferMonitorState.CONNECTING,
            "Transfer cancelled",
            now
        );
        boolean transitioned = false;
        MonitoredTransfer terminalSnapshot = null;
        synchronized (entry) {
            if (entry.state.isTerminal()) {
                return;
            }
            if (!entry.state.canTransitionTo(TransferMonitorState.CANCELLED)) {
                return;
            }
            if (fileName != null && !fileName.isBlank() && !"unknown".equals(fileName)) {
                entry.fileName = fileName;
            }
            String message = (reason != null && !reason.isBlank()) ? reason : "Transfer cancelled";
            entry.state = TransferMonitorState.CANCELLED;
            entry.completedAt = now;
            entry.updatedAt = now;
            entry.errorMessage = message;
            entry.statusNote = message;
            entry.speedEstimator.markInactive();
            entry.cancelHandler = null;
            transitioned = true;
            terminalSnapshot = entry.snapshot(nanoClock.getAsLong());
        }
        if (transitioned) {
            notifyTerminalListeners(terminalSnapshot);
            trimRecentHistory();
            requestPublish(true);
        }
    }

    public void clearCompletedHistory() {
        entries.entrySet().removeIf(e -> {
            synchronized (e.getValue()) {
                return e.getValue().state.isTerminal();
            }
        });
        requestPublish(true);
    }

    /**
     * Synchronizes an externally supplied {@link TransferItem} snapshot into the
     * monitoring state (for example from legacy service calls or UI preview fixtures).
     */
    public void syncTransferItem(TransferItem item) {
        if (shutDown.get() || item == null) {
            return;
        }
        TransferMonitorState mapped = mapStatus(item.status());
        Instant now = item.updatedAt() != null ? item.updatedAt() : Instant.now();
        long nowNanos = nanoClock.getAsLong();

        AtomicBoolean createdNew = new AtomicBoolean(false);
        TransferEntry entry = entries.computeIfAbsent(item.id(), id -> {
            createdNew.set(true);
            return new TransferEntry(
                id,
                item.direction(),
                item.fileName(),
                item.totalBytes(),
                item.peerAddress(),
                mapped,
                item.note(),
                now,
                nanoClock
            );
        });

        MonitoredTransfer terminalSnapshot = null;
        synchronized (entry) {
            boolean wasTerminal = !createdNew.get() && entry.state.isTerminal();
            if (entry.state.isTerminal() && !mapped.isTerminal()) {
                return;
            }
            if (entry.state != mapped && entry.state.canTransitionTo(mapped)) {
                entry.state = mapped;
            } else if (entry.state != mapped && mapped.isTerminal()) {
                entry.state = mapped;
            }
            entry.fileName = sanitizeFileName(item.fileName());
            entry.peerAddress = sanitizePeer(item.peerAddress());
            entry.totalBytes = item.totalBytes();
            entry.transferredBytes = TransferMetricsCalculator.clampTransferredBytes(
                item.transferredBytes(),
                item.totalBytes()
            );
            entry.updatedAt = now;
            entry.statusNote = item.note() != null ? item.note() : "";
            if (mapped == TransferMonitorState.FAILED || mapped == TransferMonitorState.CANCELLED) {
                entry.errorMessage = item.note() != null ? item.note() : mapped.displayName();
                entry.completedAt = now;
                entry.speedEstimator.markInactive();
                entry.cancelHandler = null;
            } else if (mapped == TransferMonitorState.COMPLETED) {
                entry.errorMessage = "";
                entry.completedAt = now;
                entry.speedEstimator.markInactive();
                entry.cancelHandler = null;
            } else if (mapped == TransferMonitorState.RUNNING) {
                entry.speedEstimator.recordBytes(entry.transferredBytes, nowNanos);
            }
            if (!wasTerminal && entry.state.isTerminal()) {
                terminalSnapshot = entry.snapshot(nowNanos);
            }
        }
        if (terminalSnapshot != null) {
            notifyTerminalListeners(terminalSnapshot);
        }
        requestPublish(true);
    }

    private static TransferMonitorState mapStatus(TransferStatus status) {
        if (status == null) {
            return TransferMonitorState.QUEUED;
        }
        return switch (status) {
            case QUEUED -> TransferMonitorState.QUEUED;
            case CONNECTING, AWAITING_APPROVAL -> TransferMonitorState.CONNECTING;
            case TRANSFERRING, PAUSED -> TransferMonitorState.RUNNING;
            case COMPLETED -> TransferMonitorState.COMPLETED;
            case FAILED, REJECTED -> TransferMonitorState.FAILED;
            case CANCELLED -> TransferMonitorState.CANCELLED;
        };
    }

    public void shutdown() {
        if (!shutDown.compareAndSet(false, true)) {
            return;
        }
        List<Runnable> cancelActions = new ArrayList<>();
        List<MonitoredTransfer> terminalSnapshots = new ArrayList<>();
        Instant now = Instant.now();
        for (TransferEntry entry : entries.values()) {
            synchronized (entry) {
                if (!entry.state.isTerminal() && entry.state.canTransitionTo(TransferMonitorState.CANCELLED)) {
                    entry.state = TransferMonitorState.CANCELLED;
                    entry.completedAt = now;
                    entry.updatedAt = now;
                    entry.statusNote = "Cancelled on shutdown";
                    entry.errorMessage = "Application shutting down";
                    entry.speedEstimator.markInactive();
                    terminalSnapshots.add(entry.snapshot(nanoClock.getAsLong()));
                }
                if (entry.cancelHandler != null) {
                    cancelActions.add(entry.cancelHandler);
                    entry.cancelHandler = null;
                }
            }
        }
        for (Runnable action : cancelActions) {
            runCancelSafely(action);
        }
        for (MonitoredTransfer snapshot : terminalSnapshots) {
            notifyTerminalListeners(snapshot);
        }
        if (maintenanceExecutor != null) {
            maintenanceExecutor.shutdownNow();
        }
        listeners.clear();
        terminalListeners.clear();
    }

    private void notifyTerminalListeners(MonitoredTransfer snapshot) {
        if (snapshot == null) {
            return;
        }
        for (TerminalTransferListener listener : terminalListeners) {
            try {
                listener.onTransferTerminal(snapshot);
            } catch (RuntimeException ignored) {
            }
        }
    }

    /**
     * Refreshes active transfer speed/ETA state (for example to zero out speed
     * after a stall timeout) and publishes updated snapshots if any transfer is active.
     */
    public void tickActiveTransfers() {
        if (shutDown.get()) {
            return;
        }
        boolean hasRunning = false;
        long nowNanos = nanoClock.getAsLong();
        for (TransferEntry entry : entries.values()) {
            synchronized (entry) {
                if (entry.state == TransferMonitorState.RUNNING) {
                    entry.speedEstimator.refreshAt(nowNanos);
                    hasRunning = true;
                }
            }
        }
        if (hasRunning) {
            requestPublish(true);
        }
    }

    private TransferEntry getOrCreateEntry(
        String transferId,
        TransferDirection direction,
        String fileName,
        long totalBytes,
        String peerAddress,
        TransferMonitorState defaultState,
        String statusNote,
        Instant now
    ) {
        return entries.computeIfAbsent(transferId, id -> new TransferEntry(
            id,
            direction,
            fileName,
            totalBytes,
            peerAddress,
            defaultState,
            statusNote,
            now,
            nanoClock
        ));
    }

    private void trimRecentHistory() {
        List<TransferEntry> terminalEntries = new ArrayList<>();
        for (TransferEntry entry : entries.values()) {
            synchronized (entry) {
                if (entry.state.isTerminal()) {
                    terminalEntries.add(entry);
                }
            }
        }
        if (terminalEntries.size() <= MAX_RECENT_TRANSFERS) {
            return;
        }
        terminalEntries.sort(Comparator.comparing(e -> {
            synchronized (e) {
                return e.completedAt != null ? e.completedAt : e.updatedAt;
            }
        }));
        int toRemove = terminalEntries.size() - MAX_RECENT_TRANSFERS;
        for (int i = 0; i < toRemove; i++) {
            entries.remove(terminalEntries.get(i).transferId);
        }
    }

    private void requestPublish(boolean immediate) {
        if (immediate) {
            immediatePublishRequested.set(true);
        } else if (uiThrottleNanos > 0L && maintenanceExecutor != null && !maintenanceExecutor.isShutdown()) {
            long elapsed = nanoClock.getAsLong() - lastUiPublishNanos.get();
            if (elapsed < uiThrottleNanos) {
                if (deferredPublishScheduled.compareAndSet(false, true)) {
                    long delayMs = Math.max(1L, TimeUnit.NANOSECONDS.toMillis(uiThrottleNanos - elapsed));
                    maintenanceExecutor.schedule(() -> {
                        deferredPublishScheduled.set(false);
                        if (!shutDown.get()) {
                            requestPublish(true);
                        }
                    }, delayMs, TimeUnit.MILLISECONDS);
                }
                return;
            }
        }
        if (!uiPublishPending.compareAndSet(false, true)) {
            return;
        }
        runOnFxThread(() -> {
            try {
                immediatePublishRequested.set(false);
                publishSnapshotNow(nanoClock.getAsLong());
            } finally {
                uiPublishPending.set(false);
                if (immediatePublishRequested.get()) {
                    requestPublish(true);
                }
            }
        });
    }

    private void publishSnapshotNow(long nowNanos) {
        lastUiPublishNanos.set(nowNanos);
        List<MonitoredTransfer> active = buildSortedSnapshots(true, nowNanos);
        List<MonitoredTransfer> recent = buildSortedSnapshots(false, nowNanos);

        activeTransfers.setAll(active);
        recentTransfers.setAll(recent);

        int completed = 0;
        int failed = 0;
        long totalBytes = 0L;
        for (MonitoredTransfer item : active) {
            totalBytes += item.transferredBytes();
        }
        for (MonitoredTransfer item : recent) {
            if (item.state() == TransferMonitorState.COMPLETED) {
                completed++;
            } else if (item.state() == TransferMonitorState.FAILED || item.state() == TransferMonitorState.CANCELLED) {
                failed++;
            }
            totalBytes += item.transferredBytes();
        }

        activeCount.set(active.size());
        completedCount.set(completed);
        failedCount.set(failed);
        totalTransferredBytes.set(totalBytes);

        for (TransferMonitorListener listener : listeners) {
            try {
                listener.onTransfersUpdated(active, recent);
            } catch (RuntimeException ignored) {
            }
        }
    }

    private List<MonitoredTransfer> buildSortedSnapshots(boolean activeOnly) {
        return buildSortedSnapshots(activeOnly, nanoClock.getAsLong());
    }

    private List<MonitoredTransfer> buildSortedSnapshots(boolean activeOnly, long nowNanos) {
        List<MonitoredTransfer> list = new ArrayList<>();
        for (TransferEntry entry : entries.values()) {
            MonitoredTransfer snapshot = entry.snapshot(nowNanos);
            if (activeOnly ? snapshot.state().isActive() : snapshot.state().isTerminal()) {
                list.add(snapshot);
            }
        }
        if (activeOnly) {
            list.sort(Comparator.comparing(MonitoredTransfer::startedAt).reversed());
        } else {
            list.sort(Comparator.comparing(
                (MonitoredTransfer t) -> t.completedAt() != null ? t.completedAt() : t.updatedAt()
            ).reversed());
        }
        return List.copyOf(list);
    }

    private static String resolveFailureMessage(String reason, Exception error) {
        if (reason != null && !reason.isBlank()) {
            return reason;
        }
        if (error != null && error.getMessage() != null && !error.getMessage().isBlank()) {
            return error.getMessage();
        }
        return error != null ? error.getClass().getSimpleName() : "Transfer failed";
    }

    private static String sanitizeFileName(String fileName) {
        return (fileName != null && !fileName.isBlank()) ? fileName : "unknown";
    }

    private static String sanitizePeer(String peerAddress) {
        return (peerAddress != null && !peerAddress.isBlank()) ? peerAddress : "unknown-peer";
    }

    private static long normalizeTotalBytes(long totalBytes) {
        return totalBytes < 0L ? TransferMetricsCalculator.UNKNOWN_SIZE : totalBytes;
    }

    private static void runCancelSafely(Runnable cancelAction) {
        try {
            cancelAction.run();
        } catch (RuntimeException ignored) {
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
