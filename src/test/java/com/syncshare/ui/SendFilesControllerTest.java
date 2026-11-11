package com.syncshare.ui;

import com.syncshare.model.NavItemId;
import com.syncshare.model.TransferItem;
import com.syncshare.model.TransferStatus;
import com.syncshare.service.ConnectionService;
import com.syncshare.service.DeviceInfoService;
import com.syncshare.service.SettingsService;
import com.syncshare.service.SyncShareServices;
import com.syncshare.service.TransferService;
import com.syncshare.ui.controller.SendFilesController;
import com.syncshare.ui.design.FeedbackBanner;
import com.syncshare.ui.design.LabeledProgressBar;
import com.syncshare.ui.design.SemanticTone;
import com.syncshare.ui.design.StatusBadge;
import com.syncshare.ui.design.ValidatedTextField;
import javafx.application.Platform;
import javafx.geometry.Bounds;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import java.net.ConnectException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(JavaFxToolkitExtension.class)
class SendFilesControllerTest {

    @TempDir
    Path tempDir;

    private record SendScreenFixture(
        Stage stage,
        SyncShareServices services,
        Parent root,
        SendFilesController controller,
        ValidatedTextField hostField,
        ValidatedTextField portField,
        ValidatedTextField fileField,
        Button browseBtn,
        Button validateBtn,
        Button clearBtn,
        Button cancelBtn,
        Button sendBtn,
        Label selectedFileName,
        Label selectedFileSize,
        StatusBadge selectedFileBadge,
        StatusBadge connectionBadge,
        Label connectionLabel,
        Label stageDetailLabel,
        LabeledProgressBar progressBar,
        FeedbackBanner feedbackBanner
    ) {
    }

    @Test
    void fileSelectionAndReadableFileSizeDisplay() throws Throwable {
        Path zeroFile = tempDir.resolve("empty.txt");
        Files.createFile(zeroFile);
        Path kbFile = tempDir.resolve("document.pdf");
        Files.write(kbFile, new byte[1536]); // 1.5 KB
        Path mbFile = tempDir.resolve("archive.bin");
        Files.write(mbFile, new byte[2 * 1024 * 1024]); // 2.0 MB

        AtomicInteger transports = new AtomicInteger(0);
        SendScreenFixture fx = openSendScreen(true, (h, p, f, l) -> transports.incrementAndGet());
        try {
            JavaFxToolkitExtension.runOnFxThread(() -> {
                assertEquals("No file selected", fx.selectedFileName().getText());
                assertEquals("--", fx.selectedFileSize().getText());
                assertEquals("No File", fx.selectedFileBadge().getText());

                FileChooser chooser = fx.controller().buildFileChooser();
                assertNotNull(chooser.getTitle());
                assertFalse(chooser.getExtensionFilters().isEmpty());

                // Choose 0 B file via FileChooser hook
                fx.controller().setFileChooserHandler(owner -> zeroFile.toFile());
                fx.browseBtn().fire();
                assertEquals(zeroFile.toAbsolutePath().toString(), fx.fileField().getText());
                assertEquals("empty.txt", fx.selectedFileName().getText());
                assertEquals("0 B", fx.selectedFileSize().getText());
                assertEquals("Ready", fx.selectedFileBadge().getText());

                // Choose 1.5 KB file
                fx.controller().setFileChooserHandler(owner -> kbFile.toFile());
                fx.browseBtn().fire();
                assertEquals("document.pdf", fx.selectedFileName().getText());
                assertEquals("1.5 KB", fx.selectedFileSize().getText());
                assertEquals("Ready", fx.selectedFileBadge().getText());

                // Cancel dialog (returns null) preserves current valid selection
                fx.controller().setFileChooserHandler(owner -> null);
                fx.browseBtn().fire();
                assertEquals("document.pdf", fx.selectedFileName().getText());
                assertEquals("1.5 KB", fx.selectedFileSize().getText());

                // Choose 2.0 MB file
                fx.controller().setFileChooserHandler(owner -> mbFile.toFile());
                fx.browseBtn().fire();
                assertEquals("archive.bin", fx.selectedFileName().getText());
                assertEquals("2.0 MB", fx.selectedFileSize().getText());
                assertEquals("Ready", fx.selectedFileBadge().getText());
            });
            assertEquals(0, transports.get());
        } finally {
            closeFixture(fx);
        }
    }

