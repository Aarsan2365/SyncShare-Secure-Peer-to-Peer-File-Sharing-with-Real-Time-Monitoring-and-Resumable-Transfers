package com.syncshare.ui;

import com.syncshare.events.ConsoleTransferListener;
import com.syncshare.events.TransferSessionListener;
import com.syncshare.model.NavItemId;
import com.syncshare.model.TransferDirection;
import com.syncshare.model.TransferItem;
import com.syncshare.model.TransferStatus;
import com.syncshare.monitoring.MonitoredTransfer;
import com.syncshare.monitoring.TransferMonitorState;
import com.syncshare.service.ConnectionService;
import com.syncshare.service.DeviceInfoService;
import com.syncshare.service.SettingsService;
import com.syncshare.service.SyncShareServices;
import com.syncshare.service.TransferMonitoringService;
import com.syncshare.service.TransferService;
import com.syncshare.transfer.FileReceiver;
import com.syncshare.transfer.FileSender;
import com.syncshare.ui.controller.ActiveTransfersController;
import javafx.scene.Parent;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManagerFactory;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(JavaFxToolkitExtension.class)
class RealTimeTransferMonitoringIntegrationTest {

    @TempDir
    static Path sharedCertDir;

    private static SSLContext sharedSslContext;

    @TempDir
    Path tempDir;

    @BeforeAll
    static void initTlsContext() throws Exception {
        sharedSslContext = createLoopbackTlsContext(sharedCertDir.resolve("certs"));
    }

