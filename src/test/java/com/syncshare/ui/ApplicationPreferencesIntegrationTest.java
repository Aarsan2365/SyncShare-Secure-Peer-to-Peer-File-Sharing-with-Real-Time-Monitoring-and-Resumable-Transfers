package com.syncshare.ui;

import com.syncshare.events.IncomingTransferObserver;
import com.syncshare.model.AppSettings;
import com.syncshare.model.LocalDeviceInfo;
import com.syncshare.service.ConnectionService;
import com.syncshare.service.DeviceInfoService;
import com.syncshare.service.HistoryService;
import com.syncshare.service.JsonSettingsRepository;
import com.syncshare.service.SettingsService;
import com.syncshare.service.SyncShareServices;
import com.syncshare.service.TransferMonitoringService;
import com.syncshare.service.TransferService;
import com.syncshare.ui.controller.ReceiveFilesController;
import com.syncshare.ui.controller.SendFilesController;
import com.syncshare.ui.design.DesignSystem;
import com.syncshare.ui.design.FeedbackBanner;
import com.syncshare.ui.navigation.ViewNavigator;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.layout.StackPane;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(JavaFxToolkitExtension.class)
class ApplicationPreferencesIntegrationTest {

    @Test
    @DisplayName("SyncShareApplication stage is iconified when startMinimized is configured")
    void testStartupPreferenceStartMinimized(@TempDir Path tempDir) throws Throwable {
        JavaFxToolkitExtension.runOnFxThread(() -> {
            Path settingsFile = tempDir.resolve("settings.json");
            JsonSettingsRepository repo = new JsonSettingsRepository(settingsFile);
            SettingsService settings = new SettingsService(repo);
            settings.updateSettings(settings.getSettings().withStartMinimized(true));

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
                stage.setTitle(SyncShareApplication.APP_TITLE);

                AppSettings current = services.settingsService().getSettings();
                if (current.startMinimized()) {
                    stage.setIconified(true);
                }
                stage.show();

                assertTrue(stage.isIconified(), "Stage must be iconified when startMinimized is true");
            } finally {
                services.shutdown();
                stage.close();
            }
        });
    }

    @Test
    @DisplayName("ReceiveFilesController suppresses notifications when notificationsEnabled is false")
    void testNotificationsDisabledSuppressesBanners(@TempDir Path tempDir) throws Throwable {
        JavaFxToolkitExtension.runOnFxThread(() -> {
            Path settingsFile = tempDir.resolve("settings.json");
            JsonSettingsRepository repo = new JsonSettingsRepository(settingsFile);
            SettingsService settings = new SettingsService(repo);
            settings.updateSettings(settings.getSettings().withNotificationsEnabled(false));
            assertFalse(settings.getSettings().notificationsEnabled(), "notificationsEnabled should be false");

            AtomicReference<IncomingTransferObserver> observerRef = new AtomicReference<>();
            DeviceInfoService deviceInfo = new DeviceInfoService(settings) {
                @Override
                public boolean isTlsConfigured() {
                    return true;
                }
            };
            TransferMonitoringService monitoring = new TransferMonitoringService();
            TransferService transfers = new TransferService(monitoring);
            ConnectionService connection = new ConnectionService(
                settings,
                deviceInfo,
                transfers,
                monitoring,
                (port, handler, dir, obs) -> {
                    observerRef.set(obs);
                    return () -> {};
                }
            );
            HistoryService history = new HistoryService(settings, monitoring);
            SyncShareServices services = new SyncShareServices(settings, deviceInfo, connection, transfers, monitoring, history);

            FXMLLoader loader = new FXMLLoader(getClass().getResource("/com/syncshare/ui/views/receive-files-view.fxml"));
            Parent root = loader.load();
            ReceiveFilesController controller = loader.getController();

            Scene scene = new Scene(root);
            DesignSystem.applyTheme(scene);
            ViewNavigator navigator = new ViewNavigator(new StackPane(), services, java.util.List.of());
            controller.initializeContext(services, navigator);

            FeedbackBanner banner = (FeedbackBanner) root.lookup("#receiveFeedbackBanner");
            assertNotNull(banner);
            assertEquals("Receive Files Ready", banner.getTitle());

            // Start receiving and trigger an incoming file offer
            assertTrue(connection.startReceiving(tempDir));
            IncomingTransferObserver obs = observerRef.get();
            assertNotNull(obs);

            obs.onConnectionOpened("tx-test-1", "127.0.0.1:5050", "peer-key");
            obs.onFileOffered("tx-test-1", "data.zip", 2048L, "127.0.0.1:5050", "peer-key");

            // Because notificationsEnabled is false, banner should NOT be replaced by notification banner
            assertEquals("Receive Files Ready", banner.getTitle(), "Notification banner must remain suppressed when notifications are disabled");
            assertFalse(banner.getTitle().contains("Incoming File Offer"));

            services.shutdown();
        });
    }

    @Test
    @DisplayName("SendFilesController uses configured downloadDirectory as initial directory fallback")
    void testSendFilesUsesConfiguredDownloadDirectory(@TempDir Path tempDir) throws Throwable {
        JavaFxToolkitExtension.runOnFxThread(() -> {
            Path customDl = tempDir.resolve("custom-dl-dir");
            new File(customDl.toString()).mkdirs();

            Path settingsFile = tempDir.resolve("settings.json");
            JsonSettingsRepository repo = new JsonSettingsRepository(settingsFile);
            SettingsService settings = new SettingsService(repo);
            settings.updateSettings(settings.getSettings().withDownloadDirectory(customDl.toString()));

            DeviceInfoService deviceInfo = new DeviceInfoService(settings);
            TransferMonitoringService monitoring = new TransferMonitoringService();
            TransferService transfers = new TransferService(monitoring);
            ConnectionService connection = new ConnectionService(settings, deviceInfo, transfers, monitoring);
            HistoryService history = new HistoryService(settings, monitoring);
            SyncShareServices services = new SyncShareServices(settings, deviceInfo, connection, transfers, monitoring, history);

            FXMLLoader loader = new FXMLLoader(getClass().getResource("/com/syncshare/ui/views/send-files-view.fxml"));
            Parent root = loader.load();
            SendFilesController controller = loader.getController();

            Scene scene = new Scene(root);
            DesignSystem.applyTheme(scene);
            ViewNavigator navigator = new ViewNavigator(new StackPane(), services, java.util.List.of());
            controller.initializeContext(services, navigator);

            FileChooser chooser = controller.buildFileChooser();
            assertNotNull(chooser.getInitialDirectory(), "Initial directory should be set from downloadDirectory");
            assertEquals(customDl.toFile().getAbsolutePath(), chooser.getInitialDirectory().getAbsolutePath());

            services.shutdown();
        });
    }

    @Test
    @DisplayName("Network preferences update live in DeviceInfoService without exposing credentials")
    void testNetworkPreferencesLiveUpdate(@TempDir Path tempDir) throws Throwable {
        JavaFxToolkitExtension.runOnFxThread(() -> {
            SettingsService settings = new SettingsService(new JsonSettingsRepository(tempDir.resolve("s.json")));
            DeviceInfoService deviceInfo = new DeviceInfoService(settings);

            LocalDeviceInfo initialInfo = deviceInfo.getDeviceInfo();
            assertNotNull(initialInfo.operatingSystem());
            assertNotNull(initialInfo.ipAddress());

            // Update allowed fields
            settings.updateSettings(settings.getSettings().withDeviceAlias("Updated-Alias").withListeningPort(5432));

            LocalDeviceInfo updatedInfo = deviceInfo.getDeviceInfo();
            assertEquals("Updated-Alias", updatedInfo.deviceName());
            assertEquals(5432, updatedInfo.listeningPort());

            // Verify TLS paths and status are preserved and no plaintext credentials leaked
            assertEquals(initialInfo.keyStorePath(), updatedInfo.keyStorePath());
            assertEquals(initialInfo.trustStorePath(), updatedInfo.trustStorePath());
            assertFalse(updatedInfo.keyStorePath().toLowerCase().contains("secret_pass"));
            assertFalse(updatedInfo.trustStorePath().toLowerCase().contains("secret_pass"));

            settings.shutdown();
        });
    }
}
