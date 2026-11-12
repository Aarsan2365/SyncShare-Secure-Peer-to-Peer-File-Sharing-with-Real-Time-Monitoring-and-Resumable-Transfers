package com.syncshare.history;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JsonTransferHistoryRepositoryTest {

    @TempDir
    Path tempDir;

    private static TransferRecord sampleRecord(String id, String fileName, TransferHistoryStatus status) {
        return new TransferRecord(
            id,
            fileName,
            1024L,
            TransferHistoryDirection.SEND,
            "192.168.1.10:5050",
            Instant.parse("2026-11-12T12:00:00Z"),
            Duration.ofMillis(1450),
            status,
            status == TransferHistoryStatus.COMPLETED ? "received_files" : "",
            "note with \"quotes\" and \\backslash\\"
        );
    }

    @Test
    void missingFileLoadsAsEmptyWithoutCreatingDiskFile() throws IOException {
        Path historyPath = tempDir.resolve("nested").resolve("transfer-history.json");
        JsonTransferHistoryRepository repository = new JsonTransferHistoryRepository(historyPath);

        TransferHistoryRepository.LoadResult result = repository.load();

        assertFalse(result.corrupted());
        assertEquals(0, result.skippedInvalidCount());
        assertTrue(result.records().isEmpty());
        assertFalse(Files.exists(historyPath));
    }

    @Test
    void saveAndLoadRoundTripPreservesAllFieldsAndZeroByteTransfers() throws IOException {
        Path historyPath = tempDir.resolve("transfer-history.json");
        JsonTransferHistoryRepository repository = new JsonTransferHistoryRepository(historyPath);

        TransferRecord zeroByte = new TransferRecord(
            "tx-zero",
            "empty.txt",
            0L,
            TransferHistoryDirection.RECEIVE,
            "10.0.0.8:5050",
            Instant.parse("2026-11-12T08:30:15.250Z"),
            Duration.ZERO,
            TransferHistoryStatus.COMPLETED,
            "SyncShareDownloads",
            "SHA-256 verified"
        );
        TransferRecord failed = sampleRecord("tx-fail", "draft \"v2\".pdf", TransferHistoryStatus.FAILED);
        TransferRecord cancelled = new TransferRecord(
            "tx-cancel",
            "clip.mp4",
            TransferRecord.UNKNOWN_SIZE,
            TransferHistoryDirection.SEND,
            "10.0.0.9:5050",
            Instant.parse("2026-11-12T09:45:00Z"),
            Duration.ofSeconds(12),
            TransferHistoryStatus.CANCELLED,
            "",
            "Cancelled by user"
        );

        repository.save(List.of(zeroByte, failed, cancelled));
        assertTrue(Files.exists(historyPath));

        TransferHistoryRepository.LoadResult loaded = repository.load();
        assertFalse(loaded.corrupted());
        assertEquals(0, loaded.skippedInvalidCount());
        assertEquals(List.of(zeroByte, failed, cancelled), loaded.records());
    }

    @Test
    void corruptedJsonReportsCorruptionAndNeverOverwritesExistingFile() throws IOException {
        Path historyPath = tempDir.resolve("transfer-history.json");
        String malformed = "{ \"schemaVersion\": 1, \"records\": [ { broken json ";
        Files.writeString(historyPath, malformed, StandardCharsets.UTF_8);

        JsonTransferHistoryRepository repository = new JsonTransferHistoryRepository(historyPath);
        TransferHistoryRepository.LoadResult result = repository.load();

        assertTrue(result.corrupted());
        assertTrue(result.records().isEmpty());
        assertTrue(result.hasWarnings());
        assertEquals(malformed, Files.readString(historyPath, StandardCharsets.UTF_8));
    }

    @Test
    void partiallyInvalidRecordsAreSkippedWhileValidRecordsLoadSafely() throws IOException {
        Path historyPath = tempDir.resolve("transfer-history.json");
        String mixedJson = """
            {
              "schemaVersion": 1,
              "records": [
                {
                  "transferId": "tx-valid-1",
                  "fileName": "valid.pdf",
                  "fileSize": 512,
                  "direction": "SEND",
                  "peerInfo": "192.168.1.5:5050",
                  "transferredAt": "2026-11-12T10:00:00Z",
                  "durationMillis": 400,
                  "status": "COMPLETED",
                  "destination": "",
                  "summaryNote": "ok"
                },
                {
                  "transferId": "tx-bad-status",
                  "fileName": "running.bin",
                  "fileSize": 100,
                  "direction": "SEND",
                  "peerInfo": "192.168.1.5:5050",
                  "transferredAt": "2026-11-12T10:01:00Z",
                  "durationMillis": 100,
                  "status": "RUNNING"
                },
                "not-an-object",
                {
                  "transferId": "tx-valid-1",
                  "fileName": "duplicate-id.pdf",
                  "fileSize": 512,
                  "direction": "SEND",
                  "peerInfo": "192.168.1.5:5050",
                  "transferredAt": "2026-11-12T10:02:00Z",
                  "durationMillis": 400,
                  "status": "COMPLETED"
                },
                {
                  "transferId": "tx-valid-2",
                  "fileName": "photo.png",
                  "fileSize": 2048,
                  "direction": "RECEIVE",
                  "peerInfo": "192.168.1.6:5050",
                  "transferredAt": "2026-11-12T10:03:00Z",
                  "durationMillis": 900,
                  "status": "CANCELLED",
                  "destination": "received_files",
                  "summaryNote": "Cancelled by user"
                }
              ]
            }
            """;
        Files.writeString(historyPath, mixedJson, StandardCharsets.UTF_8);

        JsonTransferHistoryRepository repository = new JsonTransferHistoryRepository(historyPath);
        TransferHistoryRepository.LoadResult result = repository.load();

        assertFalse(result.corrupted());
        assertEquals(3, result.skippedInvalidCount());
        assertEquals(3, result.warnings().size());
        assertEquals(2, result.records().size());
        assertEquals("tx-valid-1", result.records().get(0).transferId());
        assertEquals("tx-valid-2", result.records().get(1).transferId());
    }

    @Test
    void failedAtomicWritePreservesPreviousValidHistoryAndCleansTempFiles() throws IOException {
        Path historyPath = tempDir.resolve("transfer-history.json");
        TransferRecord original = sampleRecord("tx-original", "original.zip", TransferHistoryStatus.COMPLETED);

        JsonTransferHistoryRepository baselineRepo = new JsonTransferHistoryRepository(historyPath);
        baselineRepo.save(List.of(original));

        JsonTransferHistoryRepository failingRepo = new JsonTransferHistoryRepository(historyPath) {
            @Override
            protected void moveTempToTarget(Path tempFile, Path targetFile) throws IOException {
                throw new IOException("Simulated atomic move failure");
            }
        };

        TransferRecord replacement = sampleRecord("tx-new", "replacement.zip", TransferHistoryStatus.FAILED);
        assertThrows(IOException.class, () -> failingRepo.save(List.of(replacement)));

        TransferHistoryRepository.LoadResult afterFailure = baselineRepo.load();
        assertFalse(afterFailure.corrupted());
        assertEquals(List.of(original), afterFailure.records());

        try (Stream<Path> files = Files.list(tempDir)) {
            List<Path> leftovers = files
                .filter(p -> p.getFileName().toString().endsWith(".tmp"))
                .toList();
            assertTrue(leftovers.isEmpty(), "Temp files should be removed after failed save");
        }
    }

    @Test
    void concurrentSavesMaintainConsistentReadableJsonAndClearEmptiesHistory() throws Exception {
        Path historyPath = tempDir.resolve("transfer-history.json");
        JsonTransferHistoryRepository repository = new JsonTransferHistoryRepository(historyPath);

        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            List<Callable<Void>> writers = new ArrayList<>();
            for (int t = 0; t < 16; t++) {
                int index = t;
                writers.add(() -> {
                    List<TransferRecord> batch = List.of(
                        sampleRecord("tx-concurrent-" + index, "file-" + index + ".bin", TransferHistoryStatus.COMPLETED)
                    );
                    repository.save(batch);
                    return null;
                });
            }
            List<Future<Void>> futures = pool.invokeAll(writers);
            for (Future<Void> future : futures) {
                future.get();
            }
        } finally {
            pool.shutdownNow();
        }

        TransferHistoryRepository.LoadResult result = repository.load();
        assertFalse(result.corrupted());
        assertEquals(1, result.records().size());
        assertTrue(result.records().get(0).transferId().startsWith("tx-concurrent-"));

        repository.clear();
        TransferHistoryRepository.LoadResult cleared = repository.load();
        assertFalse(cleared.corrupted());
        assertTrue(cleared.records().isEmpty());
    }

    @Test
    void resolvesSystemPropertyOverridesPredictably() {
        String prevFile = System.getProperty(JsonTransferHistoryRepository.PROP_HISTORY_FILE);
        String prevDir = System.getProperty(JsonTransferHistoryRepository.PROP_DATA_DIR);
        try {
            Path customDir = tempDir.resolve("custom-data");
            System.clearProperty(JsonTransferHistoryRepository.PROP_HISTORY_FILE);
            System.setProperty(JsonTransferHistoryRepository.PROP_DATA_DIR, customDir.toString());
            assertEquals(
                customDir.resolve(JsonTransferHistoryRepository.HISTORY_FILE_NAME).toAbsolutePath().normalize(),
                JsonTransferHistoryRepository.resolveDefaultHistoryFile()
            );

            Path explicitFile = tempDir.resolve("explicit-history.json");
            System.setProperty(JsonTransferHistoryRepository.PROP_HISTORY_FILE, explicitFile.toString());
            assertEquals(
                explicitFile.toAbsolutePath().normalize(),
                JsonTransferHistoryRepository.resolveDefaultHistoryFile()
            );
        } finally {
            restoreProp(JsonTransferHistoryRepository.PROP_HISTORY_FILE, prevFile);
            restoreProp(JsonTransferHistoryRepository.PROP_DATA_DIR, prevDir);
        }
    }

    private static void restoreProp(String key, String previous) {
        if (previous == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, previous);
        }
    }
}
