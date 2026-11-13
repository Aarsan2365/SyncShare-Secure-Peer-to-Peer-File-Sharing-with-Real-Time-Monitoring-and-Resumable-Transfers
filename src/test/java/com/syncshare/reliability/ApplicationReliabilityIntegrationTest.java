package com.syncshare.reliability;

import com.syncshare.history.JsonTransferHistoryRepository;
import com.syncshare.history.TransferHistoryDirection;
import com.syncshare.history.TransferHistoryStatus;
import com.syncshare.history.TransferRecord;
import com.syncshare.model.AppSettings;
import com.syncshare.model.TransferItem;
import com.syncshare.model.TransferStatus;
import com.syncshare.service.ConnectionService;
import com.syncshare.service.DeviceInfoService;
import com.syncshare.service.HistoryService;
import com.syncshare.service.JsonSettingsRepository;
import com.syncshare.service.ReceiveDestinationValidator;
import com.syncshare.service.ReceiveListenerService;
import com.syncshare.service.SettingsService;
import com.syncshare.service.TransferMonitoringService;
import com.syncshare.service.TransferService;
import com.syncshare.ui.JavaFxToolkitExtension;
import com.syncshare.ui.controller.SendFileTransferService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import javax.net.ssl.SSLHandshakeException;
import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketException;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end reliability verification test suite covering all 16 Phase 8 requirements:
 * 1. Invalid inputs
 * 2. Connection failures
 * 3. TLS errors
 * 4. Missing files
 * 5. File permission failures
 * 6. Inaccessible directories
 * 7. Insufficient disk space where testable
 * 8. Interrupted transfers
 * 9. Unexpected exceptions
 * 10. User-friendly error mapping
 * 11. Logging output and sensitive-data redaction
 * 12. Application shutdown during active transfer
 * 13. Executor termination
 * 14. Listener cleanup
 * 15. History and settings persistence after shutdown
 * 16. Existing regression behavior
 */
@ExtendWith(com.syncshare.ui.JavaFxToolkitExtension.class)
class ApplicationReliabilityIntegrationTest {

    @TempDir
    Path tempDir;

    private CentralErrorHandler errorHandler;
    private final CopyOnWriteArrayList<AppError> errorSink = new CopyOnWriteArrayList<>();
    private AppErrorListener listener;

    @BeforeEach
    void setUp() {
        errorHandler = CentralErrorHandler.getInstance();
        errorHandler.clearRecentErrors();
        errorSink.clear();
        listener = errorSink::add;
        errorHandler.addListener(listener);
    }

    @AfterEach
    void tearDown() {
        errorHandler.removeListener(listener);
        errorHandler.clearRecentErrors();
    }

    @Test
    @DisplayName("1. Invalid input validation before network operations")
    void test1_invalidInputs() {
        TransferMonitoringService monitor = new TransferMonitoringService();
        TransferService service = new TransferService(monitor);

        TransferService.ValidationResult res1 = service.validateSendInput("not an ip or host!!", "5050", "some-file.txt");
        assertFalse(res1.valid());
        assertNotNull(res1.peerMessage());

        TransferService.ValidationResult res2 = service.validateSendInput("192.168.1.10", "invalid-port", "some-file.txt");
        assertFalse(res2.valid());
        assertNotNull(res2.portMessage());

        TransferService.ValidationResult res3 = service.validateSendInput("192.168.1.10", "5050", "");
        assertFalse(res3.valid());
        assertNotNull(res3.fileMessage());
    }

    @Test
    @DisplayName("2. Connection failure classification and user notification")
    void test2_connectionFailures() throws Throwable {
        Path file = Files.createFile(tempDir.resolve("conn-test.bin"));
        TransferMonitoringService monitor = new TransferMonitoringService();
        TransferService service = new TransferService((host, port, f, l) -> {
            throw new ConnectException("Connection refused: connect");
        }, monitor);

        TransferItem item = service.sendFile("127.0.0.1", 5099, file).get(5, TimeUnit.SECONDS);
        assertEquals(TransferStatus.FAILED, item.status());

        AppError error = errorHandler.getLatestError();
        assertNotNull(error);
        assertEquals(AppErrorCategory.CONNECTION_FAILED, error.category());
        assertTrue(error.userMessage().contains("Unable to connect to destination peer"));
        service.shutdown();
    }

