package com.syncshare.service;

import com.syncshare.events.TransferSessionListener;
import com.syncshare.history.JsonTransferHistoryRepository;
import com.syncshare.history.TransferHistoryDirection;
import com.syncshare.history.TransferHistoryRepository;
import com.syncshare.history.TransferHistoryStatus;
import com.syncshare.history.TransferRecord;
import com.syncshare.model.TransferDirection;
import com.syncshare.monitoring.MonitoredTransfer;
import com.syncshare.monitoring.TransferMonitorState;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyBooleanProperty;
import javafx.beans.property.ReadOnlyBooleanWrapper;
import javafx.beans.property.ReadOnlyStringProperty;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Application service managing persistent terminal transfer history, lifecycle
 * event capture, duplicate prevention, querying/filtering, and safe async I/O.
 */
public class HistoryService implements TransferSessionListener, TransferMonitoringService.TerminalTransferListener {

    public enum SortOrder {
        NEWEST_FIRST("Newest First"),
        OLDEST_FIRST("Oldest First");

        private final String displayName;

        SortOrder(String displayName) {
            this.displayName = displayName;
        }

        public String displayName() {
            return displayName;
        }
    }

    public record OperationResult(boolean success, String message) {
        public static OperationResult ok(String message) {
            return new OperationResult(true, message != null ? message : "");
        }

        public static OperationResult error(String message) {
            return new OperationResult(false, message != null ? message : "Operation failed.");
        }
    }

    private static final class SessionTiming {
        volatile Instant startedAt;
        volatile String fileName;
        volatile long totalBytes;
        volatile String peerInfo;
        volatile TransferDirection direction;

        SessionTiming(Instant startedAt, String fileName, long totalBytes, String peerInfo, TransferDirection direction) {
            this.startedAt = startedAt;
            this.fileName = fileName;
            this.totalBytes = totalBytes;
            this.peerInfo = peerInfo;
            this.direction = direction;
        }
    }

    private final TransferHistoryRepository repository;
    private final SettingsService settingsService;
    private final TransferMonitoringService monitoringService;
    private final ExecutorService ioExecutor;

    private final Object stateLock = new Object();
    private final LinkedHashMap<String, TransferRecord> recordsById = new LinkedHashMap<>();
    private final Set<String> recordedIds = ConcurrentHashMap.newKeySet();
    private final ConcurrentHashMap<String, SessionTiming> activeTimings = new ConcurrentHashMap<>();
    private final AtomicLong clearGeneration = new AtomicLong(0L);
    private final AtomicBoolean shutDown = new AtomicBoolean(false);
    private final AtomicBoolean listenerAttached = new AtomicBoolean(false);

    private final ObservableList<TransferRecord> records = FXCollections.observableArrayList();
    private final ObservableList<TransferRecord> unmodifiableRecords =
        FXCollections.unmodifiableObservableList(records);

    private final ReadOnlyBooleanWrapper loading = new ReadOnlyBooleanWrapper(this, "loading", false);
    private final ReadOnlyStringWrapper lastError = new ReadOnlyStringWrapper(this, "lastError", "");
    private final ReadOnlyStringWrapper warningMessage = new ReadOnlyStringWrapper(this, "warningMessage", "");

    public HistoryService() {
        this(new JsonTransferHistoryRepository(), null, null);
    }

    public HistoryService(SettingsService settingsService, TransferMonitoringService monitoringService) {
        this(new JsonTransferHistoryRepository(), settingsService, monitoringService);
    }

