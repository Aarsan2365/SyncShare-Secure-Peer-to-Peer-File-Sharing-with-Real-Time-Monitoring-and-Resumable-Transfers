package com.syncshare.service;

import com.syncshare.events.ConsoleTransferListener;
import com.syncshare.model.NavItemId;
import com.syncshare.model.TransferDirection;
import com.syncshare.model.TransferItem;
import com.syncshare.model.TransferStatus;
import com.syncshare.monitoring.MonitoredTransfer;
import com.syncshare.monitoring.TransferMetricsCalculator;
import com.syncshare.monitoring.TransferMonitorState;
import com.syncshare.transfer.FileReceiver;
import com.syncshare.transfer.FileSender;
import com.syncshare.ui.JavaFxToolkitExtension;
import com.syncshare.ui.MainController;
import com.syncshare.ui.SyncShareApplication;
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
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(JavaFxToolkitExtension.class)
class TransferLifecycleIntegrationTest {

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
    void stallTickerZeroesSpeedAndClearsEtaUntilNewBytesArrive() throws Throwable {
        AtomicLong clock = new AtomicLong(1_000_000_000L);
        TransferMonitoringService monitoring = new TransferMonitoringService(clock::get, 0L, false);

        try {
            monitoring.registerQueued("tx-stall", TransferDirection.OUTGOING, "backup.tar", 10_000L, "10.0.0.10:5050", "Queued");
            clock.addAndGet(100_000_000L);
            monitoring.onStarted("tx-stall", TransferDirection.OUTGOING, "backup.tar", 10_000L, 0L, "10.0.0.10:5050");

            // 5 KB over 0.5 s => 10 KB/s, remaining 5 KB => ETA 1s
            clock.addAndGet(500_000_000L);
            monitoring.onProgress("tx-stall", TransferDirection.OUTGOING, "backup.tar", 5_000L, 10_000L);

            MonitoredTransfer active = monitoring.getTransfer("tx-stall").orElseThrow();
            assertEquals(TransferMonitorState.RUNNING, active.state());
            assertTrue(active.speedBytesPerSecond() > 0.0);
            assertTrue(active.estimatedRemainingSeconds() > 0L);

            // Advance 3 seconds with zero progress (> 2.5s stall timeout) and tick
            clock.addAndGet(3_000_000_000L);
            monitoring.tickActiveTransfers();

            MonitoredTransfer stalled = monitoring.getTransfer("tx-stall").orElseThrow();
            assertEquals(0.0, stalled.speedBytesPerSecond(), 1e-9);
            assertEquals("0 B/s", stalled.formattedSpeed());
            assertEquals(TransferMetricsCalculator.UNAVAILABLE_ETA, stalled.estimatedRemainingSeconds());
            assertEquals("--", stalled.formattedEta());

            // Flow resumes: next valid progress window restores positive speed and finite ETA
            clock.addAndGet(250_000_000L);
            monitoring.onProgress("tx-stall", TransferDirection.OUTGOING, "backup.tar", 7_500L, 10_000L);

            MonitoredTransfer recovered = monitoring.getTransfer("tx-stall").orElseThrow();
            assertTrue(recovered.speedBytesPerSecond() > 0.0);
            assertTrue(recovered.estimatedRemainingSeconds() > 0L);
        } finally {
            monitoring.shutdown();
        }
    }

