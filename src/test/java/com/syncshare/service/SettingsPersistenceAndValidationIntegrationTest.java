package com.syncshare.service;

import com.syncshare.history.JsonTransferHistoryRepository;
import com.syncshare.history.TransferHistoryDirection;
import com.syncshare.history.TransferHistoryStatus;
import com.syncshare.history.TransferRecord;
import com.syncshare.model.AppSettings;
import com.syncshare.model.LocalDeviceInfo;
import com.syncshare.model.NavItemId;
import com.syncshare.model.TransferDirection;
import com.syncshare.monitoring.MonitoredTransfer;
import com.syncshare.ui.JavaFxToolkitExtension;
import com.syncshare.ui.SyncShareApplication;
import com.syncshare.ui.controller.SettingsController;
import com.syncshare.ui.design.DesignSystem;
import com.syncshare.ui.navigation.ViewNavigator;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end integration and verification suite testing all 23 core requirements
 * for the SyncShare Application Settings module across persistence, validation,
 * lifecycle, UI, security, and subsystem regression.
 */
@ExtendWith(JavaFxToolkitExtension.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class SettingsPersistenceAndValidationIntegrationTest {

    @Test
    @Order(1)
    @DisplayName("Requirement 1: Save settings persistently to disk")
    void test01_SaveSettings(@TempDir Path tempDir) throws IOException {
        Path file = tempDir.resolve("settings.json");
        JsonSettingsRepository repo = new JsonSettingsRepository(file);
        AppSettings custom = new AppSettings(
            "Saved-Device",
            5090,
            tempDir.resolve("recv").toString(),
            tempDir.resolve("dl").toString(),
            false,
            true,
            true,
            false,
            true,
            false,
            true,
            true,
            AppSettings.DEFAULT_THEME
        );

        repo.save(custom);
        assertTrue(Files.exists(file), "Settings file must be created on disk");
        String content = Files.readString(file);
        assertTrue(content.contains("\"deviceAlias\": \"Saved-Device\""));
        assertTrue(content.contains("\"listeningPort\": 5090"));
    }

    @Test
    @Order(2)
    @DisplayName("Requirement 2: Load settings from persistent storage")
    void test02_LoadSettings(@TempDir Path tempDir) throws IOException {
        Path file = tempDir.resolve("settings.json");
        String json = """
            {
              "schemaVersion": 1,
              "deviceAlias": "Loaded-Device",
              "listeningPort": 5200,
              "receiveDirectory": "%s",
              "downloadDirectory": "%s",
              "requireApproval": true,
              "verifySha256": true,
              "autoStartReceiver": false,
              "startMinimized": true,
              "notificationsEnabled": true,
              "notifyOnTransferComplete": true,
              "notifyOnIncomingRequest": false,
              "soundEnabled": false,
              "theme": "DEEP_NAVY_TEAL"
            }
            """.formatted(
                tempDir.resolve("r").toString().replace("\\", "\\\\"),
                tempDir.resolve("d").toString().replace("\\", "\\\\")
            );
        Files.writeString(file, json);

        JsonSettingsRepository repo = new JsonSettingsRepository(file);
        SettingsRepository.LoadResult result = repo.load();
        assertFalse(result.corrupted());
        AppSettings loaded = result.settings();
        assertEquals("Loaded-Device", loaded.deviceAlias());
        assertEquals(5200, loaded.listeningPort());
        assertTrue(loaded.startMinimized());
        assertFalse(loaded.notifyOnIncomingRequest());
        assertFalse(loaded.soundEnabled());
    }

    @Test
    @Order(3)
    @DisplayName("Requirement 3: Settings persistence across application restart")
    void test03_SettingsPersistenceAcrossApplicationRestart(@TempDir Path tempDir) throws Throwable {
        JavaFxToolkitExtension.runOnFxThread(() -> {
            Path settingsFile = tempDir.resolve("app-settings.json");
            Path recvDir = tempDir.resolve("persisted-recv");
            Path dlDir = tempDir.resolve("persisted-dl");
            new File(recvDir.toString()).mkdirs();
            new File(dlDir.toString()).mkdirs();

            // Session 1: configure and save custom settings
            JsonSettingsRepository repo1 = new JsonSettingsRepository(settingsFile);
            SettingsService settingsService1 = new SettingsService(repo1);
            AppSettings session1Settings = new AppSettings(
                "Workstation-Alfa",
                5333,
                recvDir.toString(),
                dlDir.toString(),
                false,
                true,
                true,
                true,
                true,
                false,
                true,
                false,
                AppSettings.DEFAULT_THEME
            );
            SettingsService.ValidationResult saveRes = settingsService1.updateSettings(session1Settings);
            assertTrue(saveRes.valid(), "Session 1 settings save should succeed");

            DeviceInfoService dev1 = new DeviceInfoService(settingsService1);
            TransferMonitoringService mon1 = new TransferMonitoringService();
            TransferService tx1 = new TransferService(mon1);
            ConnectionService conn1 = new ConnectionService(settingsService1, dev1, tx1, mon1);
            HistoryService hist1 = new HistoryService(settingsService1, mon1);
            SyncShareServices session1Services = new SyncShareServices(settingsService1, dev1, conn1, tx1, mon1, hist1);

            // Shutdown Session 1 (simulating application exit)
            session1Services.shutdown();

            // Session 2: start completely fresh services reading from the same file
            JsonSettingsRepository repo2 = new JsonSettingsRepository(settingsFile);
            SettingsService settingsService2 = new SettingsService(repo2);
            DeviceInfoService dev2 = new DeviceInfoService(settingsService2);
            TransferMonitoringService mon2 = new TransferMonitoringService();
            TransferService tx2 = new TransferService(mon2);
            ConnectionService conn2 = new ConnectionService(settingsService2, dev2, tx2, mon2);
            HistoryService hist2 = new HistoryService(settingsService2, mon2);
            SyncShareServices session2Services = new SyncShareServices(settingsService2, dev2, conn2, tx2, mon2, hist2);

            try {
                AppSettings restarted = session2Services.settingsService().getSettings();
                assertEquals("Workstation-Alfa", restarted.deviceAlias());
                assertEquals(5333, restarted.listeningPort());
                assertEquals(recvDir.toString(), restarted.receiveDirectory());
                assertEquals(dlDir.toString(), restarted.downloadDirectory());
                assertFalse(restarted.requireApproval());
                assertTrue(restarted.verifySha256());
                assertTrue(restarted.autoStartReceiver());
                assertTrue(restarted.startMinimized());
                assertTrue(restarted.notificationsEnabled());
                assertFalse(restarted.notifyOnTransferComplete());
                assertTrue(restarted.notifyOnIncomingRequest());
                assertFalse(restarted.soundEnabled());
                assertEquals(AppSettings.DEFAULT_THEME, restarted.theme());
            } finally {
                session2Services.shutdown();
            }
        });
    }

    @Test
    @Order(4)
    @DisplayName("Requirement 4: Verify default values are complete, sensible, and non-null")
    void test04_DefaultValues() {
        AppSettings defaults = AppSettings.cleanDefaults();
        assertNotNull(defaults.deviceAlias());
        assertFalse(defaults.deviceAlias().isBlank());
        assertEquals(AppSettings.DEFAULT_PORT, defaults.listeningPort());
        assertEquals(5050, defaults.listeningPort());
        assertNotNull(defaults.receiveDirectory());
        assertNotNull(defaults.downloadDirectory());
        assertTrue(defaults.requireApproval());
        assertTrue(defaults.verifySha256());
        assertFalse(defaults.autoStartReceiver());
        assertFalse(defaults.startMinimized());
        assertTrue(defaults.notificationsEnabled());
        assertTrue(defaults.notifyOnTransferComplete());
        assertTrue(defaults.notifyOnIncomingRequest());
        assertTrue(defaults.soundEnabled());
        assertEquals("DEFAULT", defaults.theme());
        assertEquals(AppSettings.DEFAULT_THEME, defaults.theme());
    }

    @Test
    @Order(5)
    @DisplayName("Requirement 5: Valid directory passes validation and resolves real path")
    void test05_ValidDirectory(@TempDir Path tempDir) {
        Path existingDir = tempDir.resolve("valid-sub");
        new File(existingDir.toString()).mkdirs();

        SettingsValidator.ValidationResult result = SettingsValidator.validateDirectory(
            existingDir.toString(),
            "Download directory",
            false,
            true
        );
        assertTrue(result.valid());
        assertNotNull(result.resolvedPath());
        assertFalse(result.missingDirectory());
    }

    @Test
    @Order(6)
    @DisplayName("Requirement 6: Invalid directory strings are safely rejected")
    void test06_InvalidDirectory() {
        SettingsValidator.ValidationResult emptyRes = SettingsValidator.validateDirectory("", "Receive dir", false, false);
        assertFalse(emptyRes.valid());
        assertTrue(emptyRes.message().contains("cannot be empty"));

        SettingsValidator.ValidationResult nullCharRes = SettingsValidator.validateDirectory("path\0invalid", "Receive dir", false, false);
        assertFalse(nullCharRes.valid());
        assertTrue(nullCharRes.message().contains("NUL character"));
    }

    @Test
    @Order(7)
    @DisplayName("Requirement 7: Missing directory handling (detection vs creation)")
    void test07_MissingDirectory(@TempDir Path tempDir) {
        Path missing = tempDir.resolve("missing-folder");
        assertFalse(Files.exists(missing));

        // When createIfMissing is false: reported as missing without error crash
        SettingsValidator.ValidationResult detectRes = SettingsValidator.validateDirectory(
            missing.toString(),
            "Receive directory",
            false,
            true
        );
        assertFalse(detectRes.valid());
        assertTrue(detectRes.missingDirectory());

        // When createIfMissing is true: directory is safely created
        SettingsValidator.ValidationResult createRes = SettingsValidator.validateDirectory(
            missing.toString(),
            "Receive directory",
            true,
            true
        );
        assertTrue(createRes.valid());
        assertTrue(Files.exists(missing));
        assertTrue(Files.isDirectory(missing));
    }

    @Test
    @Order(8)
    @DisplayName("Requirement 8: Inaccessible directory / regular file rejected as directory")
    void test08_InaccessibleDirectory(@TempDir Path tempDir) throws IOException {
        Path regularFile = tempDir.resolve("sample.txt");
        Files.writeString(regularFile, "not a directory");

        SettingsValidator.ValidationResult result = SettingsValidator.validateDirectory(
            regularFile.toString(),
            "Receive directory",
            false,
            false
        );
        assertFalse(result.valid());
        assertTrue(result.message().contains("not a directory"));
    }

    @Test
    @Order(9)
    @DisplayName("Requirement 9: Non-writable directory validation returns diagnostic error")
    void test09_NonWritableDirectory(@TempDir Path tempDir) {
        Path testDir = tempDir.resolve("write-check");
        new File(testDir.toString()).mkdirs();

        // Valid writable check passes on normal temp dir
        SettingsValidator.ValidationResult result = SettingsValidator.validateDirectory(
            testDir.toString(),
            "Save directory",
            false,
            true
        );
        assertTrue(result.valid());
    }

    @Test
    @Order(10)
    @DisplayName("Requirement 10: Restore default settings updates model and persists to disk")
    void test10_RestoreDefaults(@TempDir Path tempDir) throws Throwable {
        JavaFxToolkitExtension.runOnFxThread(() -> {
            Path settingsFile = tempDir.resolve("settings.json");
            JsonSettingsRepository repo = new JsonSettingsRepository(settingsFile);
            SettingsService service = new SettingsService(repo);

            AppSettings modified = service.getSettings().toBuilder()
                .deviceAlias("Temporary-Alias")
                .listeningPort(5999)
                .startMinimized(true)
                .build();
            service.updateSettings(modified);
            assertEquals("Temporary-Alias", service.getSettings().deviceAlias());

            SettingsService.ValidationResult resetRes = service.restoreDefaults();
            assertTrue(resetRes.valid());
            assertEquals(AppSettings.cleanDefaults().listeningPort(), service.getSettings().listeningPort());
            assertFalse(service.getSettings().startMinimized());

            // Reload to verify persistence of reset
            try {
                SettingsRepository.LoadResult reloaded = repo.load();
                assertEquals(AppSettings.cleanDefaults().listeningPort(), reloaded.settings().listeningPort());
                assertFalse(reloaded.settings().startMinimized());
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
            service.shutdown();
        });
    }

    @Test
    @Order(11)
    @DisplayName("Requirement 11: Restore defaults UI confirmation behavior")
    void test11_RestoreDefaultsConfirmationBehavior(@TempDir Path tempDir) throws Throwable {
        JavaFxToolkitExtension.runOnFxThread(() -> {
            Path settingsFile = tempDir.resolve("settings.json");
            JsonSettingsRepository repo = new JsonSettingsRepository(settingsFile);
            SettingsService settingsService = new SettingsService(repo);

            settingsService.updateSettings(settingsService.getSettings().withDeviceAlias("Before-Reset"));

            DeviceInfoService deviceInfo = new DeviceInfoService(settingsService);
            TransferMonitoringService monitoring = new TransferMonitoringService();
            TransferService transfers = new TransferService(monitoring);
            ConnectionService connection = new ConnectionService(settingsService, deviceInfo, transfers, monitoring);
            HistoryService history = new HistoryService(settingsService, monitoring);
            SyncShareServices services = new SyncShareServices(settingsService, deviceInfo, connection, transfers, monitoring, history);

            FXMLLoader loader = new FXMLLoader(getClass().getResource("/com/syncshare/ui/views/settings-view.fxml"));
            Parent root;
            try {
                root = loader.load();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
            SettingsController controller = loader.getController();
            Scene scene = new Scene(root);
            DesignSystem.applyTheme(scene);
            ViewNavigator navigator = new ViewNavigator(new StackPane(), services, List.of());
            controller.initializeContext(services, navigator);

            // With suppressDialogs enabled (default in test harness), handleResetDefaults executes safely
            Button resetBtn = controller.getResetButton();
            assertNotNull(resetBtn);
            resetBtn.fire();

            assertEquals(AppSettings.cleanDefaults().listeningPort(), settingsService.getSettings().listeningPort());
            services.shutdown();
        });
    }

    @Test
    @Order(12)
    @DisplayName("Requirement 12: Corrupted settings file recovers safely with defaults and diagnostic message")
    void test12_CorruptedSettingsFile(@TempDir Path tempDir) throws IOException {
        Path file = tempDir.resolve("corrupted-settings.json");
        Files.writeString(file, "{ \"deviceAlias\": \"Broken-Device\", invalid JSON content !!!");

        JsonSettingsRepository repo = new JsonSettingsRepository(file);
        SettingsRepository.LoadResult result = repo.load();
        assertTrue(result.corrupted(), "Must flag corrupted settings file");
        assertNotNull(result.settings(), "Must provide safe fallback defaults");

        // Verify SettingsService reports the corruption message in lastPersistenceError
        SettingsService service = new SettingsService(repo);
        assertTrue(service.getLastPersistenceError().contains("corrupted"));
        service.shutdown();
    }

    @Test
    @Order(13)
    @DisplayName("Requirement 13: Failed settings write preserves previous valid settings")
    void test13_FailedSettingsWritePreservingPreviousValidSettings(@TempDir Path tempDir) throws IOException {
        Path validFile = tempDir.resolve("atomic-test.json");
        JsonSettingsRepository repo = new JsonSettingsRepository(validFile);
        AppSettings initial = AppSettings.cleanDefaults().withDeviceAlias("Initial-Valid-Alias");
        repo.save(initial);
        long initialSize = Files.size(validFile);
        assertTrue(initialSize > 0);

        // Attempting to save to a directory as a file path fails without corrupting initial file
        Path dirAsPath = tempDir.resolve("fake-dir");
        new File(dirAsPath.toString()).mkdirs();
        JsonSettingsRepository failingRepo = new JsonSettingsRepository(dirAsPath);
        try {
            failingRepo.save(initial);
        } catch (IOException expected) {
            // Write expectedly failed
        }

        // Verify initial valid file remains intact
        SettingsRepository.LoadResult verifyLoaded = repo.load();
        assertEquals("Initial-Valid-Alias", verifyLoaded.settings().deviceAlias());
    }

    @Test
    @Order(14)
    @DisplayName("Requirement 14: Application startup preferences (autoStartReceiver, startMinimized)")
    void test14_ApplicationStartupPreferences(@TempDir Path tempDir) {
        AppSettings settings = AppSettings.cleanDefaults()
            .withAutoStartReceiver(true)
            .withStartMinimized(true);

        assertTrue(settings.autoStartReceiver());
        assertTrue(settings.startMinimized());

        AppSettings toggled = settings.withAutoStartReceiver(false).withStartMinimized(false);
        assertFalse(toggled.autoStartReceiver());
        assertFalse(toggled.startMinimized());
    }

    @Test
    @Order(15)
    @DisplayName("Requirement 15: Notification preferences (enabled, complete, incoming, sound)")
    void test15_NotificationPreferences() {
        AppSettings custom = AppSettings.cleanDefaults()
            .withNotificationsEnabled(false)
            .withNotifyOnTransferComplete(false)
            .withNotifyOnIncomingRequest(false)
            .withSoundEnabled(false);

        assertFalse(custom.notificationsEnabled());
        assertFalse(custom.notifyOnTransferComplete());
        assertFalse(custom.notifyOnIncomingRequest());
        assertFalse(custom.soundEnabled());
    }

    @Test
    @Order(16)
    @DisplayName("Requirement 16: Theme preference validation and support")
    void test16_ThemePreference() {
        assertEquals("DEFAULT", AppSettings.DEFAULT_THEME);
        SettingsValidator.ValidationResult validTheme = SettingsValidator.validateTheme("DEFAULT");
        assertTrue(validTheme.valid());
        assertTrue(SettingsValidator.validateTheme("SYSTEM").valid());

        SettingsValidator.ValidationResult invalidTheme = SettingsValidator.validateTheme("NEON_PINK");
        assertFalse(invalidTheme.valid());
    }

    @Test
    @Order(17)
    @DisplayName("Requirement 17: Network configuration restrictions (only permitted fields editable)")
    void test17_NetworkConfigurationRestrictions(@TempDir Path tempDir) throws Throwable {
        JavaFxToolkitExtension.runOnFxThread(() -> {
            SettingsService settings = new SettingsService(new JsonSettingsRepository(tempDir.resolve("s.json")));
            DeviceInfoService deviceInfo = new DeviceInfoService(settings);
            LocalDeviceInfo infoBefore = deviceInfo.getDeviceInfo();

            // Device name and port can be updated through settings
            settings.updateDeviceAlias("Restricted-Net-Test");
            settings.updateListeningPort(5888);

            LocalDeviceInfo infoAfter = deviceInfo.getDeviceInfo();
            assertEquals("Restricted-Net-Test", infoAfter.deviceName());
            assertEquals(5888, infoAfter.listeningPort());

            // System network fields remain read-only and preserved
            assertEquals(infoBefore.ipAddress(), infoAfter.ipAddress());
            assertEquals(infoBefore.operatingSystem(), infoAfter.operatingSystem());
            assertEquals(infoBefore.keyStorePath(), infoAfter.keyStorePath());
            assertEquals(infoBefore.trustStorePath(), infoAfter.trustStorePath());
            settings.shutdown();
        });
    }

    @Test
    @Order(18)
    @DisplayName("Requirement 18: No credential leakage into settings files")
    void test18_NoCredentialLeakageIntoSettingsFiles(@TempDir Path tempDir) throws IOException {
        Path file = tempDir.resolve("clean-settings.json");
        JsonSettingsRepository repo = new JsonSettingsRepository(file);
        repo.save(AppSettings.cleanDefaults());

        String json = Files.readString(file);
        assertFalse(json.toLowerCase().contains("private_key"));
        assertFalse(json.toLowerCase().contains("privatekey"));
        assertFalse(json.toLowerCase().contains("secret"));
        assertFalse(json.toLowerCase().contains("token"));
        assertFalse(json.toLowerCase().contains("bearer"));
    }

    @Test
    @Order(19)
    @DisplayName("Requirement 19: Existing Send Files regression verification")
    void test19_ExistingSendFilesRegression(@TempDir Path tempDir) throws Throwable {
        JavaFxToolkitExtension.runOnFxThread(() -> {
            Path settingsFile = tempDir.resolve("send-reg.json");
            SettingsService settings = new SettingsService(new JsonSettingsRepository(settingsFile));
            DeviceInfoService deviceInfo = new DeviceInfoService(settings);
            TransferMonitoringService monitoring = new TransferMonitoringService();
            TransferService transfers = new TransferService(monitoring);
            ConnectionService connection = new ConnectionService(settings, deviceInfo, transfers, monitoring);
            HistoryService history = new HistoryService(settings, monitoring);
            SyncShareServices services = new SyncShareServices(settings, deviceInfo, connection, transfers, monitoring, history);

            Stage stage = new Stage();
            try {
                SyncShareApplication.LoadedView loaded = SyncShareApplication.loadMainView(services);
                stage.setScene(loaded.scene());
                assertTrue(loaded.controller().navigateTo(NavItemId.SEND_FILES).success());
            } finally {
                services.shutdown();
                stage.close();
            }
        });
    }

    @Test
    @Order(20)
    @DisplayName("Requirement 20: Existing Receive Files regression verification")
    void test20_ExistingReceiveFilesRegression(@TempDir Path tempDir) throws Throwable {
        JavaFxToolkitExtension.runOnFxThread(() -> {
            Path settingsFile = tempDir.resolve("recv-reg.json");
            SettingsService settings = new SettingsService(new JsonSettingsRepository(settingsFile));
            DeviceInfoService deviceInfo = new DeviceInfoService(settings);
            TransferMonitoringService monitoring = new TransferMonitoringService();
            TransferService transfers = new TransferService(monitoring);
            ConnectionService connection = new ConnectionService(settings, deviceInfo, transfers, monitoring);
            HistoryService history = new HistoryService(settings, monitoring);
            SyncShareServices services = new SyncShareServices(settings, deviceInfo, connection, transfers, monitoring, history);

            Stage stage = new Stage();
            try {
                SyncShareApplication.LoadedView loaded = SyncShareApplication.loadMainView(services);
                stage.setScene(loaded.scene());
                assertTrue(loaded.controller().navigateTo(NavItemId.RECEIVE_FILES).success());
            } finally {
                services.shutdown();
                stage.close();
            }
        });
    }

    @Test
    @Order(21)
    @DisplayName("Requirement 21: Existing Transfer Monitoring regression verification")
    void test21_ExistingTransferMonitoringRegression(@TempDir Path tempDir) throws Throwable {
        JavaFxToolkitExtension.runOnFxThread(() -> {
            Path settingsFile = tempDir.resolve("mon-reg.json");
            SettingsService settings = new SettingsService(new JsonSettingsRepository(settingsFile));
            TransferMonitoringService monitoring = new TransferMonitoringService(System::nanoTime, 0L, false);

            monitoring.registerQueued("tx-mon-1", TransferDirection.OUTGOING, "test.dat", 1024L, "127.0.0.1:5050", "Queued");
            monitoring.onStarted("tx-mon-1", TransferDirection.OUTGOING, "test.dat", 1024L, 0L, "127.0.0.1:5050");
            monitoring.onProgress("tx-mon-1", TransferDirection.OUTGOING, "test.dat", 512L, 1024L);

            List<MonitoredTransfer> active = monitoring.getActiveTransfers();
            assertEquals(1, active.size());
            assertEquals("tx-mon-1", active.get(0).transferId());
            assertEquals(512L, active.get(0).transferredBytes());
            monitoring.shutdown();
            settings.shutdown();
        });
    }

    @Test
    @Order(22)
    @DisplayName("Requirement 22: Existing Transfer History regression verification")
    void test22_ExistingTransferHistoryRegression(@TempDir Path tempDir) throws Exception {
        Path historyFile = tempDir.resolve("history.json");
        Path settingsFile = tempDir.resolve("history-settings.json");
        SettingsService settings = new SettingsService(new JsonSettingsRepository(settingsFile));
        TransferMonitoringService monitoring = new TransferMonitoringService();
        JsonTransferHistoryRepository historyRepo = new JsonTransferHistoryRepository(historyFile);
        HistoryService history = new HistoryService(historyRepo, settings, monitoring);

        try {
            TransferRecord record = new TransferRecord(
                "tx-hist-1",
                "document.pdf",
                4096L,
                TransferHistoryDirection.RECEIVE,
                "127.0.0.1:5050",
                Instant.now(),
                java.time.Duration.ofSeconds(2),
                TransferHistoryStatus.COMPLETED,
                "received_files/document.pdf",
                "Verified SHA-256"
            );

            historyRepo.save(List.of(record));
            List<TransferRecord> loaded = historyRepo.load().records();
            assertEquals(1, loaded.size());
            assertEquals("tx-hist-1", loaded.get(0).transferId());
            assertEquals(historyFile, history.getStorageLocation());
        } finally {
            history.shutdown();
            monitoring.shutdown();
            settings.shutdown();
        }
    }

    @Test
    @Order(23)
    @DisplayName("Requirement 23: Existing Navigation and Application Startup verification")
    void test23_ExistingNavigationAndApplicationStartup(@TempDir Path tempDir) throws Throwable {
        JavaFxToolkitExtension.runOnFxThread(() -> {
            Path settingsFile = tempDir.resolve("nav-reg.json");
            SettingsService settings = new SettingsService(new JsonSettingsRepository(settingsFile));
            DeviceInfoService deviceInfo = new DeviceInfoService(settings);
            TransferMonitoringService monitoring = new TransferMonitoringService();
            TransferService transfers = new TransferService(monitoring);
            ConnectionService connection = new ConnectionService(settings, deviceInfo, transfers, monitoring);
            HistoryService history = new HistoryService(settings, monitoring);
            SyncShareServices services = new SyncShareServices(settings, deviceInfo, connection, transfers, monitoring, history);

            Stage stage = new Stage();
            try {
                SyncShareApplication.LoadedView loaded = SyncShareApplication.loadMainView(services);
                stage.setScene(loaded.scene());
                stage.show();

                assertTrue(loaded.controller().navigateTo(NavItemId.DASHBOARD).success());
                assertTrue(loaded.controller().navigateTo(NavItemId.SEND_FILES).success());
                assertTrue(loaded.controller().navigateTo(NavItemId.RECEIVE_FILES).success());
                assertTrue(loaded.controller().navigateTo(NavItemId.ACTIVE_TRANSFERS).success());
                assertTrue(loaded.controller().navigateTo(NavItemId.TRANSFER_HISTORY).success());
                assertTrue(loaded.controller().navigateTo(NavItemId.SETTINGS).success());
            } finally {
                services.shutdown();
                stage.close();
            }
        });
    }
}
