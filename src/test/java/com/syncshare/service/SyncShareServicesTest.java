package com.syncshare.service;

import com.syncshare.history.JsonTransferHistoryRepository;
import com.syncshare.history.TransferHistoryDirection;
import com.syncshare.history.TransferHistoryStatus;
import com.syncshare.history.TransferRecord;
import com.syncshare.model.AppSettings;
import com.syncshare.model.ConnectionState;
import com.syncshare.model.LocalDeviceInfo;
import com.syncshare.model.TransferDirection;
import com.syncshare.model.TransferItem;
import com.syncshare.model.TransferStatus;
import com.syncshare.ui.JavaFxToolkitExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(JavaFxToolkitExtension.class)
class SyncShareServicesTest {

    @TempDir
    Path tempDir;

    @Test
    void settingsServiceValidatesUpdatesAndResetsDefaults() throws Throwable {
        JavaFxToolkitExtension.runOnFxThread(() -> {
            SettingsService service = new SettingsService();

            assertFalse(service.validate("", "5050", "received_files").valid());
            assertFalse(service.validate("Node-A", "80", "received_files").valid());
            assertFalse(service.validate("Node-A", "abc", "received_files").valid());
            assertFalse(service.validate("Node-A", "5050", "").valid());
            assertTrue(service.validate("Node-A", "5050", "received_files").valid());

            AppSettings custom = new AppSettings("Lab-Desktop", 5151, tempDir.toString(), false, true, true);
            assertTrue(service.updateSettings(custom).valid());
            assertEquals("Lab-Desktop", service.getSettings().deviceAlias());
            assertEquals(5151, service.getSettings().listeningPort());

            service.resetDefaults();
            assertEquals(AppSettings.DEFAULT_PORT, service.getSettings().listeningPort());
        });
    }

    @Test
    void deviceInfoServiceResolvesNonEmptyIdentityAndReactsToSettings() throws Throwable {
        JavaFxToolkitExtension.runOnFxThread(() -> {
            SettingsService settings = new SettingsService();
            DeviceInfoService deviceInfo = new DeviceInfoService(settings);

            LocalDeviceInfo info = deviceInfo.getDeviceInfo();
            assertNotNull(info);
            assertFalse(info.deviceName().isBlank());
            assertFalse(info.ipAddress().isBlank());
            assertEquals(AppSettings.DEFAULT_PORT, info.listeningPort());

            settings.updateSettings(new AppSettings("Updated-Host", 6060, tempDir.toString(), true, true, false));
            assertEquals("Updated-Host", deviceInfo.getDeviceInfo().deviceName());
            assertEquals(6060, deviceInfo.getDeviceInfo().listeningPort());
        });
    }

    @Test
    void connectionServiceManagesListenerLifecycleAndPendingApprovals() throws Throwable {
        JavaFxToolkitExtension.runOnFxThread(() -> {
            SettingsService settings = new SettingsService();
            AtomicBoolean tlsEnabled = new AtomicBoolean(false);
            AtomicBoolean serverClosed = new AtomicBoolean(false);

            DeviceInfoService deviceInfo = new DeviceInfoService(settings) {
                @Override
                public boolean isTlsConfigured() {
                    return tlsEnabled.get();
                }
            };

            ConnectionService connection = new ConnectionService(
                settings,
                deviceInfo,
                (port, handler) -> () -> serverClosed.set(true)
            );

            assertEquals(ConnectionState.STANDBY, connection.getStatus().state());
            assertFalse(connection.startReceiver(), "Starting receiver without TLS must fail cleanly");
            assertEquals(ConnectionState.TLS_UNCONFIGURED, connection.getStatus().state());

            tlsEnabled.set(true);
            assertTrue(connection.startReceiver());
            assertTrue(connection.isListening());
            assertEquals(ConnectionState.LISTENING, connection.getStatus().state());

            connection.submitPendingRequestForPreview("report.pdf", 2048L, "10.0.0.8");
            assertEquals(1, connection.getPendingRequests().size());
            int reqId = connection.getPendingRequests().get(0).requestId();
            assertTrue(connection.approveRequest(reqId, true));
            assertEquals(0, connection.getPendingRequests().size());

            connection.stopReceiver();
            assertFalse(connection.isListening());
            assertTrue(serverClosed.get());
        });
    }

