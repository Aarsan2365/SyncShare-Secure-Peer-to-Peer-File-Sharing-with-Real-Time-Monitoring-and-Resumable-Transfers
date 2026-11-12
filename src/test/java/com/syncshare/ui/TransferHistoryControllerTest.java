package com.syncshare.ui;

import com.syncshare.history.JsonTransferHistoryRepository;
import com.syncshare.history.TransferHistoryDirection;
import com.syncshare.history.TransferHistoryStatus;
import com.syncshare.history.TransferRecord;
import com.syncshare.model.TransferDirection;
import com.syncshare.service.ConnectionService;
import com.syncshare.service.DeviceInfoService;
import com.syncshare.service.HistoryService;
import com.syncshare.service.SettingsService;
import com.syncshare.service.SyncShareServices;
import com.syncshare.service.TransferMonitoringService;
import com.syncshare.service.TransferService;
import com.syncshare.ui.controller.TransferHistoryController;
import com.syncshare.ui.design.DesignSystem;
import com.syncshare.ui.design.EmptyStatePane;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.TableColumn;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URL;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(JavaFxToolkitExtension.class)
class TransferHistoryControllerTest {

    @TempDir
    Path tempDir;

    @Test
    void displaysAllColumnsFiltersSortsRefreshesAndSafelyConfirmsClear() throws Throwable {
        Path historyFile = tempDir.resolve("transfer-history.json");
        JsonTransferHistoryRepository repo = new JsonTransferHistoryRepository(historyFile);
        TransferMonitoringService monitoring = new TransferMonitoringService(System::nanoTime, 0L, false);
        SettingsService settings = new SettingsService();
        DeviceInfoService deviceInfo = new DeviceInfoService(settings);
        ConnectionService connection = new ConnectionService(settings, deviceInfo, monitoring);
        TransferService transfers = new TransferService(monitoring);
        HistoryService historyService = new HistoryService(repo, settings, monitoring);
        SyncShareServices services = new SyncShareServices(
            settings, deviceInfo, connection, transfers, monitoring, historyService
        );

        TransferRecord olderCompleted = new TransferRecord(
            "tx-1",
            "architecture-notes.pdf",
            2048L,
            TransferHistoryDirection.RECEIVE,
            "192.168.1.10:5050",
            Instant.parse("2026-11-12T08:00:00Z"),
            Duration.ofSeconds(4),
            TransferHistoryStatus.COMPLETED,
            "received_files",
            "SHA-256 verified"
        );
        TransferRecord middleFailed = new TransferRecord(
            "tx-2",
            "dataset-chunk.tar",
            8192L,
            TransferHistoryDirection.SEND,
            "192.168.1.11:5050",
            Instant.parse("2026-11-12T09:00:00Z"),
            Duration.ofSeconds(9),
            TransferHistoryStatus.FAILED,
            "",
            "Peer closed connection"
        );
        TransferRecord newestCancelled = new TransferRecord(
            "tx-3",
            "demo-video.mp4",
            0L,
            TransferHistoryDirection.SEND,
            "192.168.1.12:5050",
            Instant.parse("2026-11-12T10:00:00Z"),
            Duration.ofSeconds(1),
            TransferHistoryStatus.CANCELLED,
            "",
            "Cancelled by user"
        );

        try {
            historyService.loadSync();
            historyService.recordTransferAsync(olderCompleted).get(5, TimeUnit.SECONDS);
            historyService.recordTransferAsync(middleFailed).get(5, TimeUnit.SECONDS);
            historyService.recordTransferAsync(newestCancelled).get(5, TimeUnit.SECONDS);

            // Seed an active monitoring transfer and verify Clear History never touches it
            monitoring.onStarted("tx-live", TransferDirection.OUTGOING, "live-upload.zip", 10_000L, 1_000L, "10.0.0.5:5050");

            JavaFxToolkitExtension.runOnFxThread(() -> {
                Stage stage = new Stage();
                LoadedScreen loaded = loadController(services);
                TransferHistoryController controller = loaded.controller();
                try {
                    Scene scene = new Scene(loaded.root(), 1080, 720);
                    DesignSystem.applyTheme(scene);
                    stage.setScene(scene);
                    stage.show();
                    loaded.root().applyCss();
                    loaded.root().layout();

                    // Verify 8 required columns
                    assertEquals(
                        List.of("FILE", "SIZE", "DIRECTION", "SENDER / RECEIVER", "DATE & TIME", "DURATION", "DESTINATION", "STATUS"),
                        controller.getHistoryTable().getColumns().stream().map(TableColumn::getText).toList()
                    );
                    assertEquals(3, controller.getHistoryTable().getItems().size());
                    assertEquals("3 Items", controller.getHistoryCountBadge().getText());
                    assertFalse(controller.getClearHistoryButton().isDisabled());
                    assertFalse(controller.getStatusBanner().isVisible());

                    // Verify default sort is newest first
                    assertEquals(
                        List.of("tx-3", "tx-2", "tx-1"),
                        controller.getHistoryTable().getItems().stream().map(TransferRecord::transferId).toList()
                    );

                    // Switch to Oldest First
                    controller.getSortOrderComboBox().setValue(HistoryService.SortOrder.OLDEST_FIRST);
                    assertEquals(
                        List.of("tx-1", "tx-2", "tx-3"),
                        controller.getHistoryTable().getItems().stream().map(TransferRecord::transferId).toList()
                    );

                    // Status filters
                    ((Button) loaded.root().lookup("#historyFilterCompletedButton")).fire();
                    assertEquals(1, controller.getHistoryTable().getItems().size());
                    assertEquals("tx-1", controller.getHistoryTable().getItems().get(0).transferId());

                    ((Button) loaded.root().lookup("#historyFilterFailedButton")).fire();
                    assertEquals(1, controller.getHistoryTable().getItems().size());
                    assertEquals("tx-2", controller.getHistoryTable().getItems().get(0).transferId());

                    ((Button) loaded.root().lookup("#historyFilterCancelledButton")).fire();
                    assertEquals(1, controller.getHistoryTable().getItems().size());
                    assertEquals("tx-3", controller.getHistoryTable().getItems().get(0).transferId());

                    ((Button) loaded.root().lookup("#historyFilterAllButton")).fire();
                    assertEquals(3, controller.getHistoryTable().getItems().size());

                    // Filename search + no-match placeholder
                    controller.getSearchField().setText("dataset");
                    assertEquals(1, controller.getHistoryTable().getItems().size());
                    controller.getSearchField().setText("does-not-exist");
                    assertEquals(0, controller.getHistoryTable().getItems().size());
                    assertTrue(controller.getHistoryTable().getPlaceholder() instanceof EmptyStatePane);
                    controller.resetFilters();
                    assertEquals(3, controller.getHistoryTable().getItems().size());

                    // Verify confirmation dialog has Cancel + Confirm actions and permanent deletion text
                    Alert confirmDialog = controller.createClearHistoryConfirmationDialog(3);
                    assertEquals(2, confirmDialog.getButtonTypes().size());
                    assertEquals(ButtonBar.ButtonData.CANCEL_CLOSE, confirmDialog.getButtonTypes().get(0).getButtonData());
                    assertEquals(ButtonBar.ButtonData.OK_DONE, confirmDialog.getButtonTypes().get(1).getButtonData());
                    assertTrue(confirmDialog.getContentText().contains("Permanently remove 3"));

                    // Cancelling confirmation preserves all history
                    AtomicBoolean promptInvoked = new AtomicBoolean(false);
                    controller.setClearConfirmationOverride(() -> {
                        promptInvoked.set(true);
                        return false;
                    });
                    controller.getClearHistoryButton().fire();
                    assertTrue(promptInvoked.get());
                    assertEquals(3, historyService.getSnapshot().size());
                    assertEquals(3, controller.getHistoryTable().getItems().size());
                } finally {
                    controller.dispose();
                    stage.close();
                }
            });

            // Now append a 4th item directly via repository on disk and trigger Refresh button in a fresh view
            TransferRecord externalRecord = new TransferRecord(
                "tx-4",
                "external-refresh.zip",
                512L,
                TransferHistoryDirection.RECEIVE,
                "192.168.1.99:5050",
                Instant.parse("2026-11-12T11:00:00Z"),
                Duration.ofSeconds(2),
                TransferHistoryStatus.COMPLETED,
                "received_files"
            );
            repo.save(List.of(olderCompleted, middleFailed, newestCancelled, externalRecord));

            TransferHistoryController[] holder = new TransferHistoryController[1];
            Stage[] stageHolder = new Stage[1];
            JavaFxToolkitExtension.runOnFxThread(() -> {
                Stage stage = new Stage();
                LoadedScreen loaded = loadController(services);
                holder[0] = loaded.controller();
                stageHolder[0] = stage;
                Scene scene = new Scene(loaded.root(), 1080, 720);
                DesignSystem.applyTheme(scene);
                stage.setScene(scene);
                stage.show();

                loaded.controller().getRefreshButton().fire();
            });

            assertTrue(historyService.awaitPendingIo(5, TimeUnit.SECONDS));

            JavaFxToolkitExtension.runOnFxThread(() -> {
                TransferHistoryController controller = holder[0];
                try {
                    assertEquals(4, controller.getHistoryTable().getItems().size());

                    // Confirmed clear removes saved history safely while preserving live monitoring
                    controller.setClearConfirmationOverride(() -> true);
                    controller.getClearHistoryButton().fire();
                } finally {
                    if (stageHolder[0] != null) {
                        stageHolder[0].close();
                    }
                    controller.dispose();
                }
            });

            assertTrue(historyService.awaitPendingIo(5, TimeUnit.SECONDS));
            assertTrue(historyService.getSnapshot().isEmpty());
            assertTrue(repo.load().records().isEmpty());
            assertEquals(1, monitoring.getActiveSnapshot().size(), "Active monitoring transfers must never be cleared");
        } finally {
            services.shutdown();
        }
    }

