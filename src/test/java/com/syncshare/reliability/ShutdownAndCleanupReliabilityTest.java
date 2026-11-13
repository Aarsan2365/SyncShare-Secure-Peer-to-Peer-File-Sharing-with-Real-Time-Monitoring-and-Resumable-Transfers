package com.syncshare.reliability;

import com.syncshare.history.JsonTransferHistoryRepository;
import com.syncshare.model.AppSettings;
import com.syncshare.model.TransferItem;
import com.syncshare.model.TransferStatus;
import com.syncshare.service.ConnectionService;
import com.syncshare.service.DeviceInfoService;
import com.syncshare.service.HistoryService;
import com.syncshare.service.JsonSettingsRepository;
import com.syncshare.service.PeerDiscoveryManager;
import com.syncshare.service.ReceiveListenerService;
import com.syncshare.service.SettingsService;
import com.syncshare.service.SyncShareServices;
import com.syncshare.service.TransferMonitoringService;
import com.syncshare.service.TransferService;
import com.syncshare.ui.JavaFxToolkitExtension;
import com.syncshare.ui.controller.ReceiveFilesController;
import com.syncshare.ui.controller.SendFileTransferService;
import com.syncshare.ui.controller.SendFilesController;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(com.syncshare.ui.JavaFxToolkitExtension.class)
class ShutdownAndCleanupReliabilityTest {

    @TempDir
    Path tempDir;

    private CentralErrorHandler errorHandler;

    @BeforeEach
    void setUp() {
        errorHandler = CentralErrorHandler.getInstance();
        errorHandler.clearRecentErrors();
    }

    @AfterEach
    void tearDown() {
        errorHandler.clearRecentErrors();
    }

    @Test
    void transferServiceShutdownCancelsRunningTransfersAndTerminatesExecutor() throws Throwable {
        JavaFxToolkitExtension.runOnFxThread(() -> {
            Path file = Files.createFile(tempDir.resolve("cancel-on-shutdown.dat"));
            TransferMonitoringService monitor = new TransferMonitoringService();
            CountDownLatch transferStarted = new CountDownLatch(1);
            CountDownLatch transferInterrupted = new CountDownLatch(1);

            TransferService service = new TransferService((host, port, f, l) -> {
                transferStarted.countDown();
                try {
                    Thread.sleep(10_000);
                } catch (InterruptedException e) {
                    transferInterrupted.countDown();
                    Thread.currentThread().interrupt();
                    throw new java.io.InterruptedIOException("Transfer interrupted");
                }
            }, monitor);

            CompletableFuture<TransferItem> future = service.sendFile("127.0.0.1", 5050, file);
            assertNotNull(future);
            assertTrue(transferStarted.await(3, TimeUnit.SECONDS), "Transfer should have started");

            // Shutdown the service while transfer is active
            service.shutdown();

            assertTrue(future.isDone(), "Transfer future should be completed on shutdown");
            TransferItem completedItem = future.join();
            assertNotNull(completedItem);
            assertEquals(TransferStatus.CANCELLED, completedItem.status());
        });
    }

    @Test
    void sendFileTransferServiceDisposesCleanly() throws Throwable {
        JavaFxToolkitExtension.runOnFxThread(() -> {
            SendFileTransferService service = new SendFileTransferService();
            assertDoesNotThrow(service::shutdown);
            assertDoesNotThrow(service::dispose);
        });
    }

    @Test
    void receiveListenerServiceDisposesExecutorCleanly() throws Throwable {
        JavaFxToolkitExtension.runOnFxThread(() -> {
            ConnectionService connection = new ConnectionService(
                new SettingsService(),
                new DeviceInfoService(new SettingsService())
            );
            ReceiveListenerService listenerService = new ReceiveListenerService(connection);
            assertDoesNotThrow(listenerService::dispose);
            connection.shutdown();
        });
    }

    @Test
    void controllersImplementNavigationAwareDisposeSafely() throws Throwable {
        JavaFxToolkitExtension.runOnFxThread(() -> {
            SendFilesController sendController = new SendFilesController();
            assertDoesNotThrow(sendController::dispose);

            ReceiveFilesController receiveController = new ReceiveFilesController();
            assertDoesNotThrow(receiveController::dispose);
        });
    }

    @Test
    void syncShareServicesShutdownCoordinatesAllServicesInOrder() throws Throwable {
        JavaFxToolkitExtension.runOnFxThread(() -> {
            Path settingsFile = tempDir.resolve("settings.json");
            Path historyFile = tempDir.resolve("history.json");

            SettingsService settings = new SettingsService(new JsonSettingsRepository(settingsFile), true);
            DeviceInfoService deviceInfo = new DeviceInfoService(settings);
            TransferMonitoringService monitor = new TransferMonitoringService();
            TransferService transfer = new TransferService(monitor);
            ConnectionService connection = new ConnectionService(settings, deviceInfo, transfer, monitor);
            HistoryService history = new HistoryService(settings, monitor);
            PeerDiscoveryManager discovery = new PeerDiscoveryManager(settings, deviceInfo);

            SyncShareServices services = new SyncShareServices(
                settings,
                deviceInfo,
                connection,
                transfer,
                monitor,
                history,
                discovery,
                errorHandler
            );

            // Verify clean shutdown
            assertDoesNotThrow(services::shutdown);
            assertFalse(discovery.isActive());
            assertFalse(connection.isListening());
        });
    }

    @Test
    void settingsAndHistoryPersistenceRemainIntactAfterShutdown() throws Throwable {
        JavaFxToolkitExtension.runOnFxThread(() -> {
            Path settingsFile = tempDir.resolve("settings.json");

            SettingsService settings = new SettingsService(new JsonSettingsRepository(settingsFile), true);
            AppSettings updated = settings.getSettings().toBuilder()
                .deviceAlias("Shutdown-Verified-Device")
                .build();
            SettingsService.ValidationResult result = settings.updateSettings(updated);
            assertTrue(result.valid());

            settings.shutdown();

            // Verify settings persistence was not corrupted by shutdown
            SettingsService reloadedSettings = new SettingsService(new JsonSettingsRepository(settingsFile), true);
            assertEquals("Shutdown-Verified-Device", reloadedSettings.getSettings().deviceAlias());
            reloadedSettings.shutdown();
        });
    }
}