    @Test
    void validationRejectsInvalidIpPortAndMissingOrDeletedFileWithoutSending() throws Throwable {
        Path ephemeralFile = tempDir.resolve("ephemeral.txt");
        Files.writeString(ephemeralFile, "hello");

        AtomicInteger transportCalls = new AtomicInteger(0);
        SendScreenFixture fx = openSendScreen(true, (h, p, f, l) -> transportCalls.incrementAndGet());
        try {
            JavaFxToolkitExtension.runOnFxThread(() -> {
                // Invalid IP & invalid port & directory instead of file
                fx.hostField().setText("999.999.999.999");
                fx.portField().setText("70000");
                fx.fileField().setText(tempDir.toString());

                fx.sendBtn().fire();

                assertEquals(SemanticTone.ERROR, fx.hostField().getValidationTone());
                assertEquals(SemanticTone.ERROR, fx.portField().getValidationTone());
                assertEquals(SemanticTone.ERROR, fx.fileField().getValidationTone());
                assertEquals("Not a File", fx.selectedFileBadge().getText());
                assertEquals(SemanticTone.ERROR, fx.feedbackBanner().getTone());
                assertEquals(0, transportCalls.get());

                // Non-numeric port & partial IP
                fx.hostField().setText("192.168.1");
                fx.portField().setText("abc");
                fx.validateBtn().fire();
                assertEquals(SemanticTone.ERROR, fx.hostField().getValidationTone());
                assertEquals(SemanticTone.ERROR, fx.portField().getValidationTone());

                // Select valid file, delete from disk, then try sending
                fx.hostField().setText("127.0.0.1");
                fx.portField().setText("5050");
                fx.controller().setFileChooserHandler(owner -> ephemeralFile.toFile());
                fx.browseBtn().fire();
                assertEquals("Ready", fx.selectedFileBadge().getText());
            });

            Files.deleteIfExists(ephemeralFile);

            JavaFxToolkitExtension.runOnFxThread(() -> {
                fx.sendBtn().fire();
                assertEquals(SemanticTone.ERROR, fx.fileField().getValidationTone());
                assertEquals("Missing", fx.selectedFileBadge().getText());
                assertEquals(SemanticTone.ERROR, fx.feedbackBanner().getTone());
                assertFalse(fx.controller().isTransferInProgress());
            });
            assertEquals(0, transportCalls.get());
        } finally {
            closeFixture(fx);
        }
    }

    @Test
    void connectionFailureAndUnconfiguredTlsReportMeaningfulErrors() throws Throwable {
        Path validFile = tempDir.resolve("sample.bin");
        Files.write(validFile, new byte[512]);

        // 1. Unconfigured TLS blocks send cleanly with warning
        AtomicInteger noTlsCalls = new AtomicInteger(0);
        SendScreenFixture noTlsFx = openSendScreen(false, (h, p, f, l) -> noTlsCalls.incrementAndGet());
        try {
            JavaFxToolkitExtension.runOnFxThread(() -> {
                assertEquals("TLS Setup", noTlsFx.connectionBadge().getText());
                noTlsFx.hostField().setText("192.168.1.42");
                noTlsFx.portField().setText("5050");
                noTlsFx.fileField().setText(validFile.toString());
                noTlsFx.sendBtn().fire();

                assertEquals(SemanticTone.WARNING, noTlsFx.feedbackBanner().getTone());
                assertTrue(noTlsFx.feedbackBanner().getTitle().contains("TLS"));
                assertFalse(noTlsFx.sendBtn().isDisabled());
            });
            assertEquals(0, noTlsCalls.get());
        } finally {
            closeFixture(noTlsFx);
        }

        // 2. Real ConnectException during send updates status, error banner, history & controls
        SendScreenFixture fx = openSendScreen(true, (h, p, f, l) -> {
            ConnectException refused = new ConnectException("Connection refused");
            l.onError(f.getFileName().toString(), refused);
            throw refused;
        });
        try {
            JavaFxToolkitExtension.runOnFxThread(() -> {
                fx.hostField().setText("192.168.1.99");
                fx.portField().setText("5050");
                fx.fileField().setText(validFile.toString());
                fx.sendBtn().fire();
            });

            waitForFxCondition(() -> !fx.controller().isTransferInProgress());

            JavaFxToolkitExtension.runOnFxThread(() -> {
                assertEquals("Failed", fx.connectionBadge().getText());
                assertEquals(SemanticTone.ERROR, fx.feedbackBanner().getTone());
                assertEquals("Transfer Failed", fx.feedbackBanner().getTitle());
                assertTrue(fx.feedbackBanner().getMessage().contains("192.168.1.99:5050"));
                assertTrue(fx.feedbackBanner().getMessage().contains("Connection refused"));
                assertFalse(fx.sendBtn().isDisabled());
                assertTrue(fx.cancelBtn().isDisabled());
                assertEquals(0, fx.services().transferService().getActiveTransfers().size());
                assertEquals(1, fx.services().transferService().getTransferHistory().size());
                assertEquals(TransferStatus.FAILED, fx.services().transferService().getTransferHistory().get(0).status());
            });
        } finally {
            closeFixture(fx);
        }
    }

