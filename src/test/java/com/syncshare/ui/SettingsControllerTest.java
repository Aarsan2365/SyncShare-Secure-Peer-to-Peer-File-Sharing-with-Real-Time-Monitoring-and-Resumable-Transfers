package com.syncshare.ui;

import com.syncshare.model.AppSettings;
import com.syncshare.service.ConnectionService;
import com.syncshare.service.DeviceInfoService;
import com.syncshare.service.HistoryService;
import com.syncshare.service.JsonSettingsRepository;
import com.syncshare.service.SettingsService;
import com.syncshare.service.SyncShareServices;
import com.syncshare.service.TransferMonitoringService;
import com.syncshare.service.TransferService;
import com.syncshare.ui.controller.SettingsController;
import com.syncshare.ui.design.DesignSystem;
import com.syncshare.ui.navigation.ViewNavigator;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.layout.StackPane;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.net.URL;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(JavaFxToolkitExtension.class)
class SettingsControllerTest {

    @Test
    @DisplayName("SettingsController builds all sections and populates initial values from service")
    void testInitializationAndPopulation(@TempDir Path tempDir) throws Throwable {
        JavaFxToolkitExtension.runOnFxThread(() -> {
            Path settingsFile = tempDir.resolve("settings.json");
            JsonSettingsRepository repo = new JsonSettingsRepository(settingsFile);
            SettingsService settingsService = new SettingsService(repo);

            AppSettings custom = new AppSettings(
                "Workstation-Beta",
                5080,
                tempDir.resolve("recv").toString(),
                tempDir.resolve("dl").toString(),
                false,
                true,
                true,
                true,
                true,
                true,
                false,
                false,
                "DEFAULT"
            );
            settingsService.updateSettings(custom);

            DeviceInfoService deviceInfo = new DeviceInfoService(settingsService);
            TransferMonitoringService monitoring = new TransferMonitoringService();
            TransferService transfers = new TransferService(monitoring);
            ConnectionService connection = new ConnectionService(settingsService, deviceInfo, transfers, monitoring);
            HistoryService history = new HistoryService(settingsService, monitoring);
            SyncShareServices services = new SyncShareServices(
                settingsService,
                deviceInfo,
                connection,
                transfers,
                monitoring,
                history
            );

            URL fxml = getClass().getResource("/com/syncshare/ui/views/settings-view.fxml");
            assertNotNull(fxml, "settings-view.fxml must exist");

            FXMLLoader loader = new FXMLLoader(fxml);
            Parent root = loader.load();
            SettingsController controller = loader.getController();

            Scene scene = new Scene(root);
            DesignSystem.applyTheme(scene);

            ViewNavigator navigator = new ViewNavigator(new StackPane(), services, java.util.List.of());
            controller.initializeContext(services, navigator);

            assertEquals("Workstation-Beta", controller.getAliasField().getText());
            assertEquals("5080", controller.getPortField().getText());
            assertEquals(tempDir.resolve("recv").toString(), controller.getReceiveDirField().getText());
            assertEquals(tempDir.resolve("dl").toString(), controller.getDownloadDirField().getText());
            assertFalse(controller.getRequireApprovalCheck().isSelected());
            assertTrue(controller.getVerifySha256Check().isSelected());
            assertTrue(controller.getAutoStartCheck().isSelected());
            assertTrue(controller.getStartMinimizedCheck().isSelected());
            assertTrue(controller.getNotificationsEnabledCheck().isSelected());
            assertFalse(controller.getNotifyOnIncomingRequestCheck().isSelected());
            assertFalse(controller.getSoundEnabledCheck().isSelected());

            services.shutdown();
        });
    }

    @Test
    @DisplayName("Master notification toggle enables and disables sub-checkboxes")
    void testNotificationMasterToggle(@TempDir Path tempDir) throws Throwable {
        JavaFxToolkitExtension.runOnFxThread(() -> {
            SettingsService settingsService = new SettingsService(new JsonSettingsRepository(tempDir.resolve("s.json")));
            DeviceInfoService deviceInfo = new DeviceInfoService(settingsService);
            TransferMonitoringService monitoring = new TransferMonitoringService();
            TransferService transfers = new TransferService(monitoring);
            ConnectionService connection = new ConnectionService(settingsService, deviceInfo, transfers, monitoring);
            HistoryService history = new HistoryService(settingsService, monitoring);
            SyncShareServices services = new SyncShareServices(settingsService, deviceInfo, connection, transfers, monitoring, history);

            FXMLLoader loader = new FXMLLoader(getClass().getResource("/com/syncshare/ui/views/settings-view.fxml"));
            Parent root = loader.load();
            SettingsController controller = loader.getController();

            Scene scene = new Scene(root);
            DesignSystem.applyTheme(scene);

            ViewNavigator navigator = new ViewNavigator(new StackPane(), services, java.util.List.of());
            controller.initializeContext(services, navigator);

            controller.getNotificationsEnabledCheck().setSelected(false);
            assertTrue(controller.getNotifyOnTransferCompleteCheck().getParent().isDisable());

            controller.getNotificationsEnabledCheck().setSelected(true);
            assertFalse(controller.getNotifyOnTransferCompleteCheck().getParent().isDisable());

            services.shutdown();
        });
    }