    @Test
    @DisplayName("3. TLS handshake and security certificate error classification")
    void test3_tlsErrors() throws Throwable {
        Path file = Files.createFile(tempDir.resolve("tls-test.bin"));
        TransferMonitoringService monitor = new TransferMonitoringService();
        TransferService service = new TransferService((host, port, f, l) -> {
            throw new SSLHandshakeException("Received fatal alert: bad_certificate");
        }, monitor);

        TransferItem item = service.sendFile("127.0.0.1", 5050, file).get(5, TimeUnit.SECONDS);
        assertEquals(TransferStatus.FAILED, item.status());

        AppError error = errorHandler.getLatestError();
        assertNotNull(error);
        assertEquals(AppErrorCategory.CONNECTION_FAILED, error.category());
        assertTrue(error.userMessage().contains("TLS") || error.userMessage().contains("certificate"));
        service.shutdown();
    }

    @Test
    @DisplayName("4. Missing file pre-check failure before transport execution")
    void test4_missingFileHandling() throws Throwable {
        Path ghostFile = tempDir.resolve("ghost-file.txt");
        TransferMonitoringService monitor = new TransferMonitoringService();
        TransferService service = new TransferService(monitor);

        TransferItem item = service.sendFile("127.0.0.1", 5050, ghostFile).get(5, TimeUnit.SECONDS);
        assertEquals(TransferStatus.FAILED, item.status());

        AppError error = errorHandler.getLatestError();
        assertNotNull(error);
        assertEquals(AppErrorCategory.FILE_NOT_FOUND, error.category());
        assertTrue(error.userMessage().contains("File not found"));
        service.shutdown();
    }

    @Test
    @DisplayName("5. File permission failures during transfer")
    void test5_filePermissionFailures() throws Throwable {
        Path file = Files.createFile(tempDir.resolve("perm-test.bin"));
        TransferMonitoringService monitor = new TransferMonitoringService();
        TransferService service = new TransferService((host, port, f, l) -> {
            throw new AccessDeniedException(f.toString(), null, "Access is denied");
        }, monitor);

        TransferItem item = service.sendFile("127.0.0.1", 5050, file).get(5, TimeUnit.SECONDS);
        assertEquals(TransferStatus.FAILED, item.status());

        AppError error = errorHandler.getLatestError();
        assertNotNull(error);
        assertEquals(AppErrorCategory.PERMISSION_DENIED, error.category());
        assertTrue(error.userMessage().contains("Permission denied"));
        service.shutdown();
    }

    @Test
    @DisplayName("6. Inaccessible receive destination validation")
    void test6_inaccessibleDirectories() {
        Path invalidDir = tempDir.resolve("non_existent_folder_abc").resolve("deep_nested");
        ReceiveDestinationValidator.ValidationResult res = ReceiveDestinationValidator.validateDirectory(invalidDir);
        assertFalse(res.valid());

        AppError error = errorHandler.handleError(new java.nio.file.NotDirectoryException(invalidDir.toString()), invalidDir.toString());
        assertNotNull(error);
        assertEquals(AppErrorCategory.DESTINATION_UNAVAILABLE, error.category());
    }

    @Test
    @DisplayName("7. Insufficient disk space validation")
    void test7_insufficientDiskSpace() {
        long gigabytesToDemand = 100_000_000_000_000_000L; // 100 Petabytes
        ReceiveDestinationValidator.ValidationResult capResult = ReceiveDestinationValidator.validateStorageCapacity(tempDir, gigabytesToDemand);
        assertFalse(capResult.valid());

        AppError error = errorHandler.handleError(new IOException("Insufficient storage: not enough space on destination drive"), tempDir.toString());
        assertNotNull(error);
        assertEquals(AppErrorCategory.INSUFFICIENT_STORAGE, error.category());
    }