    @Test
    void realProgressAndCompletionHappenOnlyAfterBackendVerification() throws Throwable {
        Path payload = tempDir.resolve("verified.bin");
        Files.write(payload, new byte[1000]);

        CountDownLatch enterProgressGate = new CountDownLatch(1);
        CountDownLatch enterAllBytesGate = new CountDownLatch(1);
        CountDownLatch releaseVerifyGate = new CountDownLatch(1);

        SendScreenFixture fx = openSendScreen(true, (h, p, f, l) -> {
            assertTrue(enterProgressGate.await(5, TimeUnit.SECONDS));
            l.onStarted(f.getFileName().toString(), 1000L);
            l.onProgress(f.getFileName().toString(), 400L, 1000L);

            assertTrue(enterAllBytesGate.await(5, TimeUnit.SECONDS));
            l.onProgress(f.getFileName().toString(), 1000L, 1000L);

            assertTrue(releaseVerifyGate.await(5, TimeUnit.SECONDS));
            l.onCompleted(f.getFileName().toString());
        });

        try {
            JavaFxToolkitExtension.runOnFxThread(() -> {
                fx.hostField().setText("192.168.1.42");
                fx.portField().setText("5050");
                fx.fileField().setText(payload.toString());
                fx.sendBtn().fire();
            });

            // Phase 1: connecting/awaiting approval -> indeterminate progress, NOT Verified
            waitForFxCondition(() -> "Connecting".equals(fx.connectionBadge().getText()));
            JavaFxToolkitExtension.runOnFxThread(() -> {
                assertTrue(fx.progressBar().getProgressBar().getProgress() < 0);
                assertTrue(fx.sendBtn().isDisabled());
                assertFalse(fx.cancelBtn().isDisabled());
                assertFalse("Transfer Verified".equals(fx.feedbackBanner().getTitle()));
            });

            // Phase 2: partial chunk ACK progress -> 40%
            enterProgressGate.countDown();
            waitForFxCondition(() -> Math.abs(fx.progressBar().getProgressBar().getProgress() - 0.4) < 0.05);
            JavaFxToolkitExtension.runOnFxThread(() -> {
                assertEquals("Transferring", fx.connectionBadge().getText());
                assertTrue(fx.stageDetailLabel().getText().contains("192.168.1.42:5050")
                    || fx.progressBar().getProgressBar().getProgress() > 0.35);
                assertFalse("Transfer Verified".equals(fx.feedbackBanner().getTitle()));
            });

            // Phase 3: all bytes ACKed, awaiting remote SHA-256 verification -> STILL not Verified!
            enterAllBytesGate.countDown();
            waitForFxCondition(() -> "Verifying".equals(fx.connectionBadge().getText()));
            JavaFxToolkitExtension.runOnFxThread(() -> {
                assertEquals("Verifying Integrity", fx.connectionLabel().getText());
                assertFalse("Transfer Verified".equals(fx.feedbackBanner().getTitle()));
                assertTrue(fx.sendBtn().isDisabled());
            });

            // Phase 4: backend calls onCompleted -> Verified!
            releaseVerifyGate.countDown();
            waitForFxCondition(() -> !fx.controller().isTransferInProgress());
            JavaFxToolkitExtension.runOnFxThread(() -> {
                assertEquals("Verified", fx.connectionBadge().getText());
                assertEquals(SemanticTone.SUCCESS, fx.feedbackBanner().getTone());
                assertEquals("Transfer Verified", fx.feedbackBanner().getTitle());
                assertEquals(1.0, fx.progressBar().getProgressBar().getProgress(), 0.0001);
                assertFalse(fx.sendBtn().isDisabled());
                assertTrue(fx.cancelBtn().isDisabled());
                assertEquals(TransferStatus.COMPLETED, fx.services().transferService().getTransferHistory().get(0).status());
            });
        } finally {
            enterProgressGate.countDown();
            enterAllBytesGate.countDown();
            releaseVerifyGate.countDown();
            closeFixture(fx);
        }
    }

