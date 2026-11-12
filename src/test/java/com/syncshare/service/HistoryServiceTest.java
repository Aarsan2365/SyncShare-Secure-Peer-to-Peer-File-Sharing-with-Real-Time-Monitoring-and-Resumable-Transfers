package com.syncshare.service;

import com.syncshare.history.JsonTransferHistoryRepository;
import com.syncshare.history.TransferHistoryDirection;
import com.syncshare.history.TransferHistoryRepository;
import com.syncshare.history.TransferHistoryStatus;
import com.syncshare.history.TransferRecord;
import com.syncshare.model.AppSettings;
import com.syncshare.model.TransferDirection;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HistoryServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void recordsOnlyTerminalOutcomesAndPreventsDuplicateEvents() throws Exception {
        Path historyFile = tempDir.resolve("transfer-history.json");
        JsonTransferHistoryRepository repo = new JsonTransferHistoryRepository(historyFile);
        SettingsService settings = new SettingsService();
        settings.updateSettings(new AppSettings(
            "Node-A",
            5050,
            tempDir.resolve("user-downloads").resolve("SyncShare").toString(),
            true,
            true,
            false
        ));
        TransferMonitoringService monitoring = new TransferMonitoringService(System::nanoTime, 0L, false);
        HistoryService history = new HistoryService(repo, settings, monitoring);

        try {
            monitoring.registerQueued("tx-active", TransferDirection.OUTGOING, "queued.bin", 5000L, "10.0.0.2:5050", "Queued");
            monitoring.onConnecting("tx-active", TransferDirection.OUTGOING, "queued.bin", 5000L, "10.0.0.2:5050", "Connecting");
            monitoring.onStarted("tx-active", TransferDirection.OUTGOING, "queued.bin", 5000L, 0L, "10.0.0.2:5050");
            monitoring.onProgress("tx-active", TransferDirection.OUTGOING, "queued.bin", 2500L, 5000L);
            assertTrue(history.getSnapshot().isEmpty(), "Non-terminal transfers must never enter history");

            // Completed incoming zero-byte transfer
            monitoring.onStarted("tx-complete", TransferDirection.INCOMING, "empty.txt", 0L, 0L, "10.0.0.5:5050");
            monitoring.onCompleted("tx-complete", TransferDirection.INCOMING, "empty.txt");
            // Duplicate terminal notifications for same ID
            monitoring.onCompleted("tx-complete", TransferDirection.INCOMING, "empty.txt");

            // Failed transfer
            monitoring.onConnecting("tx-fail", TransferDirection.OUTGOING, "broken.iso", 1024L, "10.0.0.6:5050", "Connecting");
            monitoring.onFailed("tx-fail", TransferDirection.OUTGOING, "broken.iso", "TLS peer disconnected", null);
            monitoring.onFailed("tx-fail", TransferDirection.OUTGOING, "broken.iso", "Repeated error", null);

            // Cancelled transfer
            monitoring.onStarted("tx-cancel", TransferDirection.OUTGOING, "video.mov", 2048L, 128L, "10.0.0.7:5050");
            assertTrue(monitoring.cancelTransfer("tx-cancel"));
            monitoring.onCancelled("tx-cancel", TransferDirection.OUTGOING, "video.mov", "Cancelled later");

            assertTrue(history.awaitPendingIo(5, TimeUnit.SECONDS));

            List<TransferRecord> recorded = history.getSnapshot();
            assertEquals(3, recorded.size());

            TransferRecord completed = recorded.stream()
                .filter(r -> "tx-complete".equals(r.transferId()))
                .findFirst()
                .orElseThrow();
            assertEquals(TransferHistoryStatus.COMPLETED, completed.status());
            assertEquals(TransferHistoryDirection.RECEIVE, completed.direction());
            assertEquals(0L, completed.fileSize());
            assertEquals("SyncShare", completed.destination());
            assertFalse(completed.destination().contains("user-downloads"));

            TransferRecord failed = recorded.stream()
                .filter(r -> "tx-fail".equals(r.transferId()))
                .findFirst()
                .orElseThrow();
            assertEquals(TransferHistoryStatus.FAILED, failed.status());
            assertEquals("TLS peer disconnected", failed.summaryNote());

            TransferRecord cancelled = recorded.stream()
                .filter(r -> "tx-cancel".equals(r.transferId()))
                .findFirst()
                .orElseThrow();
            assertEquals(TransferHistoryStatus.CANCELLED, cancelled.status());

            // Verify duplicate transferCompleted call directly on HistoryService is rejected idempotently
            assertFalse(history.recordCompletedTransfer(completed));
        } finally {
            monitoring.shutdown();
            history.shutdown();
        }
    }

    @Test
    void persistsHistoryAcrossServiceRestartsAndSupportsSearchFilterAndSort() throws Exception {
        Path historyFile = tempDir.resolve("transfer-history.json");
        JsonTransferHistoryRepository repo1 = new JsonTransferHistoryRepository(historyFile);
        TransferMonitoringService monitoring1 = new TransferMonitoringService(System::nanoTime, 0L, false);
        HistoryService service1 = new HistoryService(repo1, null, monitoring1);

        TransferRecord older = new TransferRecord(
            "tx-old",
            "alpha-notes.txt",
            512L,
            TransferHistoryDirection.SEND,
            "192.168.1.2:5050",
            Instant.parse("2026-11-12T08:00:00Z"),
            Duration.ofSeconds(1),
            TransferHistoryStatus.COMPLETED,
            ""
        );
        TransferRecord middle = new TransferRecord(
            "tx-mid",
            "beta-archive.zip",
            4096L,
            TransferHistoryDirection.RECEIVE,
            "192.168.1.3:5050",
            Instant.parse("2026-11-12T09:00:00Z"),
            Duration.ofSeconds(3),
            TransferHistoryStatus.FAILED,
            ""
        );
        TransferRecord newer = new TransferRecord(
            "tx-new",
            "alpha-report.pdf",
            8192L,
            TransferHistoryDirection.SEND,
            "192.168.1.4:5050",
            Instant.parse("2026-11-12T10:00:00Z"),
            Duration.ofSeconds(5),
            TransferHistoryStatus.CANCELLED,
            ""
        );

        service1.recordTransferAsync(older).get(5, TimeUnit.SECONDS);
        service1.recordTransferAsync(middle).get(5, TimeUnit.SECONDS);
        service1.recordTransferAsync(newer).get(5, TimeUnit.SECONDS);
        service1.shutdown();
        monitoring1.shutdown();
        assertEquals(0, monitoring1.terminalListenerCount());

        // Simulate fresh application restart
        JsonTransferHistoryRepository repo2 = new JsonTransferHistoryRepository(historyFile);
        TransferMonitoringService monitoring2 = new TransferMonitoringService(System::nanoTime, 0L, false);
        HistoryService service2 = new HistoryService(repo2, null, monitoring2);
        try {
            TransferHistoryRepository.LoadResult loadResult = service2.loadSync();
            assertFalse(loadResult.corrupted());
            assertEquals(3, service2.getSnapshot().size());

            // Search by filename
            assertEquals(2, service2.searchByFileName("ALPHA").size());
            assertEquals(0, service2.searchByFileName("missing-file").size());

            // Filter by status
            assertEquals(1, service2.filterByStatus(TransferHistoryStatus.COMPLETED).size());
            assertEquals(1, service2.filterByStatus(TransferHistoryStatus.FAILED).size());
            assertEquals(1, service2.filterByStatus(TransferHistoryStatus.CANCELLED).size());

            // Date sorting
            List<TransferRecord> newestFirst = service2.query("", null, HistoryService.SortOrder.NEWEST_FIRST);
            assertEquals(List.of("tx-new", "tx-mid", "tx-old"), newestFirst.stream().map(TransferRecord::transferId).toList());

            List<TransferRecord> oldestFirst = service2.query("", null, HistoryService.SortOrder.OLDEST_FIRST);
            assertEquals(List.of("tx-old", "tx-mid", "tx-new"), oldestFirst.stream().map(TransferRecord::transferId).toList());
        } finally {
            monitoring2.shutdown();
            service2.shutdown();
        }
    }

    @Test
    void failedClearAndCorruptedRefreshPreservePreviousValidHistoryAndMonitoringState() throws Exception {
        Path historyFile = tempDir.resolve("transfer-history.json");
        TransferRecord saved = new TransferRecord(
            "tx-keep",
            "important.db",
            2048L,
            TransferHistoryDirection.SEND,
            "192.168.1.50:5050",
            Instant.parse("2026-11-12T11:00:00Z"),
            Duration.ofSeconds(2),
            TransferHistoryStatus.COMPLETED,
            ""
        );

        JsonTransferHistoryRepository baseRepo = new JsonTransferHistoryRepository(historyFile);
        baseRepo.save(List.of(saved));

        JsonTransferHistoryRepository failClearRepo = new JsonTransferHistoryRepository(historyFile) {
            @Override
            public void clear() throws IOException {
                throw new IOException("Read-only storage failure");
            }
        };

        TransferMonitoringService monitoring = new TransferMonitoringService(System::nanoTime, 0L, false);
        monitoring.onStarted("tx-live", TransferDirection.OUTGOING, "live.bin", 1000L, 100L, "192.168.1.9:5050");
        HistoryService history = new HistoryService(failClearRepo, null, monitoring);

        try {
            history.loadSync();
            assertEquals(1, history.getSnapshot().size());

            HistoryService.OperationResult clearResult = history.clearHistorySync();
            assertFalse(clearResult.success());
            assertTrue(history.getLastError().contains("Read-only storage failure"));
            assertEquals(1, history.getSnapshot().size(), "In-memory valid history must remain intact when clear fails");
            assertEquals(1, monitoring.getActiveSnapshot().size(), "Active monitoring transfers must stay untouched");

            // Corrupt the file on disk and refresh -> valid in-memory history must still be preserved
            Files.writeString(historyFile, "{ invalid json", StandardCharsets.UTF_8);
            TransferHistoryRepository.LoadResult corruptedRefresh = history.refreshSync();
            assertTrue(corruptedRefresh.corrupted());
            assertFalse(history.getLastError().isBlank());
            assertEquals(1, history.getSnapshot().size());
            assertEquals("tx-keep", history.getSnapshot().get(0).transferId());
        } finally {
            monitoring.shutdown();
            history.shutdown();
        }
    }
}