    @Test
    void failedClearInUiPreservesValidHistoryAndShowsErrorBanner() throws Throwable {
        Path historyFile = tempDir.resolve("transfer-history.json");
        TransferRecord record = new TransferRecord(
            "tx-preserve",
            "critical-backup.tar.gz",
            4096L,
            TransferHistoryDirection.SEND,
            "10.0.0.4:5050",
            Instant.parse("2026-11-12T12:30:00Z"),
            Duration.ofSeconds(3),
            TransferHistoryStatus.COMPLETED,
            ""
        );

        JsonTransferHistoryRepository seedRepo = new JsonTransferHistoryRepository(historyFile);
        seedRepo.save(List.of(record));

        JsonTransferHistoryRepository failingClearRepo = new JsonTransferHistoryRepository(historyFile) {
            @Override
            public void clear() throws IOException {
                throw new IOException("Disk permission denied");
            }
        };

        TransferMonitoringService monitoring = new TransferMonitoringService(System::nanoTime, 0L, false);
        SettingsService settings = new SettingsService();
        DeviceInfoService deviceInfo = new DeviceInfoService(settings);
        ConnectionService connection = new ConnectionService(settings, deviceInfo, monitoring);
        TransferService transfers = new TransferService(monitoring);
        HistoryService historyService = new HistoryService(failingClearRepo, settings, monitoring);
        SyncShareServices services = new SyncShareServices(
            settings, deviceInfo, connection, transfers, monitoring, historyService
        );

        TransferHistoryController[] holder = new TransferHistoryController[1];
        Stage[] stageHolder = new Stage[1];

        try {
            historyService.loadSync();

            JavaFxToolkitExtension.runOnFxThread(() -> {
                Stage stage = new Stage();
                LoadedScreen loaded = loadController(services);
                holder[0] = loaded.controller();
                stageHolder[0] = stage;
                Scene scene = new Scene(loaded.root(), 1080, 720);
                DesignSystem.applyTheme(scene);
                stage.setScene(scene);
                stage.show();

                loaded.controller().setClearConfirmationOverride(() -> true);
                loaded.controller().getClearHistoryButton().fire();
            });

            assertTrue(historyService.awaitPendingIo(5, TimeUnit.SECONDS));

            JavaFxToolkitExtension.runOnFxThread(() -> {
                TransferHistoryController controller = holder[0];
                try {
                    assertEquals(1, controller.getHistoryTable().getItems().size());
                    assertTrue(controller.getStatusBanner().isVisible());
                    assertTrue(controller.getStatusBanner().getMessage().contains("Disk permission denied"));
                } finally {
                    controller.dispose();
                    if (stageHolder[0] != null) {
                        stageHolder[0].close();
                    }
                }
            });

            assertEquals(List.of(record), seedRepo.load().records());
        } finally {
            services.shutdown();
        }
    }

    private record LoadedScreen(Parent root, TransferHistoryController controller) {
    }

    private static LoadedScreen loadController(SyncShareServices services) throws IOException {
        URL fxmlUrl = TransferHistoryController.class.getResource("/com/syncshare/ui/views/transfer-history-view.fxml");
        assertNotNull(fxmlUrl);
        FXMLLoader loader = new FXMLLoader(fxmlUrl);
        Parent root = loader.load();
        TransferHistoryController controller = loader.getController();
        controller.initializeContext(services, null);
        return new LoadedScreen(root, controller);
    }
}