    @Test
    void peerRejectionShowsRejectedAndNeverClaimsCompletion() throws Throwable {
        Path payload = tempDir.resolve("rejected.bin");
        Files.write(payload, new byte[256]);

        SendScreenFixture fx = openSendScreen(true, (h, p, f, l) -> {
            // Receiver sent Protocol.REJECT so FileSender returned without onCompleted/onError
        });
        try {
            JavaFxToolkitExtension.runOnFxThread(() -> {
                fx.hostField().setText("192.168.1.50");
                fx.portField().setText("5050");
                fx.fileField().setText(payload.toString());
                fx.sendBtn().fire();
            });

            waitForFxCondition(() -> !fx.controller().isTransferInProgress());

            JavaFxToolkitExtension.runOnFxThread(() -> {
                assertEquals("Rejected", fx.connectionBadge().getText());
                assertEquals(SemanticTone.ERROR, fx.feedbackBanner().getTone());
                assertEquals("Offer Rejected", fx.feedbackBanner().getTitle());
                assertFalse(fx.sendBtn().isDisabled());
                assertEquals(TransferStatus.REJECTED, fx.services().transferService().getTransferHistory().get(0).status());
            });
        } finally {
            closeFixture(fx);
        }
    }

    @Test
    void uiRemainsResponsiveAndPreventsRepeatedSubmissionsWhileNetworkingBlocks() throws Throwable {
        Path payload = tempDir.resolve("blocking.bin");
        Files.write(payload, new byte[2048]);

        CountDownLatch inNetworkLatch = new CountDownLatch(1);
        CountDownLatch releaseNetworkLatch = new CountDownLatch(1);
        AtomicInteger callCount = new AtomicInteger(0);
        AtomicBoolean executedOnFxThread = new AtomicBoolean(true);

        SendScreenFixture fx = openSendScreen(true, (h, p, f, l) -> {
            callCount.incrementAndGet();
            executedOnFxThread.set(Platform.isFxApplicationThread());
            l.onStarted(f.getFileName().toString(), 2048L);
            l.onProgress(f.getFileName().toString(), 512L, 2048L);
            inNetworkLatch.countDown();
            assertTrue(releaseNetworkLatch.await(5, TimeUnit.SECONDS));
            l.onCompleted(f.getFileName().toString());
        });

        try {
            JavaFxToolkitExtension.runOnFxThread(() -> {
                fx.hostField().setText("127.0.0.1");
                fx.portField().setText("5050");
                fx.fileField().setText(payload.toString());
                fx.sendBtn().fire();
            });

            assertTrue(inNetworkLatch.await(5, TimeUnit.SECONDS));
            assertFalse(executedOnFxThread.get(), "Blocking send must never run on FX Application Thread");

            // Verify FX Application Thread responds promptly while network thread is still blocked
            long startNanos = System.nanoTime();
            AtomicBoolean fxResponsive = new AtomicBoolean(false);
            JavaFxToolkitExtension.runOnFxThread(() -> {
                assertTrue(fx.sendBtn().isDisabled(), "Send button must be disabled while active");
                assertTrue(fx.browseBtn().isDisabled());
                assertTrue(fx.validateBtn().isDisabled());
                assertTrue(fx.clearBtn().isDisabled());
                assertFalse(fx.cancelBtn().isDisabled());

                // Attempt accidental repeated submissions
                fx.sendBtn().fire();
                fx.sendBtn().fire();
                fxResponsive.set(true);
            });
            long fxLatencyMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
            assertTrue(fxResponsive.get());
            assertTrue(fxLatencyMs < 1000, "FX thread appeared stalled (" + fxLatencyMs + " ms)");
            assertEquals(1, callCount.get(), "Repeated submission must not invoke transport twice");

            releaseNetworkLatch.countDown();
            waitForFxCondition(() -> !fx.controller().isTransferInProgress());

            JavaFxToolkitExtension.runOnFxThread(() -> {
                assertFalse(fx.sendBtn().isDisabled(), "Send button must re-enable after completion");
                assertTrue(fx.cancelBtn().isDisabled());
            });
        } finally {
            releaseNetworkLatch.countDown();
            closeFixture(fx);
        }
    }

