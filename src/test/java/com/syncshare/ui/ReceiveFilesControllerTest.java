package com.syncshare.ui;

import com.syncshare.events.ConsoleTransferListener;
import com.syncshare.events.IncomingTransferObserver;
import com.syncshare.model.AppSettings;
import com.syncshare.model.ConnectionState;
import com.syncshare.model.NavItemId;
import com.syncshare.network.PeerServer;
import com.syncshare.security.TlsSupport;
import com.syncshare.service.ConnectionService;
import com.syncshare.service.DeviceInfoService;
import com.syncshare.service.SettingsService;
import com.syncshare.service.SyncShareServices;
import com.syncshare.service.TransferService;
import com.syncshare.transfer.FileSender;
import com.syncshare.ui.design.EmptyStatePane;
import com.syncshare.ui.design.FeedbackBanner;
import com.syncshare.ui.design.LabeledProgressBar;
import com.syncshare.ui.design.SemanticTone;
import com.syncshare.ui.design.StatusBadge;
import com.syncshare.ui.design.ValidatedTextField;
import javafx.application.Platform;
import javafx.geometry.Bounds;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import java.io.IOException;
import java.net.BindException;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

@ExtendWith(JavaFxToolkitExtension.class)
class ReceiveFilesControllerTest {

    private static final char[] TLS_PASSWORD = "syncshare-ui-test-pass".toCharArray();

    private static Path sharedCertDir;
    private static SSLContext receiverContext;
    private static SSLContext senderContext;

    @TempDir
    Path tempDir;

    private String previousReceiveDirProperty;

    @BeforeAll
    static void initMtlsCerts() throws Exception {
        sharedCertDir = Files.createTempDirectory("syncshare-ui-mtls-certs-");
        Path receiverKey = sharedCertDir.resolve("receiver-id.p12");
        Path receiverTrust = sharedCertDir.resolve("receiver-trust.p12");
        Path receiverCert = sharedCertDir.resolve("receiver.cer");
        Path senderKey = sharedCertDir.resolve("sender-id.p12");
        Path senderTrust = sharedCertDir.resolve("sender-trust.p12");
        Path senderCert = sharedCertDir.resolve("sender.cer");

        runKeytool(
            "-genkeypair", "-alias", "receiver",
            "-keyalg", "EC", "-groupname", "secp256r1", "-sigalg", "SHA256withECDSA",
            "-dname", "CN=SyncShare-UI-Receiver, O=SyncShare",
            "-validity", "30",
            "-keystore", receiverKey.toString(), "-storetype", "PKCS12",
            "-storepass", new String(TLS_PASSWORD), "-keypass", new String(TLS_PASSWORD)
        );
        runKeytool(
            "-genkeypair", "-alias", "sender",
            "-keyalg", "EC", "-groupname", "secp256r1", "-sigalg", "SHA256withECDSA",
            "-dname", "CN=SyncShare-UI-Sender, O=SyncShare",
            "-validity", "30",
            "-keystore", senderKey.toString(), "-storetype", "PKCS12",
            "-storepass", new String(TLS_PASSWORD), "-keypass", new String(TLS_PASSWORD)
        );
        runKeytool(
            "-exportcert", "-alias", "receiver", "-rfc",
            "-file", receiverCert.toString(),
            "-keystore", receiverKey.toString(), "-storetype", "PKCS12",
            "-storepass", new String(TLS_PASSWORD)
        );
        runKeytool(
            "-exportcert", "-alias", "sender", "-rfc",
            "-file", senderCert.toString(),
            "-keystore", senderKey.toString(), "-storetype", "PKCS12",
            "-storepass", new String(TLS_PASSWORD)
        );
        runKeytool(
            "-importcert", "-noprompt", "-alias", "sender-peer",
            "-file", senderCert.toString(),
            "-keystore", receiverTrust.toString(), "-storetype", "PKCS12",
            "-storepass", new String(TLS_PASSWORD)
        );
        runKeytool(
            "-importcert", "-noprompt", "-alias", "receiver-peer",
            "-file", receiverCert.toString(),
            "-keystore", senderTrust.toString(), "-storetype", "PKCS12",
            "-storepass", new String(TLS_PASSWORD)
        );

        receiverContext = TlsSupport.createContext(receiverKey, receiverTrust, TLS_PASSWORD);
        senderContext = TlsSupport.createContext(senderKey, senderTrust, TLS_PASSWORD);
    }