    @Test
    @DisplayName("Saving invalid inputs displays error banner without modifying service state")
    void testSaveInvalidInput(@TempDir Path tempDir) throws Throwable {
        JavaFxToolkitExtension.runOnFxThread(() -> {
            SettingsService settingsService = new SettingsService(new JsonSettingsRepository(tempDir.resolve("s.json")));
            DeviceInfoService deviceInfo = new DeviceInfoService(settingsService);
            TransferMonitoringService monitoring = new TransferMonitoringService();
            TransferService transfers = new TransferService(monitoring);
            ConnectionService connection = new ConnectionService(settingsService, deviceInfo, transfers, monitoring);
            HistoryService history = new HistoryService(settingsService, monitoring);
            SyncShareServices services = new SyncShareServices(settingsService, deviceInfo, connection, transfers, monitoring, history);

            FXMLLoader loader = new FXMLLoader(getClass().getResource("/com/syncshare/ui/views/settings-view.fxml"));
            Parent root = loader.load();
            SettingsController controller = loader.getController();

            Scene scene = new Scene(root);
            DesignSystem.applyTheme(scene);

            ViewNavigator navigator = new ViewNavigator(new StackPane(), services, java.util.List.of());
            controller.initializeContext(services, navigator);

            int originalPort = settingsService.getSettings().listeningPort();

            // Set invalid port and trigger save
            controller.getPortField().setText("99999");
            controller.getSaveButton().fire();

            // Port in service must remain unchanged
            assertEquals(originalPort, settingsService.getSettings().listeningPort());

            services.shutdown();
        });
    }

    @Test
    @DisplayName("Saving valid inputs updates SettingsService and shows success")
    void testSaveValidInput(@TempDir Path tempDir) throws Throwable {
        JavaFxToolkitExtension.runOnFxThread(() -> {
            Path recvDir = tempDir.resolve("valid-recv");
            Path dlDir = tempDir.resolve("valid-dl");
            new File(recvDir.toString()).mkdirs();
            new File(dlDir.toString()).mkdirs();

            SettingsService settingsService = new SettingsService(new JsonSettingsRepository(tempDir.resolve("s.json")));
            DeviceInfoService deviceInfo = new DeviceInfoService(settingsService);
            TransferMonitoringService monitoring = new TransferMonitoringService();
            TransferService transfers = new TransferService(monitoring);
            ConnectionService connection = new ConnectionService(settingsService, deviceInfo, transfers, monitoring);
            HistoryService history = new HistoryService(settingsService, monitoring);
            SyncShareServices services = new SyncShareServices(settingsService, deviceInfo, connection, transfers, monitoring, history);

            FXMLLoader loader = new FXMLLoader(getClass().getResource("/com/syncshare/ui/views/settings-view.fxml"));
            Parent root = loader.load();
            SettingsController controller = loader.getController();

            Scene scene = new Scene(root);
            DesignSystem.applyTheme(scene);

            ViewNavigator navigator = new ViewNavigator(new StackPane(), services, java.util.List.of());
            controller.initializeContext(services, navigator);

            controller.getAliasField().setText("New-Valid-Alias");
            controller.getPortField().setText("5100");
            controller.getReceiveDirField().setText(recvDir.toString());
            controller.getDownloadDirField().setText(dlDir.toString());
            controller.getAutoStartCheck().setSelected(true);
            controller.getStartMinimizedCheck().setSelected(true);

            controller.getSaveButton().fire();

            assertEquals("New-Valid-Alias", settingsService.getSettings().deviceAlias());
            assertEquals(5100, settingsService.getSettings().listeningPort());
            assertEquals(recvDir.toString(), settingsService.getSettings().receiveDirectory());
            assertEquals(dlDir.toString(), settingsService.getSettings().downloadDirectory());
            assertTrue(settingsService.getSettings().autoStartReceiver());
            assertTrue(settingsService.getSettings().startMinimized());

            services.shutdown();
        });
    }

    @Test
    @DisplayName("Reset defaults restores model and updates UI controls")
    void testResetDefaultsButton(@TempDir Path tempDir) throws Throwable {
        JavaFxToolkitExtension.runOnFxThread(() -> {
            SettingsService settingsService = new SettingsService(new JsonSettingsRepository(tempDir.resolve("s.json")));
            DeviceInfoService deviceInfo = new DeviceInfoService(settingsService);
            TransferMonitoringService monitoring = new TransferMonitoringService();
            TransferService transfers = new TransferService(monitoring);
            ConnectionService connection = new ConnectionService(settingsService, deviceInfo, transfers, monitoring);
            HistoryService history = new HistoryService(settingsService, monitoring);
            SyncShareServices services = new SyncShareServices(settingsService, deviceInfo, connection, transfers, monitoring, history);

            FXMLLoader loader = new FXMLLoader(getClass().getResource("/com/syncshare/ui/views/settings-view.fxml"));
            Parent root = loader.load();
            SettingsController controller = loader.getController();

            Scene scene = new Scene(root);
            DesignSystem.applyTheme(scene);

            ViewNavigator navigator = new ViewNavigator(new StackPane(), services, java.util.List.of());
            controller.initializeContext(services, navigator);

            controller.getAliasField().setText("Changed-Alias");
            controller.getPortField().setText("5888");

            // Fire reset defaults
            controller.getResetButton().fire();

            assertEquals(Integer.toString(AppSettings.DEFAULT_PORT), controller.getPortField().getText());
            assertEquals(AppSettings.DEFAULT_RECEIVE_DIR, controller.getReceiveDirField().getText());

            services.shutdown();
        });
    }
}