    @Test
    void cancellationAbortsTransferCleansUpAndSuppressesLateUpdates() throws Throwable {
        Path payload = tempDir.resolve("cancellable.bin");
        Files.write(payload, new byte[4096]);

        CountDownLatch inTransferLatch = new CountDownLatch(1);
        CountDownLatch allowLateCallbacksLatch = new CountDownLatch(1);
        CountDownLatch transportFinishedLatch = new CountDownLatch(1);

        SendScreenFixture fx = openSendScreen(true, (h, p, f, l) -> {
            try {
                l.onStarted(f.getFileName().toString(), 4096L);
                l.onProgress(f.getFileName().toString(), 1024L, 4096L);
                inTransferLatch.countDown();
                try {
                    allowLateCallbacksLatch.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) {
                    // Continue waiting so late callbacks below explicitly run after FX cancellation
                    allowLateCallbacksLatch.await(5, TimeUnit.SECONDS);
                }
                // Late callbacks emitted after cancellation must NOT overwrite cancelled state
                l.onProgress(f.getFileName().toString(), 4096L, 4096L);
                l.onCompleted(f.getFileName().toString());
            } finally {
                transportFinishedLatch.countDown();
            }
        });

        try {
            JavaFxToolkitExtension.runOnFxThread(() -> {
                fx.hostField().setText("127.0.0.1");
                fx.portField().setText("5050");
                fx.fileField().setText(payload.toString());
                fx.sendBtn().fire();
            });

            assertTrue(inTransferLatch.await(5, TimeUnit.SECONDS));
            waitForFxCondition(() -> !fx.services().transferService().getActiveTransfers().isEmpty());

            JavaFxToolkitExtension.runOnFxThread(() -> fx.cancelBtn().fire());

            waitForFxCondition(() ->
                !fx.controller().isTransferInProgress()
                    && "Cancelled".equals(fx.connectionBadge().getText())
            );

            // Allow late callbacks on the background worker and confirm no corrupted history/UI
            allowLateCallbacksLatch.countDown();
            assertTrue(transportFinishedLatch.await(5, TimeUnit.SECONDS));

            JavaFxToolkitExtension.runOnFxThread(() -> {
                assertEquals("Cancelled", fx.connectionBadge().getText());
                assertEquals(SemanticTone.WARNING, fx.feedbackBanner().getTone());
                assertEquals("Transfer Cancelled", fx.feedbackBanner().getTitle());
                assertFalse(fx.sendBtn().isDisabled());
                assertTrue(fx.cancelBtn().isDisabled());
                assertEquals(0, fx.services().transferService().getActiveTransfers().size());
                assertEquals(1, fx.services().transferService().getTransferHistory().size());
                TransferItem item = fx.services().transferService().getTransferHistory().get(0);
                assertEquals(TransferStatus.FAILED, item.status());
                assertEquals("Cancelled by user", item.note());
            });
        } finally {
            allowLateCallbacksLatch.countDown();
            closeFixture(fx);
        }
    }