    @Test
    @DisplayName("8. Interrupted network transfers update monitoring and error handler")
    void test8_interruptedTransfers() throws Throwable {
        Path file = Files.createFile(tempDir.resolve("reset-test.bin"));
        TransferMonitoringService monitor = new TransferMonitoringService();
        TransferService service = new TransferService((host, port, f, l) -> {
            throw new SocketException("Connection reset by peer");
        }, monitor);

        TransferItem item = service.sendFile("127.0.0.1", 5050, file).get(5, TimeUnit.SECONDS);
        assertEquals(TransferStatus.FAILED, item.status());

        AppError error = errorHandler.getLatestError();
        assertNotNull(error);
        assertEquals(AppErrorCategory.TRANSFER_INTERRUPTED, error.category());
        service.shutdown();
    }

    @Test
    @DisplayName("9. Unexpected exceptions classification")
    void test9_unexpectedExceptions() {
        RuntimeException unhandled = new RuntimeException("Unexpected memory heap corruption");
        AppError error = errorHandler.handleError(unhandled, "subsystem-x");

        assertNotNull(error);
        assertEquals(AppErrorCategory.UNEXPECTED_ERROR, error.category());
        assertFalse(error.recoverable());
    }

    @Test
    @DisplayName("10. User-friendly error message resolution")
    void test10_userFriendlyErrorMapping() {
        String msg1 = ErrorMessageResolver.resolveUserMessage(AppErrorCategory.FILE_NOT_FOUND, new NoSuchFileException("C:/secret/doc.pdf"), "doc.pdf");
        assertTrue(msg1.contains("selected file could not be located"));

        String msg2 = ErrorMessageResolver.resolveUserMessage(AppErrorCategory.CONNECTION_FAILED, new ConnectException("Connection refused"), "192.168.1.5:5050");
        assertTrue(msg2.contains("Unable to connect to destination peer"));
    }

    @Test
    @DisplayName("11. Logging output and sensitive-data redaction")
    void test11_loggingSensitiveDataRedaction() {
        String leaked = "TLS setup failed password=SuperSecretToken123 with key -----BEGIN PRIVATE KEY----- ABCDEF -----END PRIVATE KEY-----";
        String redacted = ReliabilityLogger.redactSensitiveData(leaked);

        assertFalse(redacted.contains("SuperSecretToken123"));
        assertFalse(redacted.contains("ABCDEF"));
        assertTrue(redacted.contains("password=[REDACTED]"));
        assertTrue(redacted.contains("[REDACTED_PRIVATE_KEY]"));
    }

