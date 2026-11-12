package com.syncshare.ui;

import com.syncshare.model.TransferDirection;
import com.syncshare.monitoring.MonitoredTransfer;
import com.syncshare.monitoring.TransferMonitorState;
import com.syncshare.service.ConnectionService;
import com.syncshare.service.DeviceInfoService;
import com.syncshare.service.SettingsService;
import com.syncshare.service.SyncShareServices;
import com.syncshare.service.TransferMonitoringService;
import com.syncshare.service.TransferService;
import com.syncshare.ui.controller.ActiveTransfersController;
import com.syncshare.ui.design.DesignSystem;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.io.IOException;
import java.net.URL;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(JavaFxToolkitExtension.class)
class ActiveTransfersControllerTest {

    @Test
    void rendersMultipleConcurrentTransfersWithMetricsAndOutcomes() throws Throwable {
        AtomicLong clock = new AtomicLong(1_000_000_000L);
        TransferMonitoringService monitoring = new TransferMonitoringService(clock::get, 0L, false);
        SyncShareServices services = createServices(monitoring);

        JavaFxToolkitExtension.runOnFxThread(() -> {
            Stage stage = new Stage();
            LoadedScreen loaded = loadController(services);
            ActiveTransfersController controller = loaded.controller();
            try {
                Scene scene = new Scene(loaded.root(), 1080, 720);
                DesignSystem.applyTheme(scene);
                stage.setScene(scene);
                stage.show();

                // Register two concurrent transfers (outgoing & incoming) + one unknown-size transfer
                monitoring.registerQueued("tx-out", TransferDirection.OUTGOING, "release.tar.gz", 10_000L, "10.0.0.21:5050", "Queued");
                monitoring.onStarted("tx-out", TransferDirection.OUTGOING, "release.tar.gz", 10_000L, 0L, "10.0.0.21:5050");
                clock.addAndGet(500_000_000L); // +0.5s -> 5 KB in 0.5s = 10 KB/s
                monitoring.onProgress("tx-out", TransferDirection.OUTGOING, "release.tar.gz", 5_000L, 10_000L);

                monitoring.registerQueued("tx-in", TransferDirection.INCOMING, "stream.dat", -1L, "10.0.0.22:5050", "Queued");
                monitoring.onStarted("tx-in", TransferDirection.INCOMING, "stream.dat", -1L, 0L, "10.0.0.22:5050");
                monitoring.onProgress("tx-in", TransferDirection.INCOMING, "stream.dat", 2_048L, -1L);

                // Zero-byte completed transfer
                monitoring.registerQueued("tx-zero", TransferDirection.OUTGOING, "empty.txt", 0L, "10.0.0.23:5050", "Queued");
                monitoring.onStarted("tx-zero", TransferDirection.OUTGOING, "empty.txt", 0L, 0L, "10.0.0.23:5050");
                monitoring.onCompleted("tx-zero", TransferDirection.OUTGOING, "empty.txt");

                loaded.root().applyCss();
                loaded.root().layout();

                assertEquals(2, controller.getActiveTable().getItems().size());
                assertEquals(1, controller.getRecentTable().getItems().size());
                assertEquals("2 Active", controller.getCountBadge().getText());
                assertFalse(controller.getErrorBanner().isVisible());

                MonitoredTransfer outgoingRow = controller.getActiveTable().getItems().stream()
                    .filter(t -> t.transferId().equals("tx-out"))
                    .findFirst()
                    .orElseThrow();
                assertEquals("50%", outgoingRow.formattedPercentage());
                assertTrue(outgoingRow.speedBytesPerSecond() > 0);
                assertTrue(outgoingRow.estimatedRemainingSeconds() > 0);
                assertNotNull(outgoingRow.startedAt());

                MonitoredTransfer unknownSizeRow = controller.getActiveTable().getItems().stream()
                    .filter(t -> t.transferId().equals("tx-in"))
                    .findFirst()
                    .orElseThrow();
                assertEquals("Unknown", unknownSizeRow.formattedTotalSize());
                assertEquals("--", unknownSizeRow.formattedPercentage());

                MonitoredTransfer zeroRow = controller.getRecentTable().getItems().get(0);
                assertEquals(TransferMonitorState.COMPLETED, zeroRow.state());
                assertEquals("100%", zeroRow.formattedPercentage());
                assertNotNull(zeroRow.completedAt());

                // Filter matches active + recent consistently
                controller.getSearchField().setText("stream");
                assertEquals(1, controller.getActiveTable().getItems().size());
                assertEquals(0, controller.getRecentTable().getItems().size());
                controller.getSearchField().clear();
                assertEquals(2, controller.getActiveTable().getItems().size());
                assertEquals(1, controller.getRecentTable().getItems().size());

                // Trigger a failure and confirm error banner surfaces exact diagnostic
                monitoring.onFailed("tx-out", TransferDirection.OUTGOING, "release.tar.gz", "Peer closed TLS socket", new IOException("Peer closed TLS socket"));
                assertEquals(1, controller.getActiveTable().getItems().size());
                assertEquals(2, controller.getRecentTable().getItems().size());
                assertTrue(controller.getErrorBanner().isVisible());
                assertTrue(controller.getErrorBanner().getTitle().contains("release.tar.gz"));
                assertTrue(controller.getErrorBanner().getMessage().contains("Peer closed TLS socket"));

                // Dismiss banner and ensure unrelated progress doesn't reopen acknowledged failure banner
                controller.getErrorBanner().dismiss();
                assertFalse(controller.getErrorBanner().isVisible());
                monitoring.onProgress("tx-in", TransferDirection.INCOMING, "stream.dat", 4_096L, -1L);
                assertFalse(controller.getErrorBanner().isVisible());
            } finally {
                controller.dispose();
                stage.close();
                services.shutdown();
            }
        });
    }