    @Test
    void transferServiceTracksSuccessFailureAndValidation() throws Exception {
        Path sampleFile = tempDir.resolve("payload.bin");
        Files.write(sampleFile, new byte[256]);

        TransferService okService = new TransferService((host, port, file, listener) -> {
            listener.onStarted(file.getFileName().toString(), 256L);
            listener.onProgress(file.getFileName().toString(), 128L, 256L);
            listener.onCompleted(file.getFileName().toString());
        });

        try {
            assertFalse(okService.validateSendInput("bad host!", "5050", sampleFile.toString()).valid());
            assertFalse(okService.validateSendInput("192.168.1.5", "0", sampleFile.toString()).valid());
            assertFalse(okService.validateSendInput("192.168.1.5", "5050", tempDir.resolve("missing.bin").toString()).valid());
            assertTrue(okService.validateSendInput("192.168.1.5", "5050", sampleFile.toString()).valid());

            TransferItem done = okService.sendFile("192.168.1.5", 5050, sampleFile).get(5, TimeUnit.SECONDS);
            assertEquals(TransferStatus.COMPLETED, done.status());
        } finally {
            okService.shutdown();
        }

        TransferService failingService = new TransferService((host, port, file, listener) -> {
            throw new IOException("Connection refused");
        });
        try {
            TransferItem failed = failingService.sendFile("192.168.1.99", 5050, sampleFile).get(5, TimeUnit.SECONDS);
            assertEquals(TransferStatus.FAILED, failed.status());
            assertTrue(failed.note().contains("Connection refused"));
        } finally {
            failingService.shutdown();
        }
    }

    @Test
    void createDefaultWiresSharedMonitoringAndHistoryAcrossRestartAndShutdown() throws Exception {
        Path historyFile = tempDir.resolve("default-services-history.json");
        String previousProp = System.getProperty(JsonTransferHistoryRepository.PROP_HISTORY_FILE);
        System.setProperty(JsonTransferHistoryRepository.PROP_HISTORY_FILE, historyFile.toString());

        SyncShareServices firstSession = null;
        SyncShareServices restartedSession = null;
        try {
            firstSession = SyncShareServices.createDefault();
            assertNotNull(firstSession.historyService());
            assertSame(
                firstSession.transferMonitoringService(),
                firstSession.transferService().getMonitoringService(),
                "TransferService must share SyncShareServices monitoring instance"
            );
            assertEquals(
                1,
                firstSession.transferMonitoringService().terminalListenerCount(),
                "HistoryService must subscribe once to shared TransferMonitoringService"
            );
            assertTrue(firstSession.historyService().awaitPendingIo(5, TimeUnit.SECONDS));

            // Outgoing monitored transfer completion
            firstSession.transferMonitoringService().onStarted(
                "tx-default-out",
                TransferDirection.OUTGOING,
                "release-notes.pdf",
                4096L,
                0L,
                "127.0.0.1:5050"
            );
            firstSession.transferMonitoringService().onCompleted(
                "tx-default-out",
                TransferDirection.OUTGOING,
                "release-notes.pdf"
            );

            // Incoming completion routed through TransferService -> ConnectionService wiring
            TransferItem incomingDone = new TransferItem(
                "tx-default-in",
                "backup-image.tar",
                8192L,
                8192L,
                TransferDirection.INCOMING,
                TransferStatus.COMPLETED,
                "127.0.0.1:6060",
                "SHA-256 verified",
                Instant.now(),
                "Verified and saved"
            );
            firstSession.transferService().moveToHistory(incomingDone);
            assertTrue(firstSession.historyService().awaitPendingIo(5, TimeUnit.SECONDS));

            firstSession.shutdown();
            assertEquals(0, firstSession.transferMonitoringService().terminalListenerCount());
            firstSession = null;

            restartedSession = SyncShareServices.createDefault();
            assertTrue(restartedSession.historyService().awaitPendingIo(5, TimeUnit.SECONDS));
            List<TransferRecord> reloaded = restartedSession.historyService().getSnapshot();
            assertEquals(2, reloaded.size());
            assertEquals(
                1,
                reloaded.stream()
                    .filter(r -> "tx-default-out".equals(r.transferId())
                        && r.direction() == TransferHistoryDirection.SEND
                        && r.status() == TransferHistoryStatus.COMPLETED)
                    .count()
            );
            assertEquals(
                1,
                reloaded.stream()
                    .filter(r -> "tx-default-in".equals(r.transferId())
                        && r.direction() == TransferHistoryDirection.RECEIVE
                        && r.status() == TransferHistoryStatus.COMPLETED)
                    .count()
            );
        } finally {
            if (firstSession != null) {
                firstSession.shutdown();
            }
            if (restartedSession != null) {
                restartedSession.shutdown();
            }
            if (previousProp == null) {
                System.clearProperty(JsonTransferHistoryRepository.PROP_HISTORY_FILE);
            } else {
                System.setProperty(JsonTransferHistoryRepository.PROP_HISTORY_FILE, previousProp);
            }
        }
    }
}