    @Test
    @DisplayName("12. Application shutdown during active transfer")
    void test12_shutdownDuringTransfer() throws Throwable {
        JavaFxToolkitExtension.runOnFxThread(() -> {
            Path file = Files.createFile(tempDir.resolve("active-shutdown.dat"));
            TransferMonitoringService monitor = new TransferMonitoringService();
            CountDownLatch started = new CountDownLatch(1);

            TransferService service = new TransferService((h, p, f, l) -> {
                started.countDown();
                try {
                    Thread.sleep(10_000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new java.io.InterruptedIOException("Cancelled");
                }
            }, monitor);

            CompletableFuture<TransferItem> future = service.sendFile("127.0.0.1", 5050, file);
            assertTrue(started.await(3, TimeUnit.SECONDS));

            service.shutdown();
            assertTrue(future.isDone());
            assertEquals(TransferStatus.CANCELLED, future.join().status());
        });
    }

    @Test
    @DisplayName("13. Executor termination across services")
    void test13_executorTermination() throws Throwable {
        JavaFxToolkitExtension.runOnFxThread(() -> {
            SendFileTransferService sendService = new SendFileTransferService();
            sendService.shutdown();

            ReceiveListenerService rxService = new ReceiveListenerService(new ConnectionService(new SettingsService(), new DeviceInfoService(new SettingsService())));
            rxService.dispose();
        });
    }

    @Test
    @DisplayName("14. Listener cleanup without leaks")
    void test14_listenerCleanup() {
        AppErrorListener tempListener = e -> {};
        assertTrue(errorHandler.addListener(tempListener));
        assertFalse(errorHandler.addListener(tempListener)); // Duplicate prevented
        assertTrue(errorHandler.removeListener(tempListener));
        assertFalse(errorHandler.removeListener(tempListener));
    }

    @Test
    @DisplayName("15. History and settings persistence intact after shutdown")
    void test15_historyAndSettingsPersistence() throws Throwable {
        JavaFxToolkitExtension.runOnFxThread(() -> {
            Path settingsFile = tempDir.resolve("settings-e2e.json");
            Path historyFile = tempDir.resolve("history-e2e.json");

            SettingsService settings = new SettingsService(new JsonSettingsRepository(settingsFile), true);
            AppSettings custom = settings.getSettings().toBuilder()
                .deviceAlias("E2E-Device")
                .listeningPort(5080)
                .build();
            assertTrue(settings.updateSettings(custom).valid());

            TransferMonitoringService monitor = new TransferMonitoringService();
            HistoryService history = new HistoryService(new JsonTransferHistoryRepository(historyFile), settings, monitor);
            TransferRecord record = new TransferRecord(
                UUID.randomUUID().toString(),
                "test-document.pdf",
                1024L,
                TransferHistoryDirection.SEND,
                "127.0.0.1:5050",
                Instant.now().minusSeconds(10),
                java.time.Duration.ofSeconds(1),
                TransferHistoryStatus.COMPLETED,
                "",
                "Verified via SHA-256"
            );
            assertTrue(history.recordCompletedTransfer(record));
            assertTrue(history.awaitPendingIo(3, TimeUnit.SECONDS));

            // Shutdown both services
            settings.shutdown();
            history.shutdown();

            // Reload and verify
            SettingsService reloadedSettings = new SettingsService(new JsonSettingsRepository(settingsFile), true);
            assertEquals("E2E-Device", reloadedSettings.getSettings().deviceAlias());
            assertEquals(5080, reloadedSettings.getSettings().listeningPort());
            reloadedSettings.shutdown();

            HistoryService reloadedHistory = new HistoryService(new JsonTransferHistoryRepository(historyFile), new SettingsService(), new TransferMonitoringService());
            reloadedHistory.loadSync();
            assertTrue(reloadedHistory.getSnapshot().stream().anyMatch(r -> r.fileName().equals("test-document.pdf")));
            reloadedHistory.shutdown();
        });
    }

    @Test
    @DisplayName("16. Regression behavior: successful transfer completes and updates history")
    void test16_successfulTransferRegression() throws Throwable {
        Path file = Files.createFile(tempDir.resolve("success.dat"));
        Files.writeString(file, "SyncShare Reliability Success Data");
        TransferMonitoringService monitor = new TransferMonitoringService();

        TransferService service = new TransferService((host, port, f, l) -> {
            l.onStarted("success.dat", Files.size(f));
            l.onProgress("success.dat", Files.size(f), Files.size(f));
            l.onCompleted("success.dat");
        }, monitor);

        CompletableFuture<TransferItem> future = service.sendFile("127.0.0.1", 5050, file);
        TransferItem completed = future.get(5, TimeUnit.SECONDS);

        assertNotNull(completed);
        assertEquals(TransferStatus.COMPLETED, completed.status());

        JavaFxToolkitExtension.runOnFxThread(() -> {
            assertEquals(1, service.getTransferHistory().size());
            assertEquals(TransferStatus.COMPLETED, service.getTransferHistory().get(0).status());
        });
        service.shutdown();
    }
}
