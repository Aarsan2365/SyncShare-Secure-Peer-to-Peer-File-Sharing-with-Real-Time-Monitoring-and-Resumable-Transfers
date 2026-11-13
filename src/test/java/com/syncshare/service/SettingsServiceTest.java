package com.syncshare.service;

import com.syncshare.model.AppSettings;
import com.syncshare.ui.JavaFxToolkitExtension;
import javafx.application.Platform;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(JavaFxToolkitExtension.class)
class SettingsServiceTest {

    @Test
    @DisplayName("SettingsService loads existing settings from repository on initialization")
    void testInitialLoadFromRepository(@TempDir Path tempDir) throws Throwable {
        Path file = tempDir.resolve("settings.json");
        JsonSettingsRepository repo = new JsonSettingsRepository(file);
        AppSettings custom = new AppSettings(
            "Preloaded-Station",
            5200,
            tempDir.resolve("custom-recv").toString(),
            false,
            true,
            true
        );
        repo.save(custom);

        JavaFxToolkitExtension.runOnFxThread(() -> {
            SettingsService service = new SettingsService(repo);
            assertEquals("Preloaded-Station", service.getSettings().deviceAlias());
            assertEquals(5200, service.getSettings().listeningPort());
            assertEquals(tempDir.resolve("custom-recv").toString(), service.getSettings().receiveDirectory());
            assertTrue(service.getSettings().autoStartReceiver());
            assertFalse(service.getSettings().requireApproval());
            service.shutdown();
        });
    }

    @Test
    @DisplayName("updateSettings persists changes and updates property")
    void testUpdateSettingsPersists(@TempDir Path tempDir) throws Throwable {
        Path file = tempDir.resolve("settings.json");
        JsonSettingsRepository repo = new JsonSettingsRepository(file);

        JavaFxToolkitExtension.runOnFxThread(() -> {
            SettingsService service = new SettingsService(repo);

            AppSettings updated = service.getSettings().toBuilder()
                .deviceAlias("Updated-Alias")
                .listeningPort(6000)
                .notificationsEnabled(false)
                .build();

            SettingsService.ValidationResult result = service.updateSettings(updated);
            assertTrue(result.valid());
            assertEquals("Updated-Alias", service.getSettings().deviceAlias());
            assertEquals(6000, service.getSettings().listeningPort());
            assertFalse(service.getSettings().notificationsEnabled());

            // Verify persistence by reloading with fresh repository
            SettingsRepository.LoadResult diskResult = repo.load();
            assertEquals("Updated-Alias", diskResult.settings().deviceAlias());
            assertEquals(6000, diskResult.settings().listeningPort());
            assertFalse(diskResult.settings().notificationsEnabled());

            service.shutdown();
        });
    }

    @Test
    @DisplayName("updateSettingsAsync completes asynchronously without blocking")
    void testUpdateSettingsAsync(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("settings.json");
        JsonSettingsRepository repo = new JsonSettingsRepository(file);
        SettingsService service = new SettingsService(repo);

        AppSettings target = service.getSettings().withDeviceAlias("Async-Node");
        CompletableFuture<SettingsService.ValidationResult> future = service.updateSettingsAsync(target);

        SettingsService.ValidationResult res = future.get(5, TimeUnit.SECONDS);
        assertTrue(res.valid());

        // Await JavaFX event queue delivery
        CountDownLatch fxLatch = new CountDownLatch(1);
        Platform.runLater(fxLatch::countDown);
        assertTrue(fxLatch.await(5, TimeUnit.SECONDS));

        assertEquals("Async-Node", service.getSettings().deviceAlias());
        service.shutdown();
    }

