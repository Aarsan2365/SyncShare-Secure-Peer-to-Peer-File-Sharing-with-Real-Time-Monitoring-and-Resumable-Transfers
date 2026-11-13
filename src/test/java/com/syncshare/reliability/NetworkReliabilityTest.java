package com.syncshare.reliability;

import com.syncshare.model.TransferItem;
import com.syncshare.model.TransferStatus;
import com.syncshare.service.DeviceInfoService;
import com.syncshare.service.PeerDiscoveryManager;
import com.syncshare.service.SettingsService;
import com.syncshare.service.TransferMonitoringService;
import com.syncshare.service.TransferService;
import com.syncshare.ui.JavaFxToolkitExtension;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import javax.net.ssl.SSLHandshakeException;
import java.net.BindException;
import java.net.ConnectException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(com.syncshare.ui.JavaFxToolkitExtension.class)
class NetworkReliabilityTest {

    @TempDir
    Path tempDir;

    private CentralErrorHandler errorHandler;
    private final CopyOnWriteArrayList<AppError> caughtErrors = new CopyOnWriteArrayList<>();
    private AppErrorListener listener;

    @BeforeEach
    void setUp() {
        errorHandler = CentralErrorHandler.getInstance();
        errorHandler.clearRecentErrors();
        caughtErrors.clear();
        listener = caughtErrors::add;
        errorHandler.addListener(listener);
    }

    @AfterEach
    void tearDown() {
        errorHandler.removeListener(listener);
        errorHandler.clearRecentErrors();
    }

    @Test
    void connectionRefusedClassifiedAndRecordedInHistory() throws Throwable {
        JavaFxToolkitExtension.runOnFxThread(() -> {
            Path file = Files.createFile(tempDir.resolve("sample.bin"));
            TransferMonitoringService monitor = new TransferMonitoringService();
            TransferService service = new TransferService((host, port, f, l) -> {
                throw new ConnectException("Connection refused: connect");
            }, monitor);

            assertThrows(ConnectException.class, () -> service.sendFileBlocking(
                "tx-conn-refused",
                "127.0.0.1",
                59999,
                file,
                null,
                () -> false
            ));

            assertNotNull(errorHandler.getLatestError());
            assertEquals(AppErrorCategory.CONNECTION_FAILED, errorHandler.getLatestError().category());
            assertTrue(caughtErrors.stream().anyMatch(e -> e.category() == AppErrorCategory.CONNECTION_FAILED));

            // Verify history is updated with FAILED state and does NOT simulate success
            assertEquals(0, service.getActiveTransfers().size());
            assertEquals(1, service.getTransferHistory().size());
            TransferItem historyItem = service.getTransferHistory().get(0);
            assertEquals(TransferStatus.FAILED, historyItem.status());
            assertTrue(historyItem.note().contains("Unable to connect"));
        });
    }

    @Test
    void socketTimeoutClassifiedAndMonitored() throws Throwable {
        JavaFxToolkitExtension.runOnFxThread(() -> {
            Path file = Files.createFile(tempDir.resolve("payload.dat"));
            TransferMonitoringService monitor = new TransferMonitoringService();
            TransferService service = new TransferService((host, port, f, l) -> {
                throw new SocketTimeoutException("Read timed out");
            }, monitor);

            assertThrows(SocketTimeoutException.class, () -> service.sendFileBlocking(
                "tx-timeout",
                "192.168.1.100",
                5050,
                file,
                null,
                () -> false
            ));

            assertNotNull(errorHandler.getLatestError());
            assertEquals(AppErrorCategory.TRANSFER_INTERRUPTED, errorHandler.getLatestError().category());

            assertEquals(1, service.getTransferHistory().size());
            assertEquals(TransferStatus.FAILED, service.getTransferHistory().get(0).status());
            assertTrue(service.getTransferHistory().get(0).note().contains("timed out"));
        });
    }

    @Test
    void tlsHandshakeFailureClassifiedAndHandled() throws Throwable {
        JavaFxToolkitExtension.runOnFxThread(() -> {
            Path file = Files.createFile(tempDir.resolve("encrypted.bin"));
            TransferMonitoringService monitor = new TransferMonitoringService();
            TransferService service = new TransferService((host, port, f, l) -> {
                throw new SSLHandshakeException("Received fatal alert: certificate_unknown");
            }, monitor);

            assertThrows(SSLHandshakeException.class, () -> service.sendFileBlocking(
                "tx-tls-fail",
                "127.0.0.1",
                5050,
                file,
                null,
                () -> false
            ));

            assertNotNull(errorHandler.getLatestError());
            assertEquals(AppErrorCategory.CONNECTION_FAILED, errorHandler.getLatestError().category());

            assertEquals(1, service.getTransferHistory().size());
            assertEquals(TransferStatus.FAILED, service.getTransferHistory().get(0).status());
            assertTrue(service.getTransferHistory().get(0).note().contains("Mutual TLS 1.3"));
        });
    }

    @Test
    void unexpectedSocketClosureMidTransferRecordedAsInterrupted() throws Throwable {
        JavaFxToolkitExtension.runOnFxThread(() -> {
            Path file = Files.createFile(tempDir.resolve("broken.dat"));
            TransferMonitoringService monitor = new TransferMonitoringService();
            TransferService service = new TransferService((host, port, f, l) -> {
                l.onStarted("broken.dat", 1024L);
                l.onProgress("broken.dat", 512L, 1024L);
                throw new SocketException("Connection reset by peer");
            }, monitor);

            assertThrows(SocketException.class, () -> service.sendFileBlocking(
                "tx-reset",
                "127.0.0.1",
                5050,
                file,
                null,
                () -> false
            ));

            assertNotNull(errorHandler.getLatestError());
            assertEquals(AppErrorCategory.TRANSFER_INTERRUPTED, errorHandler.getLatestError().category());

            assertEquals(1, service.getTransferHistory().size());
            assertEquals(TransferStatus.FAILED, service.getTransferHistory().get(0).status());
        });
    }

    @Test
    void discoveryServiceBindFailureHandledGracefully() throws Throwable {
        JavaFxToolkitExtension.runOnFxThread(() -> {
            SettingsService settings = new SettingsService();
            DeviceInfoService deviceInfo = new DeviceInfoService(settings);

            PeerDiscoveryManager manager = new PeerDiscoveryManager(
                settings,
                deviceInfo,
                (name, port) -> {
                    throw new BindException("Address already in use: bind");
                }
            );

            manager.start();
            assertFalse(manager.isActive());

            assertNotNull(errorHandler.getLatestError());
            assertEquals(AppErrorCategory.INVALID_CONFIGURATION, errorHandler.getLatestError().category());
            manager.close();
        });
    }
}