    @Test
    void realMtlsLoopbackTransfersUpdateActiveTransfersDashboardAndSidebarLive() throws Throwable {
        byte[] payloadA = new byte[256 * 1024];
        byte[] payloadB = new byte[192 * 1024];
        new Random(101).nextBytes(payloadA);
        new Random(202).nextBytes(payloadB);
        Path fileA = tempDir.resolve("bundle-alpha.bin");
        Path fileB = tempDir.resolve("bundle-beta.bin");
        Files.write(fileA, payloadA);
        Files.write(fileB, payloadB);

        TransferMonitoringService monitoring = new TransferMonitoringService(System::nanoTime, 25_000_000L, true);
        SettingsService settings = new SettingsService();
        DeviceInfoService deviceInfo = new DeviceInfoService(settings);
        ConnectionService connection = new ConnectionService(settings, deviceInfo, monitoring);
        TransferService transferService = new TransferService((host, port, file, listener) -> {
            try (SSLSocket socket = (SSLSocket) sharedSslContext.getSocketFactory().createSocket(host, port)) {
                socket.setEnabledProtocols(new String[] {"TLSv1.3"});
                socket.setSoTimeout(10_000);
                FileSender.send(socket, file, "internal-" + file.getFileName(), new TransferSessionListener() {
                    @Override
                    public void onConnecting(String id, TransferDirection dir, String name, long total, String peer, String note) {
                    }

                    @Override
                    public void onStarted(String id, TransferDirection dir, String name, long total, long initial, String peer) {
                        listener.onStarted(name, total);
                    }

                    @Override
                    public void onProgress(String id, TransferDirection dir, String name, long transferred, long total) {
                        listener.onProgress(name, transferred, total);
                    }

                    @Override
                    public void onCompleted(String id, TransferDirection dir, String name) {
                        listener.onCompleted(name);
                    }

                    @Override
                    public void onFailed(String id, TransferDirection dir, String name, String reason, Exception error) {
                        listener.onError(name, error != null ? error : new IOException(reason));
                    }

                    @Override
                    public void onCancelled(String id, TransferDirection dir, String name, String reason) {
                        listener.onError(name, new InterruptedException(reason));
                    }
                });
            }
        }, monitoring);

        SyncShareServices services = new SyncShareServices(settings, deviceInfo, connection, transferService, monitoring);
        MainController[] mainHolder = new MainController[1];
        ActiveTransfersController[] activeHolder = new ActiveTransfersController[1];
        Stage[] stageHolder = new Stage[1];

        JavaFxToolkitExtension.runOnFxThread(() -> {
            Stage stage = new Stage();
            SyncShareApplication.LoadedView loaded = SyncShareApplication.loadMainView(services);
            stage.setScene(loaded.scene());
            stage.show();

            MainController main = loaded.controller();
            assertTrue(main.navigateTo(NavItemId.ACTIVE_TRANSFERS).success());
            ActiveTransfersController active = (ActiveTransfersController)
                main.getViewNavigator().getCachedScreen(NavItemId.ACTIVE_TRANSFERS).controller();

            mainHolder[0] = main;
            activeHolder[0] = active;
            stageHolder[0] = stage;
        });

        try (SSLServerSocket server = (SSLServerSocket) sharedSslContext.getServerSocketFactory().createServerSocket(0);
             ExecutorService rxPool = Executors.newFixedThreadPool(2)) {
            server.setEnabledProtocols(new String[] {"TLSv1.3"});
            server.setNeedClientAuth(true);
            server.setSoTimeout(10_000);

            AtomicInteger rxCounter = new AtomicInteger(1);
            CountDownLatch acceptedBoth = new CountDownLatch(2);
            Future<?> serverTask = rxPool.submit(() -> {
                for (int i = 0; i < 2; i++) {
                    SSLSocket peer = (SSLSocket) server.accept();
                    peer.setSoTimeout(10_000);
                    acceptedBoth.countDown();
                    int idNum = rxCounter.getAndIncrement();
                    rxPool.submit(() -> {
                        try (SSLSocket s = peer) {
                            FileReceiver.receive(s, new ConsoleTransferListener(), (n, sz, addr) -> true, "rx-live-" + idNum, monitoring);
                        } catch (Exception ignored) {
                        }
                    });
                }
                return null;
            });

            CompletableFuture<TransferItem> txA = transferService.sendFile("127.0.0.1", server.getLocalPort(), fileA);
            CompletableFuture<TransferItem> txB = transferService.sendFile("127.0.0.1", server.getLocalPort(), fileB);

            assertTrue(acceptedBoth.await(10, TimeUnit.SECONDS));
            serverTask.get(10, TimeUnit.SECONDS);

            TransferItem doneA = txA.get(15, TimeUnit.SECONDS);
            TransferItem doneB = txB.get(15, TimeUnit.SECONDS);
            assertEquals(TransferStatus.COMPLETED, doneA.status());
            assertEquals(TransferStatus.COMPLETED, doneB.status());

            // Give FX thread time to settle both outgoing and incoming completion snapshots
            long deadline = System.currentTimeMillis() + 5_000L;
            while (monitoring.getRecentSnapshot().size() < 4 && System.currentTimeMillis() < deadline) {
                Thread.sleep(25);
            }

            JavaFxToolkitExtension.runOnFxThread(() -> {
                ActiveTransfersController active = activeHolder[0];
                MainController main = mainHolder[0];

                assertEquals(0, active.getActiveTable().getItems().size());
                assertEquals("0 Active", active.getCountBadge().getText());
                assertEquals(4, active.getRecentTable().getItems().size(), "Expect 2 outgoing + 2 incoming monitored records");
                for (MonitoredTransfer item : active.getRecentTable().getItems()) {
                    assertEquals(TransferMonitorState.COMPLETED, item.state());
                    assertEquals("100%", item.formattedPercentage());
                    assertNotNull(item.startedAt());
                    assertNotNull(item.completedAt());
                }
                assertFalse(active.getErrorBanner().isVisible());

                // Navigate to Dashboard and inspect synchronized summaries
                assertTrue(main.navigateTo(NavItemId.DASHBOARD).success());
                Parent root = stageHolder[0].getScene().getRoot();
                root.applyCss();
                root.layout();

                VBox recentSummary = (VBox) root.lookup("#recentSummaryContent");
                assertNotNull(recentSummary);
                assertFalse(recentSummary.getChildren().isEmpty());
                assertTrue(recentSummary.getChildren().get(0).getStyleClass().contains("transfer-summary-row"));
                Label firstMeta = (Label) recentSummary.getChildren().get(0).lookup(".transfer-summary-meta");
                assertNotNull(firstMeta);
                assertFalse(firstMeta.getText().isBlank());

                // Shutdown cleanly removes Dashboard + ActiveTransfers monitoring listeners
                main.shutdown();
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

    private static SSLContext createLoopbackTlsContext(Path certDir) throws Exception {
        Files.createDirectories(certDir);
        Path keyStorePath = certDir.resolve("loopback.p12");
        String password = "testpassword123";

        String keytoolExe = Path.of(System.getProperty("java.home"), "bin", "keytool").toString();
        Process process = new ProcessBuilder(
            keytoolExe,
            "-genkeypair",
            "-alias", "syncshare-test",
            "-keyalg", "RSA",
            "-keysize", "2048",
            "-dname", "CN=localhost,O=SyncShare,C=US",
            "-validity", "7",
            "-storetype", "PKCS12",
            "-keystore", keyStorePath.toString(),
            "-storepass", password,
            "-keypass", password
        ).redirectErrorStream(true).start();

        String output = new String(process.getInputStream().readAllBytes());
        if (!process.waitFor(20, TimeUnit.SECONDS) || process.exitValue() != 0) {
            throw new IllegalStateException("keytool failed: " + output);
        }

        KeyStore ks = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(keyStorePath)) {
            ks.load(in, password.toCharArray());
        }

        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(ks, password.toCharArray());

        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(ks);

        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(kmf.getKeyManagers(), tmf.getTrustManagers(), null);
        return ctx;
    }
}