    @Test
    @DisplayName("Validation prevents invalid port or empty alias from updating settings")
    void testValidationPreventsInvalidUpdates(@TempDir Path tempDir) throws Throwable {
        Path file = tempDir.resolve("settings.json");
        JsonSettingsRepository repo = new JsonSettingsRepository(file);

        JavaFxToolkitExtension.runOnFxThread(() -> {
            SettingsService service = new SettingsService(repo);
            String previousAlias = service.getSettings().deviceAlias();

            // Empty alias
            SettingsService.ValidationResult emptyAlias = service.updateSettings(
                service.getSettings().withDeviceAlias("")
            );
            assertFalse(emptyAlias.valid());
            assertEquals(previousAlias, service.getSettings().deviceAlias());

            // Invalid port
            SettingsService.ValidationResult invalidPort = service.updateSettings(
                service.getSettings().withListeningPort(99999)
            );
            assertFalse(invalidPort.valid());

            service.shutdown();
        });
    }

    @Test
    @DisplayName("Individual preference updater methods work and persist accurately")
    void testIndividualUpdaters(@TempDir Path tempDir) throws Throwable {
        Path file = tempDir.resolve("settings.json");
        JsonSettingsRepository repo = new JsonSettingsRepository(file);

        JavaFxToolkitExtension.runOnFxThread(() -> {
            SettingsService service = new SettingsService(repo);

            assertTrue(service.updateDeviceAlias("Single-Update").valid());
            assertEquals("Single-Update", service.getSettings().deviceAlias());

            assertTrue(service.updateListeningPort(5555).valid());
            assertEquals(5555, service.getSettings().listeningPort());

            assertTrue(service.updateStartupPreferences(true, true).valid());
            assertTrue(service.getSettings().autoStartReceiver());
            assertTrue(service.getSettings().startMinimized());

            assertTrue(service.updateNotificationPreferences(false, false, false, false).valid());
            assertFalse(service.getSettings().notificationsEnabled());
            assertFalse(service.getSettings().soundEnabled());

            assertTrue(service.updateSecurityPreferences(false, true).valid());
            assertFalse(service.getSettings().requireApproval());
            assertTrue(service.getSettings().verifySha256());

            assertTrue(service.updateTheme("SYSTEM").valid());
            assertEquals("SYSTEM", service.getSettings().theme());

            service.shutdown();
        });
    }

    @Test
    @DisplayName("resetDefaults reverts to AppSettings defaults and persists to disk")
    void testResetDefaults(@TempDir Path tempDir) throws Throwable {
        Path file = tempDir.resolve("settings.json");
        JsonSettingsRepository repo = new JsonSettingsRepository(file);

        JavaFxToolkitExtension.runOnFxThread(() -> {
            SettingsService service = new SettingsService(repo);

            service.updateSettings(service.getSettings().withDeviceAlias("Modified-Host").withListeningPort(7777));
            assertEquals("Modified-Host", service.getSettings().deviceAlias());

            service.resetDefaults();
            assertEquals(AppSettings.DEFAULT_PORT, service.getSettings().listeningPort());
            assertEquals(AppSettings.DEFAULT_RECEIVE_DIR, service.getSettings().receiveDirectory());

            SettingsRepository.LoadResult diskResult = repo.load();
            assertEquals(AppSettings.DEFAULT_PORT, diskResult.settings().listeningPort());
            assertEquals(AppSettings.DEFAULT_RECEIVE_DIR, diskResult.settings().receiveDirectory());

            service.shutdown();
        });
    }

    @Test
    @DisplayName("Persistence failure reports error message without crashing application")
    void testPersistenceFailureReporting(@TempDir Path tempDir) throws Throwable {
        Path file = tempDir.resolve("settings.json");
        SettingsRepository failingRepo = new SettingsRepository() {
            @Override
            public Path storageLocation() {
                return file;
            }

            @Override
            public LoadResult load() {
                return LoadResult.fallback(AppSettings.defaults(), "Fallback");
            }

            @Override
            public void save(AppSettings settings) throws IOException {
                throw new IOException("Simulated disk full");
            }
        };

        JavaFxToolkitExtension.runOnFxThread(() -> {
            SettingsService service = new SettingsService(failingRepo);

            SettingsService.ValidationResult result = service.updateSettings(
                service.getSettings().withDeviceAlias("Will-Fail")
            );

            assertFalse(result.valid());
            assertTrue(result.message().contains("Simulated disk full"));
            assertFalse(service.getLastPersistenceError().isBlank());

            service.shutdown();
        });
    }
}