    @AfterAll
    static void cleanupCerts() throws IOException {
        if (sharedCertDir != null && Files.exists(sharedCertDir)) {
            try (var paths = Files.walk(sharedCertDir)) {
                for (Path p : paths.sorted(Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(p);
                }
            }
        }
    }

    @BeforeEach
    void saveSystemProperty() {
        previousReceiveDirProperty = System.getProperty("syncshare.receiveDir");
    }

    @AfterEach
    void restoreSystemProperty() {
        if (previousReceiveDirProperty == null) {
            System.clearProperty("syncshare.receiveDir");
        } else {
            System.setProperty("syncshare.receiveDir", previousReceiveDirProperty);
        }
    }

    @Test
    void receiveViewDisplaysCleanEmptyStateValidatedDestinationAndZeroInlineStyles() throws Throwable {
        Path validDest = tempDir.resolve("ui-valid-dest");
        Files.createDirectories(validDest);

        List<String> cssWarnings = new ArrayList<>();
        Logger cssLogger = Logger.getLogger("javafx.css");
        Handler cssHandler = new Handler() {
            @Override
            public void publish(LogRecord record) {
                if (record.getLevel().intValue() >= Level.WARNING.intValue()) {
                    cssWarnings.add(record.getLevel() + ": " + record.getMessage());
                }
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        cssLogger.addHandler(cssHandler);

        int port = findFreePort();
        SyncShareServices services = createMtlsUiServices(port, validDest, true);
        AtomicReference<Stage> stageRef = new AtomicReference<>();

        try {
            JavaFxToolkitExtension.runOnFxThread(() -> {
                Stage stage = new Stage();
                stageRef.set(stage);
                SyncShareApplication.LoadedView loaded = SyncShareApplication.loadMainView(services);
                configureStage(stage, loaded);
                assertTrue(loaded.controller().navigateTo(NavItemId.RECEIVE_FILES).success());

                Parent root = loaded.scene().getRoot();
                root.applyCss();
                root.layout();

                ValidatedTextField dirField = (ValidatedTextField) root.lookup("#receiveDirField");
                StatusBadge listenerBadge = (StatusBadge) root.lookup("#receiveListenerBadge");
                StatusBadge sessionBadge = (StatusBadge) root.lookup("#receiveSessionBadge");
                EmptyStatePane emptyState = (EmptyStatePane) root.lookup("#receiveEmptyState");
                VBox sessionPane = (VBox) root.lookup("#receiveSessionPane");
                Button createBtn = (Button) root.lookup("#receiveCreateDirButton");

                assertNotNull(dirField);
                assertEquals(validDest.toString(), dirField.getText());
                assertEquals(SemanticTone.SUCCESS, dirField.getValidationTone());
                assertEquals("Standby", listenerBadge.getText());
                assertEquals("Waiting", sessionBadge.getText());
                assertTrue(emptyState.isVisible());
                assertFalse(sessionPane.isVisible());
                assertFalse(createBtn.isVisible());
                assertNoInlineStyles(root);
            });
            assertTrue(cssWarnings.isEmpty(), "Unexpected CSS warnings: " + cssWarnings);
        } finally {
            cssLogger.removeHandler(cssHandler);
            closeUi(stageRef, services);
        }
    }

    @Test
    void invalidAndMissingDestinationDirectoriesBlockStartAndAllowFolderCreation() throws Throwable {
        Path validDest = tempDir.resolve("initial-dest");
        Files.createDirectories(validDest);
        Path regularFile = tempDir.resolve("not-a-directory.txt");
        Files.writeString(regularFile, "file-body", StandardCharsets.UTF_8);
        Path missingFolder = tempDir.resolve("created-from-ui");

        int port = findFreePort();
        SyncShareServices services = createMtlsUiServices(port, validDest, false);
        AtomicReference<Stage> stageRef = new AtomicReference<>();
        AtomicReference<Parent> rootRef = new AtomicReference<>();

        try {
            JavaFxToolkitExtension.runOnFxThread(() -> {
                Stage stage = new Stage();
                stageRef.set(stage);
                SyncShareApplication.LoadedView loaded = SyncShareApplication.loadMainView(services);
                configureStage(stage, loaded);
                assertTrue(loaded.controller().navigateTo(NavItemId.RECEIVE_FILES).success());
                rootRef.set(loaded.scene().getRoot());
            });

            // 1. Existing regular file is rejected on Start Receiving
            JavaFxToolkitExtension.runOnFxThread(() -> {
                Parent root = rootRef.get();
                ValidatedTextField dirField = (ValidatedTextField) root.lookup("#receiveDirField");
                Button toggleBtn = (Button) root.lookup("#receiveToggleButton");
                dirField.setText(regularFile.toString());
                toggleBtn.fire();
            });

            waitUntilFx(
                () -> {
                    Parent root = rootRef.get();
                    ValidatedTextField dirField = (ValidatedTextField) root.lookup("#receiveDirField");
                    FeedbackBanner banner = (FeedbackBanner) root.lookup("#receiveFeedbackBanner");
                    return dirField.getValidationTone() == SemanticTone.ERROR
                        && banner.getTone() == SemanticTone.ERROR
                        && !services.connectionService().isListening();
                },
                5_000,
                "Regular file destination must fail validation and prevent listener startup"
            );

            // 2. Non-existent directory reveals Create Folder action cleanly
            JavaFxToolkitExtension.runOnFxThread(() -> {
                Parent root = rootRef.get();
                ValidatedTextField dirField = (ValidatedTextField) root.lookup("#receiveDirField");
                Button validateBtn = (Button) root.lookup("#receiveValidateDirButton");
                dirField.setText(missingFolder.toString());
                validateBtn.fire();

                Button createBtn = (Button) root.lookup("#receiveCreateDirButton");
                assertEquals(SemanticTone.ERROR, dirField.getValidationTone());
                assertTrue(createBtn.isVisible() && createBtn.isManaged());

                createBtn.fire();
                assertTrue(Files.isDirectory(missingFolder));
                assertEquals(SemanticTone.SUCCESS, dirField.getValidationTone());
                assertFalse(createBtn.isVisible());
            });
        } finally {
            closeUi(stageRef, services);
        }
    }

    @Test
    void listenerStartAndStopExecuteAsynchronouslyWithoutBlockingFxThreadAndHandlePortConflict() throws Throwable {
        Path validDest = tempDir.resolve("async-dest");
        Files.createDirectories(validDest);

        int port = findFreePort();
        SettingsService settings = createSettings("AsyncHost", port, validDest, false);
        DeviceInfoService deviceInfo = new DeviceInfoService(settings) {
            @Override
            public boolean isTlsConfigured() {
                return true;
            }
        };
        TransferService transfers = new TransferService();

        CountDownLatch workerEntered = new CountDownLatch(1);
        CountDownLatch releaseWorker = new CountDownLatch(1);
        AtomicInteger factoryCalls = new AtomicInteger(0);
        AtomicBoolean simulateConflict = new AtomicBoolean(false);

        ConnectionService connection = new ConnectionService(
            settings,
            deviceInfo,
            transfers,
            (p, handler, dir, observer) -> {
                if (simulateConflict.get()) {
                    throw new BindException("Address already in use");
                }
                factoryCalls.incrementAndGet();
                workerEntered.countDown();
                try {
                    releaseWorker.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return () -> {};
            }
        );

        SyncShareServices services = new SyncShareServices(settings, deviceInfo, connection, transfers);
        AtomicReference<Stage> stageRef = new AtomicReference<>();
        AtomicReference<Parent> rootRef = new AtomicReference<>();

        try {
            JavaFxToolkitExtension.runOnFxThread(() -> {
                Stage stage = new Stage();
                stageRef.set(stage);
                SyncShareApplication.LoadedView loaded = SyncShareApplication.loadMainView(services);
                configureStage(stage, loaded);
                assertTrue(loaded.controller().navigateTo(NavItemId.RECEIVE_FILES).success());
                rootRef.set(loaded.scene().getRoot());

                Button toggleBtn = (Button) loaded.scene().getRoot().lookup("#receiveToggleButton");
                toggleBtn.fire();
                assertTrue(toggleBtn.isDisabled(), "Toggle button must disable while background start task runs");
            });

            // Wait until background thread is blocked inside startup
            assertTrue(workerEntered.await(5, TimeUnit.SECONDS));

            // Prove FX Application Thread remains immediately responsive while background start is blocked
            CountDownLatch fxResponsive = new CountDownLatch(1);
            Platform.runLater(() -> {
                Button toggleBtn = (Button) rootRef.get().lookup("#receiveToggleButton");
                assertTrue(toggleBtn.getText().contains("Starting"));
                fxResponsive.countDown();
            });
            assertTrue(fxResponsive.await(2, TimeUnit.SECONDS), "JavaFX Application Thread was blocked!");

            releaseWorker.countDown();

            waitUntilFx(
                () -> {
                    Parent root = rootRef.get();
                    Button toggleBtn = (Button) root.lookup("#receiveToggleButton");
                    StatusBadge badge = (StatusBadge) root.lookup("#receiveListenerBadge");
                    return services.connectionService().isListening()
                        && !toggleBtn.isDisabled()
                        && "Stop Receiving".equals(toggleBtn.getText())
                        && "Listening".equals(badge.getText());
                },
                5_000,
                "Receive UI did not transition to active Listening state"
            );
            assertEquals(1, factoryCalls.get());

            // Stop Receiving cleanly
            JavaFxToolkitExtension.runOnFxThread(() -> ((Button) rootRef.get().lookup("#receiveToggleButton")).fire());
            waitUntilFx(
                () -> {
                    Button toggleBtn = (Button) rootRef.get().lookup("#receiveToggleButton");
                    return !services.connectionService().isListening()
                        && !toggleBtn.isDisabled()
                        && "Start Receiving".equals(toggleBtn.getText());
                },
                5_000,
                "Receive UI did not return to Standby after Stop Receiving"
            );

            // Simulate port conflict and click Start Receiving again
            simulateConflict.set(true);
            JavaFxToolkitExtension.runOnFxThread(() -> ((Button) rootRef.get().lookup("#receiveToggleButton")).fire());
            waitUntilFx(
                () -> {
                    Parent root = rootRef.get();
                    FeedbackBanner banner = (FeedbackBanner) root.lookup("#receiveFeedbackBanner");
                    StatusBadge badge = (StatusBadge) root.lookup("#receiveListenerBadge");
                    return services.connectionService().getStatus().state() == ConnectionState.ERROR
                        && banner.getTone() == SemanticTone.ERROR
                        && banner.getMessage().contains("Port " + port + " is already in use")
                        && "Error".equals(badge.getText());
                },
                5_000,
                "Port conflict error was not surfaced in Receive UI"
            );
        } finally {
            releaseWorker.countDown();
            closeUi(stageRef, services);
        }
    }

    @Test
    void realMtlsTransferDrivesLiveReceiveUiApprovalProgressVerifiedCompletionAndResponsiveBreakpoints() throws Throwable {
        Path validDest = tempDir.resolve("e2e-ui-dest");
        Files.createDirectories(validDest);

        Path sourceFile = tempDir.resolve("release-bundle.bin");
        byte[] payload = new byte[192_000];
        new Random(2026L).nextBytes(payload);
        Files.write(sourceFile, payload);

        int port = findFreePort();
        SyncShareServices services = createMtlsUiServices(port, validDest, true);
        AtomicReference<Stage> stageRef = new AtomicReference<>();
        AtomicReference<Parent> rootRef = new AtomicReference<>();
        AtomicReference<Scene> sceneRef = new AtomicReference<>();

        try {
            JavaFxToolkitExtension.runOnFxThread(() -> {
                Stage stage = new Stage();
                stageRef.set(stage);
                SyncShareApplication.LoadedView loaded = SyncShareApplication.loadMainView(services);
                configureStage(stage, loaded);
                assertTrue(loaded.controller().navigateTo(NavItemId.RECEIVE_FILES).success());
                sceneRef.set(loaded.scene());
                rootRef.set(loaded.scene().getRoot());

                ((Button) rootRef.get().lookup("#receiveToggleButton")).fire();
            });

            waitUntilFx(
                () -> services.connectionService().isListening(),
                5_000,
                "Listener failed to start via UI toggle button"
            );

            // Send real file over Mutual TLS 1.3
            AtomicReference<Throwable> senderError = new AtomicReference<>();
            CountDownLatch senderFinished = new CountDownLatch(1);
            Thread senderThread = new Thread(() -> {
                try (SSLSocket socket = TlsSupport.connect("127.0.0.1", port, senderContext)) {
                    socket.setSoTimeout(15_000);
                    FileSender.send(socket, sourceFile, new ConsoleTransferListener());
                } catch (Throwable error) {
                    senderError.set(error);
                } finally {
                    senderFinished.countDown();
                }
            }, "ui-e2e-sender");
            senderThread.start();

            // Wait for pending approval row to render in Receive UI and click its real Accept button
            waitUntilFx(
                () -> {
                    VBox queueBox = (VBox) rootRef.get().lookup("#receiveRequestsListBox");
                    Label fileLabel = (Label) rootRef.get().lookup("#receiveFileNameLabel");
                    Label sizeLabel = (Label) rootRef.get().lookup("#receiveFileSizeLabel");
                    Label senderLabel = (Label) rootRef.get().lookup("#receiveSenderLabel");
                    VBox sessionPane = (VBox) rootRef.get().lookup("#receiveSessionPane");
                    return sessionPane.isVisible()
                        && "release-bundle.bin".equals(fileLabel.getText())
                        && sizeLabel.getText().contains("187.5 KB")
                        && senderLabel.getText().contains("CN=SyncShare-UI-Sender")
                        && !queueBox.getChildren().isEmpty()
                        && !(queueBox.getChildren().get(0) instanceof EmptyStatePane);
                },
                10_000,
                "Incoming file metadata & approval row did not render in Receive UI"
            );

            JavaFxToolkitExtension.runOnFxThread(() -> {
                Button acceptBtn = findButtonByText(rootRef.get().lookup("#receiveRequestsListBox"), "Accept");
                assertNotNull(acceptBtn, "Expected Accept button in pending requests list");
                acceptBtn.fire();
            });

            assertTrue(senderFinished.await(15, TimeUnit.SECONDS), "Sender did not complete");
            assertNull(senderError.get());

            waitUntilFx(
                () -> {
                    Parent root = rootRef.get();
                    StatusBadge sessionBadge = (StatusBadge) root.lookup("#receiveSessionBadge");
                    FeedbackBanner banner = (FeedbackBanner) root.lookup("#receiveFeedbackBanner");
                    Label outcomeLabel = (Label) root.lookup("#receiveOutcomeLabel");
                    LabeledProgressBar progressBar = (LabeledProgressBar) root.lookup("#receiveProgressBar");
                    VBox recentBox = (VBox) root.lookup("#receiveRecentListBox");
                    return "Verified & Saved".equals(sessionBadge.getText())
                        && banner.getTone() == SemanticTone.SUCCESS
                        && "File Received & Verified".equals(banner.getTitle())
                        && outcomeLabel.getText().contains("Saved to")
                        && progressBar.getProgressBar().getProgress() >= 0.999
                        && !recentBox.getChildren().isEmpty()
                        && !(recentBox.getChildren().get(0) instanceof EmptyStatePane);
                },
                10_000,
                "Verified completion state and saved file banner did not appear in Receive UI"
            );

            // Test responsive Receive Files breakpoints (800x560, 1080x720, 1440x900) with populated session data
            double[][] sizes = {
                {SyncShareApplication.MIN_WIDTH, SyncShareApplication.MIN_HEIGHT},
                {SyncShareApplication.INITIAL_WIDTH, SyncShareApplication.INITIAL_HEIGHT},
                {1440, 900}
            };
            JavaFxToolkitExtension.runOnFxThread(() -> {
                Stage stage = stageRef.get();
                Scene scene = sceneRef.get();
                Parent root = rootRef.get();
                GridPane metaGrid = (GridPane) root.lookup("#receiveMetaGrid");
                assertNotNull(metaGrid);

                for (double[] dim : sizes) {
                    stage.setWidth(dim[0]);
                    stage.setHeight(dim[1]);
                    root.applyCss();
                    root.layout();

                    assertNoInlineStyles(root);
                    List<Node> cells = metaGrid.getChildren();
                    for (int i = 0; i < cells.size(); i++) {
                        Bounds a = cells.get(i).localToScene(cells.get(i).getBoundsInLocal());
                        assertTrue(
                            a.getMaxX() <= scene.getWidth() + 2.0,
                            "Receive metadata cell overflows scene at " + dim[0] + "x" + dim[1]
                        );
                        for (int j = i + 1; j < cells.size(); j++) {
                            Bounds b = cells.get(j).localToScene(cells.get(j).getBoundsInLocal());
                            boolean sepX = a.getMaxX() <= b.getMinX() + 1.0 || b.getMaxX() <= a.getMinX() + 1.0;
                            boolean sepY = a.getMaxY() <= b.getMinY() + 1.0 || b.getMaxY() <= a.getMinY() + 1.0;
                            assertTrue(
                                sepX || sepY,
                                "Receive metadata cells overlap at " + dim[0] + "x" + dim[1]
                            );
                        }
                    }
                }
            });
        } finally {
            closeUi(stageRef, services);
        }
    }

    @Test
    void interruptedConnectionShowsFailureBannerAndClearStatusRestoresEmptyState() throws Throwable {
        Path validDest = tempDir.resolve("fail-ui-dest");
        Files.createDirectories(validDest);

        int port = findFreePort();
        SettingsService settings = createSettings("UiHost", port, validDest, false);
        DeviceInfoService deviceInfo = new DeviceInfoService(settings) {
            @Override
            public boolean isTlsConfigured() {
                return true;
            }
        };
        TransferService transfers = new TransferService();
        AtomicReference<IncomingTransferObserver> observerRef = new AtomicReference<>();

        ConnectionService connection = new ConnectionService(
            settings,
            deviceInfo,
            transfers,
            (listenPort, handler, dir, observer) -> {
                observerRef.set(observer);
                return () -> {};
            }
        );

        SyncShareServices services = new SyncShareServices(settings, deviceInfo, connection, transfers);
        AtomicReference<Stage> stageRef = new AtomicReference<>();
        AtomicReference<Parent> rootRef = new AtomicReference<>();

        try {
            JavaFxToolkitExtension.runOnFxThread(() -> {
                Stage stage = new Stage();
                stageRef.set(stage);
                SyncShareApplication.LoadedView loaded = SyncShareApplication.loadMainView(services);
                configureStage(stage, loaded);
                assertTrue(loaded.controller().navigateTo(NavItemId.RECEIVE_FILES).success());
                rootRef.set(loaded.scene().getRoot());
            });

            assertTrue(connection.startReceiving(validDest));
            IncomingTransferObserver obs = observerRef.get();
            assertNotNull(obs);

            obs.onConnectionOpened("tx-err-1", "10.0.0.21:51234", "peer-fp-1");
            obs.onFileOffered("tx-err-1", "broken-stream.iso", 4_000_000L, "10.0.0.21:51234", "peer-fp-1");
            obs.onStarted("tx-err-1", "broken-stream.iso", 4_000_000L, "10.0.0.21:51234", "peer-fp-1");
            obs.onProgress("tx-err-1", "broken-stream.iso", 1_000_000L, 4_000_000L);
            obs.onError("tx-err-1", "broken-stream.iso", "10.0.0.21:51234", new IOException("Connection reset by peer"));

            waitUntilFx(
                () -> {
                    Parent root = rootRef.get();
                    StatusBadge sessionBadge = (StatusBadge) root.lookup("#receiveSessionBadge");
                    FeedbackBanner banner = (FeedbackBanner) root.lookup("#receiveFeedbackBanner");
                    Label outcome = (Label) root.lookup("#receiveOutcomeLabel");
                    Button clearBtn = (Button) root.lookup("#receiveClearSessionButton");
                    return "Failed".equals(sessionBadge.getText())
                        && banner.getTone() == SemanticTone.ERROR
                        && banner.getMessage().contains("Connection reset by peer")
                        && outcome.getText().contains("Connection reset by peer")
                        && clearBtn.isVisible();
                },
                5_000,
                "Interrupted transfer error state was not displayed in Receive UI"
            );

            JavaFxToolkitExtension.runOnFxThread(() -> ((Button) rootRef.get().lookup("#receiveClearSessionButton")).fire());
            waitUntilFx(
                () -> rootRef.get().lookup("#receiveEmptyState").isVisible()
                    && !rootRef.get().lookup("#receiveSessionPane").isVisible(),
                5_000,
                "Clear Status button did not restore clean empty state"
            );
        } finally {
            closeUi(stageRef, services);
        }
    }

    private static SyncShareServices createMtlsUiServices(
        int port,
        Path receiveDir,
        boolean requireApproval
    ) throws Throwable {
        SettingsService settings = createSettings("UI-Receiver-Node", port, receiveDir, requireApproval);
        DeviceInfoService deviceInfo = new DeviceInfoService(settings) {
            @Override
            public boolean isTlsConfigured() {
                return true;
            }
        };
        TransferService transfers = new TransferService();
        ConnectionService connection = new ConnectionService(
            settings,
            deviceInfo,
            transfers,
            (listenPort, handler, dir, observer) -> {
                PeerServer server = new PeerServer(
                    listenPort,
                    handler,
                    new ConsoleTransferListener(),
                    dir,
                    observer,
                    receiverContext
                );
                server.start();
                return server;
            }
        );
        return new SyncShareServices(settings, deviceInfo, connection, transfers);
    }

    private static SettingsService createSettings(
        String alias,
        int port,
        Path receiveDir,
        boolean requireApproval
    ) throws Throwable {
        AtomicReference<SettingsService> ref = new AtomicReference<>();
        JavaFxToolkitExtension.runOnFxThread(() -> {
            SettingsService settings = new SettingsService();
            settings.updateSettings(new AppSettings(
                alias,
                port,
                receiveDir.toString(),
                requireApproval,
                true,
                false
            ));
            ref.set(settings);
        });
        return ref.get();
    }

    private static Button findButtonByText(Node root, String text) {
        if (root instanceof Button button && text.equals(button.getText())) {
            return button;
        }
        if (root instanceof Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) {
                Button match = findButtonByText(child, text);
                if (match != null) {
                    return match;
                }
            }
        }
        return null;
    }

    private static void waitUntilFx(BooleanSupplier conditionOnFx, long timeoutMs, String failureMessage)
        throws Throwable {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            AtomicBoolean matched = new AtomicBoolean(false);
            try {
                JavaFxToolkitExtension.runOnFxThread(() -> {
                    try {
                        matched.set(conditionOnFx.getAsBoolean());
                    } catch (Throwable t) {
                        matched.set(false);
                    }
                });
            } catch (Throwable ignored) {
            }
            if (matched.get()) {
                return;
            }
            Thread.sleep(30L);
        }
        fail(failureMessage);
    }

    private static void closeUi(AtomicReference<Stage> stageRef, SyncShareServices services) throws Throwable {
        JavaFxToolkitExtension.runOnFxThread(() -> {
            services.shutdown();
            Stage stage = stageRef.get();
            if (stage != null) {
                stage.close();
            }
        });
    }

    private static void configureStage(Stage stage, SyncShareApplication.LoadedView loaded) {
        Platform.setImplicitExit(false);
        stage.setTitle(SyncShareApplication.APP_TITLE);
        stage.setMinWidth(SyncShareApplication.MIN_WIDTH);
        stage.setMinHeight(SyncShareApplication.MIN_HEIGHT);
        stage.setScene(loaded.scene());
        stage.show();
    }

    private static void assertNoInlineStyles(Node node) {
        String inlineStyle = node.getStyle();
        assertTrue(
            inlineStyle == null || inlineStyle.isBlank(),
            "Found unexpected inline style on " + node.getClass().getSimpleName() + ": " + inlineStyle
        );
        if (node instanceof Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) {
                assertNoInlineStyles(child);
            }
        }
    }

    private static int findFreePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static void runKeytool(String... args) throws Exception {
        boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
        Path keytool = Path.of(System.getProperty("java.home"), "bin", windows ? "keytool.exe" : "keytool");
        List<String> command = new ArrayList<>();
        command.add(keytool.toString());
        Collections.addAll(command, args);
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(process.waitFor(20, TimeUnit.SECONDS), "keytool timed out");
        assertEquals(0, process.exitValue(), "keytool failed: " + output);
    }
}
