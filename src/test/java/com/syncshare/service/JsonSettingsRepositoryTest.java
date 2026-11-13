package com.syncshare.service;

import com.syncshare.model.AppSettings;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JsonSettingsRepositoryTest {

    @Test
    @DisplayName("Missing settings file returns defaults without modifying disk")
    void testMissingFileReturnsDefaults(@TempDir Path tempDir) throws IOException {
        Path missingFile = tempDir.resolve("non_existent_settings.json");
        JsonSettingsRepository repo = new JsonSettingsRepository(missingFile);

        SettingsRepository.LoadResult result = repo.load();

        assertNotNull(result.settings());
        assertTrue(result.fallbackToDefaults());
        assertFalse(result.corrupted());
        assertFalse(Files.exists(missingFile), "Missing file must not be created on read");
    }

    @Test
    @DisplayName("Settings round-trip persists all fields accurately")
    void testSaveAndLoadRoundTrip(@TempDir Path tempDir) throws IOException {
        Path settingsFile = tempDir.resolve("settings.json");
        JsonSettingsRepository repo = new JsonSettingsRepository(settingsFile);

        AppSettings custom = new AppSettings(
            "Node-Alpha",
            5090,
            tempDir.resolve("inbox").toString(),
            tempDir.resolve("downloads").toString(),
            false,
            true,
            true,
            true,
            false,
            true,
            false,
            false,
            "SYSTEM"
        );

        repo.save(custom);
        assertTrue(Files.exists(settingsFile), "Settings file should be created on save");

        SettingsRepository.LoadResult result = repo.load();
        assertFalse(result.fallbackToDefaults());
        assertFalse(result.corrupted());

        AppSettings loaded = result.settings();
        assertEquals("Node-Alpha", loaded.deviceAlias());
        assertEquals(5090, loaded.listeningPort());
        assertEquals(tempDir.resolve("inbox").toString(), loaded.receiveDirectory());
        assertEquals(tempDir.resolve("downloads").toString(), loaded.downloadDirectory());
        assertFalse(loaded.requireApproval());
        assertTrue(loaded.verifySha256());
        assertTrue(loaded.autoStartReceiver());
        assertTrue(loaded.startMinimized());
        assertFalse(loaded.notificationsEnabled());
        assertTrue(loaded.notifyOnTransferComplete());
        assertFalse(loaded.notifyOnIncomingRequest());
        assertFalse(loaded.soundEnabled());
        assertEquals("SYSTEM", loaded.theme());
    }

    @Test
    @DisplayName("Corrupted settings file falls back to defaults and preserves corrupted file on disk")
    void testCorruptedFileHandling(@TempDir Path tempDir) throws IOException {
        Path corruptedFile = tempDir.resolve("settings.json");
        String badContent = "{\n  \"deviceAlias\": \"Truncated-Json\",\n  \"listeningPort\": ";
        Files.writeString(corruptedFile, badContent);

        JsonSettingsRepository repo = new JsonSettingsRepository(corruptedFile);
        SettingsRepository.LoadResult result = repo.load();

        assertTrue(result.corrupted(), "Must be marked as corrupted");
        assertTrue(result.fallbackToDefaults(), "Must fall back to defaults");
        assertNotNull(result.settings());
        assertEquals(badContent, Files.readString(corruptedFile), "Corrupted file on disk must not be altered on load");
    }

    @Test
    @DisplayName("Partially invalid settings preserves valid fields and substitutes defaults for invalid ones")
    void testPartialSettingsRecovery(@TempDir Path tempDir) throws IOException {
        Path partialFile = tempDir.resolve("settings.json");
        String partialJson = """
            {
              "schemaVersion": 1,
              "deviceAlias": "Recovered-Node",
              "listeningPort": 999999,
              "autoStartReceiver": true
            }
            """;
        Files.writeString(partialFile, partialJson);

        JsonSettingsRepository repo = new JsonSettingsRepository(partialFile);
        SettingsRepository.LoadResult result = repo.load();

        assertFalse(result.corrupted());
        assertTrue(result.fallbackToDefaults()); // has warnings about port fallback
        AppSettings loaded = result.settings();
        assertEquals("Recovered-Node", loaded.deviceAlias());
        // Port 999999 is invalid -> should fall back to default port 5050
        assertEquals(AppSettings.DEFAULT_PORT, loaded.listeningPort());
        assertTrue(loaded.autoStartReceiver());
        // Non-present fields should use defaults
        assertTrue(loaded.requireApproval());
    }

    @Test
    @DisplayName("Failed write preserves existing valid settings file")
    void testFailedWritePreservesExistingFile(@TempDir Path tempDir) throws IOException {
        Path validFile = tempDir.resolve("settings.json");
        JsonSettingsRepository repo = new JsonSettingsRepository(validFile);

        AppSettings validSettings = new AppSettings(
            "Valid-Host",
            5050,
            tempDir.resolve("recv").toString(),
            tempDir.resolve("dl").toString(),
            true,
            true,
            false,
            false,
            true,
            true,
            true,
            true,
            "DEFAULT"
        );
        repo.save(validSettings);
        String validDiskContent = Files.readString(validFile);

        // Subclass repository that simulates I/O error during move
        JsonSettingsRepository failingRepo = new JsonSettingsRepository(validFile) {
            @Override
            protected void moveTempToTarget(Path tempFile, Path targetFile) throws IOException {
                throw new IOException("Simulated disk error during atomic move");
            }
        };

        AppSettings nextSettings = validSettings.withDeviceAlias("Should-Fail-Update");
        try {
            failingRepo.save(nextSettings);
        } catch (IOException expected) {
            // expected failure
        }

        // Verify valid file was NOT modified
        assertEquals(validDiskContent, Files.readString(validFile), "Previous valid settings file must be preserved");
    }

    @Test
    @DisplayName("No credentials, passwords, or private keys are serialized in JSON settings")
    void testNoSensitiveCredentialsInJson(@TempDir Path tempDir) throws IOException {
        Path file = tempDir.resolve("settings.json");
        JsonSettingsRepository repo = new JsonSettingsRepository(file);
        repo.save(AppSettings.defaults());

        String json = Files.readString(file);
        assertFalse(json.toLowerCase().contains("password"), "JSON must not contain password field");
        assertFalse(json.toLowerCase().contains("privatekey"), "JSON must not contain privateKey field");
        assertFalse(json.toLowerCase().contains("secret"), "JSON must not contain secret field");
        assertFalse(json.toLowerCase().contains("token"), "JSON must not contain token field");
    }

    @Test
    @DisplayName("Concurrent reads and writes operate safely without file corruption")
    void testConcurrentAccess(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("settings.json");
        JsonSettingsRepository repo = new JsonSettingsRepository(file);
        repo.save(AppSettings.defaults());

        int threads = 8;
        int operationsPerThread = 25;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch latch = new CountDownLatch(threads);
        AtomicInteger failures = new AtomicInteger(0);

        for (int t = 0; t < threads; t++) {
            final int threadId = t;
            executor.submit(() -> {
                try {
                    for (int i = 0; i < operationsPerThread; i++) {
                        if (threadId % 2 == 0) {
                            repo.save(AppSettings.defaults().withDeviceAlias("Worker-" + threadId + "-" + i));
                        } else {
                            SettingsRepository.LoadResult res = repo.load();
                            assertNotNull(res.settings());
                        }
                    }
                } catch (Exception ex) {
                    failures.incrementAndGet();
                } finally {
                    latch.countDown();
                }
            });
        }

        assertTrue(latch.await(10, TimeUnit.SECONDS));
        executor.shutdown();
        assertEquals(0, failures.get(), "No concurrent access failures should occur");

        SettingsRepository.LoadResult finalLoad = repo.load();
        assertFalse(finalLoad.corrupted(), "Final file should not be corrupted");
        assertNotNull(finalLoad.settings());
    }
}