    @Test
    void midTransferCancellationOverRealMtlsPreservesBytesAndClosesSocket() throws Exception {
        Path receiveDir = tempDir.resolve("rx-cancel");
        Files.createDirectories(receiveDir);

        byte[] payload = new byte[512 * 1024]; // 8 x 64 KB chunks
        new Random(99).nextBytes(payload);
        Path source = tempDir.resolve("large-cancel.bin");
        Files.write(source, payload);

        TransferMonitoringService monitoring = new TransferMonitoringService(System::nanoTime, 0L, false);
        AtomicReference<SSLSocket> activeClientSocket = new AtomicReference<>();
        CountDownLatch firstChunkSent = new CountDownLatch(1);
        CountDownLatch allowCancel = new CountDownLatch(1);

        TransferService transferService = new TransferService((host, port, file, listener) -> {
            try (SSLSocket socket = (SSLSocket) sharedSslContext.getSocketFactory().createSocket(host, port)) {
                socket.setEnabledProtocols(new String[] {"TLSv1.3"});
                socket.setSoTimeout(10_000);
                activeClientSocket.set(socket);
                FileSender.send(socket, file, "tx-unused", new com.syncshare.events.TransferSessionListener() {
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
                        if (transferred >= 65_536L) {
                            firstChunkSent.countDown();
                            try {
                                allowCancel.await(5, TimeUnit.SECONDS);
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                            }
                        }
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

        withReceiveDir(receiveDir, () -> {
            try (SSLServerSocket server = (SSLServerSocket) sharedSslContext.getServerSocketFactory().createServerSocket(0);
                 ExecutorService rxPool = Executors.newSingleThreadExecutor()) {
                server.setEnabledProtocols(new String[] {"TLSv1.3"});
                server.setNeedClientAuth(true);
                server.setSoTimeout(10_000);

                Future<?> rxFuture = rxPool.submit(() -> {
                    try (SSLSocket peer = (SSLSocket) server.accept()) {
                        peer.setSoTimeout(10_000);
                        FileReceiver.receive(peer, new ConsoleTransferListener(), (n, s, a) -> true, "rx-cancel", monitoring);
                    }
                    return null;
                });

                CompletableFuture<TransferItem> future = transferService.sendFile("127.0.0.1", server.getLocalPort(), source);
                assertTrue(firstChunkSent.await(10, TimeUnit.SECONDS));

                MonitoredTransfer running = monitoring.getActiveSnapshot().stream()
                    .filter(t -> t.direction() == TransferDirection.OUTGOING)
                    .findFirst()
                    .orElseThrow();

                // Close live socket and trigger service cancellation while sender waits after chunk 1
                SSLSocket openSocket = activeClientSocket.get();
                if (openSocket != null) {
                    openSocket.close();
                }
                assertTrue(transferService.cancelActiveTransfer(running.transferId()));
                allowCancel.countDown();

                TransferItem outcome = future.get(10, TimeUnit.SECONDS);
                assertEquals(TransferStatus.CANCELLED, outcome.status());

                MonitoredTransfer terminal = monitoring.getTransfer(running.transferId()).orElseThrow();
                assertEquals(TransferMonitorState.CANCELLED, terminal.state());
                assertTrue(terminal.transferredBytes() >= 65_536L && terminal.transferredBytes() < payload.length);
                assertNotNull(terminal.completedAt());
                assertEquals(TransferMetricsCalculator.UNAVAILABLE_SPEED, terminal.speedBytesPerSecond(), 1e-9);

                // Receiver future finishes with FAILED when remote peer drops socket mid-stream
                try {
                    rxFuture.get(10, TimeUnit.SECONDS);
                } catch (Exception ignored) {
                }

                MonitoredTransfer rxTerminal = monitoring.getTransfer("rx-cancel").orElseThrow();
                assertEquals(TransferMonitorState.FAILED, rxTerminal.state());
                assertNotNull(rxTerminal.completedAt());
            } finally {
                transferService.shutdown();
            }
        });
    }

    @Test
    void interruptedConnectionAndZeroByteTransferRecordAccurateTerminalMetrics() throws Exception {
        Path receiveDir = tempDir.resolve("rx-int");
        Files.createDirectories(receiveDir);

        byte[] largePayload = new byte[256 * 1024];
        new Random(41).nextBytes(largePayload);
        Path largeSource = tempDir.resolve("interrupted.bin");
        Files.write(largeSource, largePayload);

        Path zeroSource = tempDir.resolve("zero.bin");
        Files.write(zeroSource, new byte[0]);

        TransferMonitoringService monitoring = new TransferMonitoringService(System::nanoTime, 0L, false);
        try {
            withReceiveDir(receiveDir, () -> {
                try (SSLServerSocket server = (SSLServerSocket) sharedSslContext.getServerSocketFactory().createServerSocket(0);
                     ExecutorService pool = Executors.newFixedThreadPool(2)) {
                    server.setEnabledProtocols(new String[] {"TLSv1.3"});
                    server.setNeedClientAuth(true);
                    server.setSoTimeout(10_000);

                    // 1. Interrupted connection test
                    Future<?> dropServer = pool.submit(() -> {
                        try (SSLSocket peer = (SSLSocket) server.accept()) {
                            peer.setSoTimeout(10_000);
                            FileReceiver.receive(peer, new ConsoleTransferListener(), (n, s, a) -> true, "rx-drop", monitoring);
                        }
                        return null;
                    });

                    try (SSLSocket senderSock = (SSLSocket) sharedSslContext.getSocketFactory().createSocket("127.0.0.1", server.getLocalPort())) {
                        senderSock.setEnabledProtocols(new String[] {"TLSv1.3"});
                        senderSock.setSoTimeout(10_000);
                        com.syncshare.events.TransferSessionListener dropTrigger = new com.syncshare.events.TransferSessionListener() {
                            @Override
                            public void onConnecting(String id, TransferDirection dir, String name, long total, String peer, String note) {
                                monitoring.onConnecting(id, dir, name, total, peer, note);
                            }

                            @Override
                            public void onStarted(String id, TransferDirection dir, String name, long total, long initial, String peer) {
                                monitoring.onStarted(id, dir, name, total, initial, peer);
                            }

                            @Override
                            public void onProgress(String id, TransferDirection dir, String name, long transferred, long total) {
                                monitoring.onProgress(id, dir, name, transferred, total);
                                if (transferred >= 65_536L) {
                                    try {
                                        senderSock.close();
                                    } catch (IOException ignored) {
                                    }
                                }
                            }

                            @Override
                            public void onCompleted(String id, TransferDirection dir, String name) {
                                monitoring.onCompleted(id, dir, name);
                            }

                            @Override
                            public void onFailed(String id, TransferDirection dir, String name, String reason, Exception error) {
                                monitoring.onFailed(id, dir, name, reason, error);
                            }

                            @Override
                            public void onCancelled(String id, TransferDirection dir, String name, String reason) {
                                monitoring.onCancelled(id, dir, name, reason);
                            }
                        };
                        try {
                            FileSender.send(senderSock, largeSource, "tx-drop", dropTrigger);
                        } catch (IOException ignored) {
                        }
                    }

                    try {
                        dropServer.get(10, TimeUnit.SECONDS);
                    } catch (Exception ignored) {
                    }

                    MonitoredTransfer txDrop = monitoring.getTransfer("tx-drop").orElseThrow();
                    assertEquals(TransferMonitorState.FAILED, txDrop.state());
                    assertTrue(txDrop.transferredBytes() >= 65_536L && txDrop.transferredBytes() < largePayload.length);
                    assertTrue(!txDrop.errorMessage().isBlank());
                    assertNotNull(txDrop.completedAt());

                    // 2. Zero-byte verified completion test
                    Future<?> zeroServer = pool.submit(() -> {
                        try (SSLSocket peer = (SSLSocket) server.accept()) {
                            peer.setSoTimeout(10_000);
                            FileReceiver.receive(peer, new ConsoleTransferListener(), (n, s, a) -> true, "rx-zero", monitoring);
                        }
                        return null;
                    });

                    try (SSLSocket zeroClient = (SSLSocket) sharedSslContext.getSocketFactory().createSocket("127.0.0.1", server.getLocalPort())) {
                        zeroClient.setEnabledProtocols(new String[] {"TLSv1.3"});
                        zeroClient.setSoTimeout(10_000);
                        FileSender.send(zeroClient, zeroSource, "tx-zero", monitoring);
                    }
                    zeroServer.get(10, TimeUnit.SECONDS);

                    MonitoredTransfer txZero = monitoring.getTransfer("tx-zero").orElseThrow();
                    assertEquals(TransferMonitorState.COMPLETED, txZero.state());
                    assertEquals(0L, txZero.totalBytes());
                    assertEquals(1.0, txZero.progress(), 1e-9);
                    assertNotNull(txZero.completedAt());

                    MonitoredTransfer rxZero = monitoring.getTransfer("rx-zero").orElseThrow();
                    assertEquals(TransferMonitorState.COMPLETED, rxZero.state());
                    assertEquals(1.0, rxZero.progress(), 1e-9);
                }
            });
        } finally {
            monitoring.shutdown();
        }
    }

    @Test
    void applicationShutdownDisposesCachedScreenListenersCleanly() throws Throwable {
        JavaFxToolkitExtension.runOnFxThread(() -> {
            TransferMonitoringService monitoring = new TransferMonitoringService(System::nanoTime, 0L, false);
            SettingsService settings = new SettingsService();
            DeviceInfoService deviceInfo = new DeviceInfoService(settings);
            ConnectionService connection = new ConnectionService(settings, deviceInfo, (p, h) -> () -> {});
            TransferService transfers = new TransferService(monitoring);
            SyncShareServices services = new SyncShareServices(settings, deviceInfo, connection, transfers, monitoring);

            Stage stage = new Stage();
            SyncShareApplication.LoadedView loaded = SyncShareApplication.loadMainView(services);
            try {
                stage.setScene(loaded.scene());
                MainController mainController = loaded.controller();
                assertTrue(mainController.navigateTo(NavItemId.ACTIVE_TRANSFERS).success());
                assertEquals(1, monitoring.listenerCount());

                mainController.shutdown();
                assertEquals(0, monitoring.listenerCount());
            } finally {
                stage.close();
            }
        });
    }

    private static void withReceiveDir(Path receiveDir, ThrowingRunnable action) throws Exception {
        String originalDir = System.getProperty("user.dir");
        synchronized (TransferLifecycleIntegrationTest.class) {
            System.setProperty("user.dir", receiveDir.getParent().toString());
            Path expectedDir = receiveDir.getParent().resolve("received_files");
            Files.createDirectories(expectedDir);
            try {
                action.run();
            } finally {
                System.setProperty("user.dir", originalDir);
            }
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

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}