    public HistoryService(
        TransferHistoryRepository repository,
        SettingsService settingsService,
        TransferMonitoringService monitoringService
    ) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.settingsService = settingsService;
        this.monitoringService = monitoringService;
        this.ioExecutor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "syncshare-history-io");
            thread.setDaemon(true);
            return thread;
        });
        attachToMonitoring();
    }

    public final void attachToMonitoring() {
        if (monitoringService != null && !shutDown.get() && listenerAttached.compareAndSet(false, true)) {
            monitoringService.addTerminalListener(this);
        }
    }

    public void detachFromMonitoring() {
        if (monitoringService != null && listenerAttached.compareAndSet(true, false)) {
            monitoringService.removeTerminalListener(this);
        }
    }

    public ObservableList<TransferRecord> getRecords() {
        return unmodifiableRecords;
    }

    public List<TransferRecord> getSnapshot() {
        synchronized (stateLock) {
            return List.copyOf(sortedDescendingLocked());
        }
    }

    public ReadOnlyBooleanProperty loadingProperty() {
        return loading.getReadOnlyProperty();
    }

    public ReadOnlyStringProperty lastErrorProperty() {
        return lastError.getReadOnlyProperty();
    }

    public ReadOnlyStringProperty warningMessageProperty() {
        return warningMessage.getReadOnlyProperty();
    }

    public String getLastError() {
        return lastError.get();
    }

    public String getWarningMessage() {
        return warningMessage.get();
    }

    public Path getStorageLocation() {
        return repository.storageLocation();
    }

    public CompletableFuture<TransferHistoryRepository.LoadResult> loadAsync() {
        return runLoadAsync(false);
    }

    public TransferHistoryRepository.LoadResult loadSync() {
        try {
            return loadAsync().get();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return TransferHistoryRepository.LoadResult.corrupted("History load interrupted.");
        } catch (ExecutionException ex) {
            Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
            return TransferHistoryRepository.LoadResult.corrupted(safeErrorMessage(cause));
        }
    }

    public CompletableFuture<TransferHistoryRepository.LoadResult> refreshAsync() {
        return runLoadAsync(true);
    }

    public TransferHistoryRepository.LoadResult refreshSync() {
        try {
            return refreshAsync().get();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return TransferHistoryRepository.LoadResult.corrupted("History refresh interrupted.");
        } catch (ExecutionException ex) {
            Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
            return TransferHistoryRepository.LoadResult.corrupted(safeErrorMessage(cause));
        }
    }

    private CompletableFuture<TransferHistoryRepository.LoadResult> runLoadAsync(boolean replaceValidOnCleanLoad) {
        if (shutDown.get()) {
            return CompletableFuture.completedFuture(TransferHistoryRepository.LoadResult.empty());
        }
        long startEpoch = clearGeneration.get();
        runOnFxThread(() -> loading.set(true));

        return CompletableFuture.supplyAsync(() -> {
            try {
                TransferHistoryRepository.LoadResult result = repository.load();
                applyLoadResult(result, startEpoch, replaceValidOnCleanLoad);
                return result;
            } catch (Exception ex) {
                String error = "Failed to load transfer history: " + safeErrorMessage(ex);
                runOnFxThread(() -> {
                    loading.set(false);
                    lastError.set(error);
                });
                return TransferHistoryRepository.LoadResult.corrupted(error);
            }
        }, ioExecutor);
    }

    private void applyLoadResult(
        TransferHistoryRepository.LoadResult result,
        long startEpoch,
        boolean replaceValidOnCleanLoad
    ) {
        if (result.corrupted()) {
            String message = !result.warnings().isEmpty()
                ? result.warnings().get(0)
                : "Transfer history file is corrupted.";
            String detailed = message + " Previous valid records were preserved without overwriting.";
            runOnFxThread(() -> {
                loading.set(false);
                lastError.set(detailed);
            });
            return;
        }

        List<TransferRecord> updatedSnapshot;
        synchronized (stateLock) {
            if (clearGeneration.get() != startEpoch) {
                updatedSnapshot = sortedDescendingLocked();
            } else {
                if (replaceValidOnCleanLoad) {
                    recordsById.clear();
                    recordedIds.clear();
                }
                for (TransferRecord record : result.records()) {
                    if (recordedIds.add(record.transferId())) {
                        recordsById.put(record.transferId(), record);
                    } else if (!replaceValidOnCleanLoad) {
                        recordsById.putIfAbsent(record.transferId(), record);
                    }
                }
                updatedSnapshot = sortedDescendingLocked();
            }
        }

        String warning = result.skippedInvalidCount() > 0
            ? "Skipped " + result.skippedInvalidCount() + " invalid transfer history record(s)."
            : "";

        runOnFxThread(() -> {
            records.setAll(updatedSnapshot);
            warningMessage.set(warning);
            lastError.set("");
            loading.set(false);
        });
    }

    @Override
    public void onTransferTerminal(MonitoredTransfer snapshot) {
        if (snapshot == null || snapshot.state() == null || !snapshot.state().isTerminal()) {
            return;
        }
        TransferHistoryStatus status = TransferHistoryStatus.from(snapshot.state());
        TransferHistoryDirection direction = TransferHistoryDirection.from(snapshot.direction());
        Instant endTime = snapshot.completedAt() != null
            ? snapshot.completedAt()
            : (snapshot.updatedAt() != null ? snapshot.updatedAt() : Instant.now());
        Instant startTime = snapshot.startedAt() != null ? snapshot.startedAt() : endTime;
        Duration duration = Duration.between(startTime, endTime);
        if (duration.isNegative()) {
            duration = Duration.ZERO;
        }

        long size = snapshot.totalBytes() >= 0L ? snapshot.totalBytes() : TransferRecord.UNKNOWN_SIZE;
        String peer = !snapshot.peerAddress().isBlank() ? snapshot.peerAddress() : "Unknown Peer";
        String destination = resolveDestination(direction, status);
        String note = !snapshot.errorMessage().isBlank() ? snapshot.errorMessage() : snapshot.statusNote();

        TransferRecord record = new TransferRecord(
            snapshot.transferId(),
            snapshot.fileName(),
            size,
            direction,
            peer,
            endTime,
            duration,
            status,
            destination,
            note
        );
        recordInternalAsync(record);
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
        trackActiveSession(transferId, direction, fileName, totalBytes, peerAddress);
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
        trackActiveSession(transferId, direction, fileName, totalBytes, peerAddress);
    }

    @Override
    public void onProgress(
        String transferId,
        TransferDirection direction,
        String fileName,
        long transferredBytes,
        long totalBytes
    ) {
        if (transferId == null || transferId.isBlank()) {
            return;
        }
        activeTimings.compute(transferId, (id, existing) -> {
            if (existing == null) {
                return new SessionTiming(
                    Instant.now(),
                    safeFileName(fileName),
                    normalizeSize(totalBytes),
                    "Unknown Peer",
                    direction
                );
            }
            if (fileName != null && !fileName.isBlank()) {
                existing.fileName = fileName;
            }
            if (totalBytes >= 0L) {
                existing.totalBytes = totalBytes;
            }
            if (direction != null) {
                existing.direction = direction;
            }
            return existing;
        });
    }

    @Override
    public void onCompleted(String transferId, TransferDirection direction, String fileName) {
        recordSessionTerminal(transferId, direction, fileName, TransferHistoryStatus.COMPLETED, "Verified via SHA-256");
    }

    @Override
    public void onFailed(String transferId, TransferDirection direction, String fileName, String reason, Exception error) {
        String note = (reason != null && !reason.isBlank())
            ? reason
            : (error != null && error.getMessage() != null && !error.getMessage().isBlank()
                ? error.getMessage()
                : "Transfer failed");
        recordSessionTerminal(transferId, direction, fileName, TransferHistoryStatus.FAILED, note);
    }

    @Override
    public void onCancelled(String transferId, TransferDirection direction, String fileName, String reason) {
        String note = (reason != null && !reason.isBlank()) ? reason : "Cancelled by user";
        recordSessionTerminal(transferId, direction, fileName, TransferHistoryStatus.CANCELLED, note);
    }

    public boolean recordCompletedTransfer(TransferRecord record) {
        Objects.requireNonNull(record, "record");
        return recordInternalAsync(record) != null;
    }

    public CompletableFuture<OperationResult> recordTransferAsync(TransferRecord record) {
        Objects.requireNonNull(record, "record");
        CompletableFuture<OperationResult> future = recordInternalAsync(record);
        if (future == null) {
            return CompletableFuture.completedFuture(
                OperationResult.ok("Duplicate transferId '" + record.transferId() + "' ignored.")
            );
        }
        return future;
    }

    private CompletableFuture<OperationResult> recordInternalAsync(TransferRecord record) {
        if (shutDown.get()) {
            return CompletableFuture.completedFuture(OperationResult.error("History service is shut down."));
        }

        final List<TransferRecord> snapshotToSave;
        synchronized (stateLock) {
            if (!recordedIds.add(record.transferId())) {
                return null;
            }
            recordsById.put(record.transferId(), record);
            snapshotToSave = sortedDescendingLocked();
        }

        runOnFxThread(() -> records.setAll(snapshotToSave));

        return CompletableFuture.supplyAsync(() -> {
            try {
                repository.save(snapshotToSave);
                runOnFxThread(() -> lastError.set(""));
                return OperationResult.ok("Saved transfer record.");
            } catch (Exception ex) {
                String message = "Failed to save transfer history: " + safeErrorMessage(ex);
                runOnFxThread(() -> lastError.set(message));
                return OperationResult.error(message);
            }
        }, ioExecutor);
    }

    public CompletableFuture<OperationResult> clearHistoryAsync() {
        if (shutDown.get()) {
            return CompletableFuture.completedFuture(OperationResult.error("History service is shut down."));
        }
        runOnFxThread(() -> loading.set(true));

        return CompletableFuture.supplyAsync(() -> {
            try {
                repository.clear();
                synchronized (stateLock) {
                    clearGeneration.incrementAndGet();
                    recordsById.clear();
                    recordedIds.clear();
                    activeTimings.clear();
                }
                runOnFxThread(() -> {
                    records.clear();
                    lastError.set("");
                    warningMessage.set("");
                    loading.set(false);
                });
                return OperationResult.ok("Transfer history cleared.");
            } catch (Exception ex) {
                String error = "Failed to clear transfer history: " + safeErrorMessage(ex)
                    + " Previous valid history has been preserved.";
                runOnFxThread(() -> {
                    loading.set(false);
                    lastError.set(error);
                });
                return OperationResult.error(error);
            }
        }, ioExecutor);
    }

    public OperationResult clearHistorySync() {
        try {
            return clearHistoryAsync().get();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return OperationResult.error("Clear history interrupted.");
        } catch (ExecutionException ex) {
            Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
            return OperationResult.error(safeErrorMessage(cause));
        }
    }

    public List<TransferRecord> searchByFileName(String query) {
        return query(query, null, SortOrder.NEWEST_FIRST);
    }

    public List<TransferRecord> filterByStatus(TransferHistoryStatus status) {
        return query("", status, SortOrder.NEWEST_FIRST);
    }

    public List<TransferRecord> sortByDate(List<TransferRecord> input, SortOrder sortOrder) {
        Objects.requireNonNull(input, "input");
        List<TransferRecord> sorted = new ArrayList<>(input);
        sorted.sort(comparatorFor(sortOrder));
        return List.copyOf(sorted);
    }

    public List<TransferRecord> query(String fileNameQuery, TransferHistoryStatus statusFilter, SortOrder sortOrder) {
        List<TransferRecord> snapshot = getSnapshot();
        List<TransferRecord> filtered = new ArrayList<>();
        for (TransferRecord record : snapshot) {
            if (matchesFilter(record, fileNameQuery, statusFilter)) {
                filtered.add(record);
            }
        }
        filtered.sort(comparatorFor(sortOrder));
        return List.copyOf(filtered);
    }

    public static boolean matchesFilter(
        TransferRecord record,
        String fileNameQuery,
        TransferHistoryStatus statusFilter
    ) {
        if (record == null) {
            return false;
        }
        if (statusFilter != null && record.status() != statusFilter) {
            return false;
        }
        if (fileNameQuery == null || fileNameQuery.isBlank()) {
            return true;
        }
        String needle = fileNameQuery.trim().toLowerCase(Locale.ROOT);
        return record.fileName().toLowerCase(Locale.ROOT).contains(needle);
    }

    public static Comparator<TransferRecord> comparatorFor(SortOrder sortOrder) {
        SortOrder resolved = sortOrder != null ? sortOrder : SortOrder.NEWEST_FIRST;
        Comparator<TransferRecord> byDate = Comparator.comparing(TransferRecord::transferredAt);
        if (resolved == SortOrder.NEWEST_FIRST) {
            byDate = byDate.reversed();
        }
        return byDate.thenComparing(TransferRecord::transferId);
    }

    public boolean awaitPendingIo(long timeout, TimeUnit unit) {
        if (shutDown.get()) {
            return true;
        }
        try {
            CompletableFuture.runAsync(() -> {}, ioExecutor).get(timeout, unit);
            return true;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return false;
        } catch (ExecutionException | TimeoutException | CompletionException ex) {
            return false;
        }
    }

    public void shutdown() {
        if (!shutDown.compareAndSet(false, true)) {
            return;
        }
        detachFromMonitoring();
        activeTimings.clear();
        ioExecutor.shutdown();
        try {
            if (!ioExecutor.awaitTermination(3, TimeUnit.SECONDS)) {
                ioExecutor.shutdownNow();
            }
        } catch (InterruptedException ex) {
            ioExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    private void trackActiveSession(
        String transferId,
        TransferDirection direction,
        String fileName,
        long totalBytes,
        String peerAddress
    ) {
        if (transferId == null || transferId.isBlank()) {
            return;
        }
        activeTimings.compute(transferId, (id, existing) -> {
            if (existing == null) {
                return new SessionTiming(
                    Instant.now(),
                    safeFileName(fileName),
                    normalizeSize(totalBytes),
                    safePeer(peerAddress),
                    direction
                );
            }
            if (fileName != null && !fileName.isBlank()) {
                existing.fileName = fileName;
            }
            if (totalBytes >= 0L) {
                existing.totalBytes = totalBytes;
            }
            if (peerAddress != null && !peerAddress.isBlank()) {
                existing.peerInfo = peerAddress;
            }
            if (direction != null) {
                existing.direction = direction;
            }
            return existing;
        });
    }

    private void recordSessionTerminal(
        String transferId,
        TransferDirection direction,
        String fileName,
        TransferHistoryStatus status,
        String note
    ) {
        if (transferId == null || transferId.isBlank()) {
            return;
        }
        SessionTiming timing = activeTimings.remove(transferId);
        Instant now = Instant.now();
        Instant startedAt = timing != null && timing.startedAt != null ? timing.startedAt : now;
        Duration duration = Duration.between(startedAt, now);
        if (duration.isNegative()) {
            duration = Duration.ZERO;
        }

        TransferDirection resolvedDirection = direction != null
            ? direction
            : (timing != null && timing.direction != null ? timing.direction : TransferDirection.OUTGOING);
        TransferHistoryDirection historyDirection = TransferHistoryDirection.from(resolvedDirection);
        String resolvedName = (fileName != null && !fileName.isBlank() && !"unknown".equals(fileName))
            ? fileName
            : (timing != null ? timing.fileName : "unknown");
        long resolvedSize = timing != null ? timing.totalBytes : TransferRecord.UNKNOWN_SIZE;
        if (status == TransferHistoryStatus.COMPLETED && resolvedSize < 0L) {
            resolvedSize = 0L;
        }
        String resolvedPeer = timing != null ? timing.peerInfo : "Unknown Peer";
        String destination = resolveDestination(historyDirection, status);

        TransferRecord record = new TransferRecord(
            transferId,
            resolvedName,
            resolvedSize,
            historyDirection,
            resolvedPeer,
            now,
            duration,
            status,
            destination,
            note
        );
        recordInternalAsync(record);
    }

    private String resolveDestination(TransferHistoryDirection direction, TransferHistoryStatus status) {
        if (direction != TransferHistoryDirection.RECEIVE || status != TransferHistoryStatus.COMPLETED) {
            return "";
        }
        String rawDir = settingsService != null
            ? settingsService.getSettings().receiveDirectory()
            : System.getProperty("syncshare.receiveDir", "received_files");
        return TransferRecord.sanitizeDestination(rawDir);
    }

    private List<TransferRecord> sortedDescendingLocked() {
        List<TransferRecord> list = new ArrayList<>(recordsById.values());
        list.sort(comparatorFor(SortOrder.NEWEST_FIRST));
        return list;
    }

    private static String safeFileName(String name) {
        return (name != null && !name.isBlank()) ? name : "unknown";
    }

    private static String safePeer(String peer) {
        return (peer != null && !peer.isBlank()) ? peer : "Unknown Peer";
    }

    private static long normalizeSize(long totalBytes) {
        return totalBytes >= 0L ? totalBytes : TransferRecord.UNKNOWN_SIZE;
    }

    private static String safeErrorMessage(Throwable error) {
        if (error == null) {
            return "Unknown I/O error";
        }
        String msg = error.getMessage();
        return (msg != null && !msg.isBlank()) ? msg : error.getClass().getSimpleName();
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