    @Test
    void clearFormResetsSelectionValidationAndIdleStatus() throws Throwable {
        Path validFile = tempDir.resolve("reset-me.txt");
        Files.writeString(validFile, "syncshare-reset");

        SendScreenFixture fx = openSendScreen(true, (h, p, f, l) -> {});
        try {
            JavaFxToolkitExtension.runOnFxThread(() -> {
                fx.hostField().setText("bad host!");
                fx.portField().setText("99999");
                fx.fileField().setText(validFile.toString());
                fx.validateBtn().fire();
                assertEquals(SemanticTone.ERROR, fx.hostField().getValidationTone());
                assertEquals("reset-me.txt", fx.selectedFileName().getText());

                fx.clearBtn().fire();

                assertEquals("", fx.hostField().getText());
                assertEquals("5050", fx.portField().getText());
                assertEquals("", fx.fileField().getText());
                assertEquals(SemanticTone.NEUTRAL, fx.hostField().getValidationTone());
                assertEquals(SemanticTone.NEUTRAL, fx.portField().getValidationTone());
                assertEquals(SemanticTone.NEUTRAL, fx.fileField().getValidationTone());
                assertEquals("No file selected", fx.selectedFileName().getText());
                assertEquals("--", fx.selectedFileSize().getText());
                assertEquals("No File", fx.selectedFileBadge().getText());
                assertEquals("Ready", fx.connectionBadge().getText());
            });
        } finally {
            closeFixture(fx);
        }
    }

    @Test
    void responsiveSendFilesScreenNeverOverlapsOrOverflowsAcrossBreakpoints() throws Throwable {
        Path validFile = tempDir.resolve("long-filename-for-responsive-send-files-check.bin");
        Files.write(validFile, new byte[4096]);

        SendScreenFixture fx = openSendScreen(true, (h, p, f, l) -> {});
        try {
            JavaFxToolkitExtension.runOnFxThread(() -> {
                fx.hostField().setText("192.168.1.42");
                fx.portField().setText("5050");
                fx.fileField().setText(validFile.toString());

                double[][] breakpoints = {
                    {SyncShareApplication.MIN_WIDTH, SyncShareApplication.MIN_HEIGHT},
                    {SyncShareApplication.INITIAL_WIDTH, SyncShareApplication.INITIAL_HEIGHT},
                    {1440, 900}
                };

                for (double[] bp : breakpoints) {
                    fx.stage().setWidth(bp[0]);
                    fx.stage().setHeight(bp[1]);
                    fx.root().applyCss();
                    fx.root().layout();

                    double sceneWidth = fx.stage().getScene().getWidth();
                    assertTrue(sceneWidth > 0, "Scene width must be positive at " + bp[0] + "x" + bp[1]);

                    assertPairwiseSeparatedAndWithinScene(
                        List.of(fx.hostField(), fx.portField()),
                        sceneWidth,
                        "endpointGrid",
                        bp[0],
                        bp[1]
                    );
                    assertPairwiseSeparatedAndWithinScene(
                        List.of(fx.fileField(), fx.browseBtn()),
                        sceneWidth,
                        "filePickerRow",
                        bp[0],
                        bp[1]
                    );
                    assertPairwiseSeparatedAndWithinScene(
                        List.of(fx.selectedFileName(), fx.selectedFileSize(), fx.selectedFileBadge()),
                        sceneWidth,
                        "selectedFileSummaryBox",
                        bp[0],
                        bp[1]
                    );
                    assertPairwiseSeparatedAndWithinScene(
                        List.of(fx.clearBtn(), fx.validateBtn(), fx.cancelBtn(), fx.sendBtn()),
                        sceneWidth,
                        "footerActions",
                        bp[0],
                        bp[1]
                    );

                    Bounds progressBounds = fx.progressBar().localToScene(fx.progressBar().getBoundsInLocal());
                    assertTrue(
                        progressBounds.getMaxX() <= sceneWidth + 2.0,
                        "Progress bar overflows scene width at " + bp[0] + "x" + bp[1]
                    );
                }
            });
        } finally {
            closeFixture(fx);
        }
    }

