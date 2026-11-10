package com.syncshare.service;

import com.syncshare.model.AppSettings;
import com.syncshare.model.ConnectionState;
import com.syncshare.model.LocalDeviceInfo;
import com.syncshare.model.TransferItem;
import com.syncshare.model.TransferStatus;
import com.syncshare.ui.JavaFxToolkitExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
}
