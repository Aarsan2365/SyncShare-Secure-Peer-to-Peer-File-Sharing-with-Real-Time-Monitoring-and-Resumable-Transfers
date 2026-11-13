package com.syncshare.reliability;

import com.syncshare.model.AppSettings;
import com.syncshare.model.TransferItem;
import com.syncshare.model.TransferStatus;
import com.syncshare.service.ReceiveDestinationValidator;
import com.syncshare.service.SettingsRepository;
import com.syncshare.service.SettingsService;
import com.syncshare.service.SettingsValidator;
import com.syncshare.service.TransferMonitoringService;
import com.syncshare.service.TransferService;
import com.syncshare.ui.JavaFxToolkitExtension;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(com.syncshare.ui.JavaFxToolkitExtension.class)
class StartupAndFileReliabilityTest {

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
    void settingsServiceHandlesCorruptedConfigurationGracefully() throws Throwable {
        JavaFxToolkitExtension.runOnFxThread(() -> {
            SettingsRepository corruptedRepo = new SettingsRepository() {
                @Override
                public LoadResult load() {
                    return new LoadResult(AppSettings.defaults(), false, true, java.util.List.of());
                }

                @Override
                public void save(AppSettings settings) {
                }

                @Override
                public Path storageLocation() {
                    return tempDir.resolve("settings.json");
                }
            };

            SettingsService service = new SettingsService(corruptedRepo, true);
            assertNotNull(service.getSettings());
            assertTrue(service.getLastPersistenceError().contains("corrupted"));
            assertNotNull(errorHandler.getLatestError());
            assertEquals(AppErrorCategory.INVALID_CONFIGURATION, errorHandler.getLatestError().category());
            assertTrue(caughtErrors.stream().anyMatch(e -> e.category() == AppErrorCategory.INVALID_CONFIGURATION));
        });
    }

    @Test
    void settingsServiceHandlesIoExceptionOnLoad() throws Throwable {
        JavaFxToolkitExtension.runOnFxThread(() -> {
            SettingsRepository failingRepo = new SettingsRepository() {
                @Override
                public LoadResult load() throws IOException {
                    throw new IOException("Simulated disk read failure");
                }

                @Override
                public void save(AppSettings settings) {
                }

                @Override
                public Path storageLocation() {
                    return tempDir.resolve("settings.json");
                }
            };

            SettingsService service = new SettingsService(failingRepo, true);
            assertNotNull(service.getSettings());
            assertTrue(service.getLastPersistenceError().contains("Failed to load settings"));
            assertNotNull(errorHandler.getLatestError());
            assertEquals(AppErrorCategory.INVALID_CONFIGURATION, errorHandler.getLatestError().category());
            assertTrue(caughtErrors.stream().anyMatch(e -> e.category() == AppErrorCategory.INVALID_CONFIGURATION));
        });
    }

    @Test
    void transferServiceInspectsMissingAndInvalidFiles() throws IOException {
        TransferService service = new TransferService();

        TransferService.FileSummary missingSummary = service.inspectSelectedFile(tempDir.resolve("non-existent.txt").toString());
        assertFalse(missingSummary.valid());
        assertEquals("Missing", missingSummary.statusLabel());

        Path dir = Files.createDirectory(tempDir.resolve("sub-folder"));
        TransferService.FileSummary dirSummary = service.inspectSelectedFile(dir.toString());
        assertFalse(dirSummary.valid());
        assertEquals("Not a File", dirSummary.statusLabel());

        TransferService.FileSummary emptySummary = service.inspectSelectedFile("   ");
        assertFalse(emptySummary.valid());
    }

    @Test
    void transferServiceAbortsAndClassifiesMissingFileBeforeNetworkAttempt() throws Throwable {
        JavaFxToolkitExtension.runOnFxThread(() -> {
            TransferService service = new TransferService((host, port, file, listener) -> {});
            Path missing = tempDir.resolve("missing-payload.bin");

            assertThrows(NoSuchFileException.class, () -> service.sendFileBlocking(
                "transfer-missing-test",
                "127.0.0.1",
                9999,
                missing,
                null,
                () -> false
            ));

            assertNotNull(errorHandler.getLatestError());
            assertEquals(AppErrorCategory.FILE_NOT_FOUND, errorHandler.getLatestError().category());
            assertTrue(caughtErrors.stream().anyMatch(e -> e.category() == AppErrorCategory.FILE_NOT_FOUND));
        });
    }

    @Test
    void receiveDestinationStorageCapacityValidation() throws IOException {
        Path validDir = Files.createDirectory(tempDir.resolve("rx-dir"));

        assertTrue(ReceiveDestinationValidator.hasUsableSpace(validDir, 1024L));
        ReceiveDestinationValidator.ValidationResult result = ReceiveDestinationValidator.validateStorageCapacity(validDir, 1024L);
        assertTrue(result.valid());

        // Extremely large allocation beyond standard disk space (e.g. 100 Petabytes)
        long excessiveBytes = 100_000_000_000_000_000L;
        assertFalse(ReceiveDestinationValidator.hasUsableSpace(validDir, excessiveBytes));
        ReceiveDestinationValidator.ValidationResult excessiveResult = ReceiveDestinationValidator.validateStorageCapacity(validDir, excessiveBytes);
        assertFalse(excessiveResult.valid());
        assertTrue(excessiveResult.message().contains("Insufficient storage"));
    }

    @Test
    void receiveDestinationDirectoryProbingAndValidation() throws IOException {
        Path validDir = Files.createDirectory(tempDir.resolve("valid-dest"));
        ReceiveDestinationValidator.ValidationResult okResult = ReceiveDestinationValidator.validateDirectory(validDir);
        assertTrue(okResult.valid());

        Path fileNotDir = Files.createFile(tempDir.resolve("some-file.txt"));
        ReceiveDestinationValidator.ValidationResult fileResult = ReceiveDestinationValidator.validateDirectory(fileNotDir);
        assertFalse(fileResult.valid());
        assertTrue(fileResult.message().contains("not a directory"));

        ReceiveDestinationValidator.ValidationResult nullResult = ReceiveDestinationValidator.createAndValidateDirectory(null);
        assertFalse(nullResult.valid());

        ReceiveDestinationValidator.ValidationResult invalidNul = ReceiveDestinationValidator.createAndValidateDirectory("path\0invalid");
        assertFalse(invalidNul.valid());
    }

    @Test
    void unsafePathTraversalDetection() {
        assertFalse(ReceiveDestinationValidator.isSafeSingleFileName("../evil.sh"));
        assertFalse(ReceiveDestinationValidator.isSafeSingleFileName("folder/file.txt"));
        assertFalse(ReceiveDestinationValidator.isSafeSingleFileName("folder\\file.txt"));
        assertFalse(ReceiveDestinationValidator.isSafeSingleFileName("file:stream"));
        assertFalse(ReceiveDestinationValidator.isSafeSingleFileName("."));
        assertFalse(ReceiveDestinationValidator.isSafeSingleFileName(".."));
        assertTrue(ReceiveDestinationValidator.isSafeSingleFileName("safe_archive-2026.zip"));
    }
}