    private static void assertPairwiseSeparatedAndWithinScene(
        List<? extends Node> nodes,
        double sceneWidth,
        String groupName,
        double width,
        double height
    ) {
        for (int i = 0; i < nodes.size(); i++) {
            Node nodeA = nodes.get(i);
            Bounds a = nodeA.localToScene(nodeA.getBoundsInLocal());
            assertTrue(
                a.getMaxX() <= sceneWidth + 2.0,
                groupName + " node " + i + " overflows scene (" + a.getMaxX() + " > " + sceneWidth + ") at " + width + "x" + height
            );
            for (int j = i + 1; j < nodes.size(); j++) {
                Node nodeB = nodes.get(j);
                Bounds b = nodeB.localToScene(nodeB.getBoundsInLocal());
                boolean separatedX = a.getMaxX() <= b.getMinX() + 1.0 || b.getMaxX() <= a.getMinX() + 1.0;
                boolean separatedY = a.getMaxY() <= b.getMinY() + 1.0 || b.getMaxY() <= a.getMinY() + 1.0;
                assertTrue(
                    separatedX || separatedY,
                    groupName + " controls overlap (" + i + " & " + j + ") at " + width + "x" + height
                );
            }
        }
    }

    private SendScreenFixture openSendScreen(
        boolean tlsConfigured,
        TransferService.SendTransport transport
    ) throws Throwable {
        AtomicReference<SendScreenFixture> holder = new AtomicReference<>();
        JavaFxToolkitExtension.runOnFxThread(() -> {
            SettingsService settings = new SettingsService();
            DeviceInfoService deviceInfo = new DeviceInfoService(settings) {
                @Override
                public boolean isTlsConfigured() {
                    return tlsConfigured;
                }
            };
            ConnectionService connection = new ConnectionService(settings, deviceInfo, (port, handler) -> () -> {});
            TransferService transfer = new TransferService(transport);
            SyncShareServices services = new SyncShareServices(settings, deviceInfo, connection, transfer);

            Stage stage = new Stage();
            SyncShareApplication.LoadedView loaded = SyncShareApplication.loadMainView(services);
            Platform.setImplicitExit(false);
            stage.setScene(loaded.scene());
            stage.setWidth(SyncShareApplication.INITIAL_WIDTH);
            stage.setHeight(SyncShareApplication.INITIAL_HEIGHT);
            stage.show();

            MainController main = loaded.controller();
            assertTrue(main.navigateTo(NavItemId.SEND_FILES).success());
            Parent root = loaded.scene().getRoot();
            root.applyCss();
            root.layout();

            SendFilesController sendController = (SendFilesController)
                main.getViewNavigator().getCachedScreen(NavItemId.SEND_FILES).controller();

            holder.set(new SendScreenFixture(
                stage,
                services,
                root,
                sendController,
                (ValidatedTextField) root.lookup("#sendPeerHostField"),
                (ValidatedTextField) root.lookup("#sendPeerPortField"),
                (ValidatedTextField) root.lookup("#sendFilePathField"),
                (Button) root.lookup("#sendBrowseButton"),
                (Button) root.lookup("#sendValidateButton"),
                (Button) root.lookup("#sendClearButton"),
                (Button) root.lookup("#sendCancelButton"),
                (Button) root.lookup("#sendSubmitButton"),
                (Label) root.lookup("#selectedFileNameLabel"),
                (Label) root.lookup("#selectedFileSizeLabel"),
                (StatusBadge) root.lookup("#selectedFileStatusBadge"),
                (StatusBadge) root.lookup("#sendConnectionStatusBadge"),
                (Label) root.lookup("#sendConnectionStatusLabel"),
                (Label) root.lookup("#sendStageDetailLabel"),
                (LabeledProgressBar) root.lookup("#sendProgressBar"),
                (FeedbackBanner) root.lookup("#sendFeedbackBanner")
            ));
        });
        return holder.get();
    }

    private static void closeFixture(SendScreenFixture fx) throws Throwable {
        if (fx == null) {
            return;
        }
        JavaFxToolkitExtension.runOnFxThread(() -> {
            fx.services().shutdown();
            fx.stage().close();
        });
    }

    private static void waitForFxCondition(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            CompletableFuture<Boolean> future = new CompletableFuture<>();
            Platform.runLater(() -> {
                try {
                    future.complete(condition.getAsBoolean());
                } catch (Throwable error) {
                    future.completeExceptionally(error);
                }
            });
            if (Boolean.TRUE.equals(future.get(2, TimeUnit.SECONDS))) {
                return;
            }
            Thread.sleep(15);
        }
        throw new AssertionError("Timed out waiting for JavaFX condition");
    }
}