    @Test
    void backgroundUpdatesMarshalSafelyAndDisposeRemovesSubscription() throws Throwable {
        TransferMonitoringService monitoring = new TransferMonitoringService(System::nanoTime, 0L, false);
        SyncShareServices services = createServices(monitoring);

        ActiveTransfersController[] holder = new ActiveTransfersController[1];
        Stage[] stageHolder = new Stage[1];

        JavaFxToolkitExtension.runOnFxThread(() -> {
            LoadedScreen loaded = loadController(services);
            ActiveTransfersController controller = loaded.controller();
            // Repeated initializeContext must not duplicate subscriptions
            controller.initializeContext(services, null);
            assertEquals(1, monitoring.listenerCount());

            Stage stage = new Stage();
            Scene scene = new Scene(loaded.root(), 960, 640);
            DesignSystem.applyTheme(scene);
            stage.setScene(scene);
            stage.show();

            holder[0] = controller;
            stageHolder[0] = stage;
        });

        try {
            AtomicBoolean cancelledByHandler = new AtomicBoolean(false);
            CountDownLatch workerDone = new CountDownLatch(1);
            Thread worker = new Thread(() -> {
                try {
                    assertFalse(Platform.isFxApplicationThread());
                    monitoring.registerQueued("bg-tx", TransferDirection.OUTGOING, "video.mp4", 4_000L, "192.168.1.90:5050", "Queued");
                    monitoring.registerCancelHandler("bg-tx", () -> cancelledByHandler.set(true));
                    monitoring.onStarted("bg-tx", TransferDirection.OUTGOING, "video.mp4", 4_000L, 0L, "192.168.1.90:5050");
                    monitoring.onProgress("bg-tx", TransferDirection.OUTGOING, "video.mp4", 2_000L, 4_000L);
                } finally {
                    workerDone.countDown();
                }
            }, "test-bg-transfer");
            worker.start();
            assertTrue(workerDone.await(5, TimeUnit.SECONDS));

            // Drain FX queue and inspect UI state
            JavaFxToolkitExtension.runOnFxThread(() -> {
                ActiveTransfersController controller = holder[0];
                assertEquals(1, controller.getActiveTable().getItems().size());
                assertEquals("50%", controller.getActiveTable().getItems().get(0).formattedPercentage());

                assertTrue(services.transferService().cancelActiveTransfer("bg-tx"));
                assertTrue(cancelledByHandler.get());
                assertEquals(0, controller.getActiveTable().getItems().size());
                assertEquals(1, controller.getRecentTable().getItems().size());
                assertEquals(TransferMonitorState.CANCELLED, controller.getRecentTable().getItems().get(0).state());

                controller.dispose();
                assertEquals(0, monitoring.listenerCount());
            });
        } finally {
            JavaFxToolkitExtension.runOnFxThread(() -> {
                if (stageHolder[0] != null) {
                    stageHolder[0].close();
                }
            });
            services.shutdown();
        }
    }

    private static SyncShareServices createServices(TransferMonitoringService monitoring) {
        SettingsService settings = new SettingsService();
        DeviceInfoService deviceInfo = new DeviceInfoService(settings);
        ConnectionService connection = new ConnectionService(settings, deviceInfo, monitoring);
        TransferService transfer = new TransferService(monitoring);
        return new SyncShareServices(settings, deviceInfo, connection, transfer, monitoring);
    }

    private record LoadedScreen(Parent root, ActiveTransfersController controller) {
    }

    private static LoadedScreen loadController(SyncShareServices services) throws IOException {
        URL fxmlUrl = ActiveTransfersController.class.getResource("/com/syncshare/ui/views/active-transfers-view.fxml");
        assertNotNull(fxmlUrl);
        FXMLLoader loader = new FXMLLoader(fxmlUrl);
        Parent root = loader.load();
        ActiveTransfersController controller = loader.getController();
        controller.initializeContext(services, null);
        return new LoadedScreen(root, controller);
    }
}
