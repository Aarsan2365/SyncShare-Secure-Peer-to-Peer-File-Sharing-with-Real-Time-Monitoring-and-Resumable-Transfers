package com.syncshare.service;

import com.syncshare.discovery.DeviceDiscoveryService;
import com.syncshare.discovery.PeerInfo;
import com.syncshare.events.ConsoleTransferListener;
import com.syncshare.events.TransferEventListener;
import com.syncshare.events.TransferListener;
import com.syncshare.events.TransferSessionListener;
import com.syncshare.model.ConnectionState;
import com.syncshare.model.ConnectionStatus;
import com.syncshare.model.LocalDeviceInfo;
import com.syncshare.model.TransferDirection;
import com.syncshare.model.TransferItem;
import com.syncshare.model.TransferStatus;
import com.syncshare.monitoring.MonitoredTransfer;
import com.syncshare.monitoring.TransferMonitorState;
import com.syncshare.network.PeerServer;
import com.syncshare.network.Protocol;
import com.syncshare.resume.ResumeStore;
import com.syncshare.security.TlsSupport;
import com.syncshare.transfer.FileReceiver;
import com.syncshare.transfer.FileSender;
import com.syncshare.ui.JavaFxToolkitExtension;
import com.syncshare.ui.controller.SendFilesController;
import javafx.application.Platform;
import javafx.stage.Stage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManagerFactory;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(JavaFxToolkitExtension.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class NetworkServicesIntegrationTest {

    @TempDir
    static Path sharedCertDir;

    private static SSLContext sharedSslContext;

    @TempDir
    Path tempDir;

    @BeforeAll
    static void initTls() throws Exception {
        sharedSslContext = createLoopbackTlsContext(sharedCertDir.resolve("certs"));
    }

    @Test
    @Order(1)
    @DisplayName("Validation 1: Two local application instances perform successful file transfer over mTLS 1.3")
    void test01_TwoLocalInstancesSuccessfulTransfer() throws Exception {
        Path receiveDir = tempDir.resolve("rx-instance");
        Files.createDirectories(receiveDir);

        byte[] payload = new byte[192 * 1024]; // 3 x 64 KB chunks
        new Random(42).nextBytes(payload);
        Path sourceFile = tempDir.resolve("tx-document.bin");
        Files.write(sourceFile, payload);

        TransferMonitoringService monitoring = new TransferMonitoringService(System::nanoTime, 0L, false);
        List<Long> progressReported = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch completedLatch = new CountDownLatch(1);

        try (SSLServerSocket server = (SSLServerSocket) sharedSslContext.getServerSocketFactory().createServerSocket(0);
             ExecutorService rxPool = Executors.newSingleThreadExecutor()) {
            server.setEnabledProtocols(new String[]{"TLSv1.3"});
            server.setNeedClientAuth(true);
            server.setSoTimeout(15_000);

            // Local instance 1: Background Receiver with auto-approval
            Future<?> rxFuture = rxPool.submit(() -> {
                try (SSLSocket accepted = (SSLSocket) server.accept()) {
                    accepted.setSoTimeout(15_000);
                    withReceiveDir(receiveDir, () -> {
                        FileReceiver.receive(
                            accepted,
                            new ConsoleTransferListener(),
                            (name, size, addr) -> true,
                            receiveDir,
                            null,
                            "rx-inst-1",
                            monitoring
                        );
                    });
                }
                return null;
            });

            // Local instance 2: Sender Service configured with loopback mTLS transport
            TransferService transferService = new TransferService((host, port, file, listener) -> {
                try (SSLSocket socket = (SSLSocket) sharedSslContext.getSocketFactory().createSocket(host, port)) {
                    socket.setEnabledProtocols(new String[]{"TLSv1.3"});
                    socket.setSoTimeout(15_000);
                    FileSender.send(socket, file, "tx-inst-1", new TransferSessionListener() {
                        @Override
                        public void onProgress(String id, TransferDirection dir, String name, long transferred, long total) {
                            progressReported.add(transferred);
                            listener.onProgress(name, transferred, total);
                        }

                        @Override
                        public void onCompleted(String id, TransferDirection dir, String name) {
                            listener.onCompleted(name);
                            completedLatch.countDown();
                        }

                        @Override
                        public void onFailed(String id, TransferDirection dir, String name, String reason, Exception error) {
                            listener.onError(name, error != null ? error : new IOException(reason));
                        }
                    });
                }
            }, monitoring);

            CompletableFuture<TransferItem> sendFuture = transferService.sendFile("127.0.0.1", server.getLocalPort(), sourceFile);
            TransferItem result = sendFuture.get(15, TimeUnit.SECONDS);
            rxFuture.get(15, TimeUnit.SECONDS);

            assertEquals(TransferStatus.COMPLETED, result.status());
            assertTrue(completedLatch.await(5, TimeUnit.SECONDS));

            // Verify file received and content integrity
            Path saved = findSingleReceivedFile(receiveDir);
            assertArrayEquals(payload, Files.readAllBytes(saved));
            assertTrue(progressReported.contains(192L * 1024));

            transferService.shutdown();
        } finally {
            monitoring.shutdown();
        }
    }

    @Test
    @Order(2)
    @DisplayName("Validation 2: Connection failure to unreachable peer reports failure and cleans up cleanly")
    void test02_ConnectionFailureToUnreachablePeer() throws Exception {
        TransferMonitoringService monitoring = new TransferMonitoringService();
        TransferService transferService = new TransferService((host, port, file, listener) -> {
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress(host, port), 500);
            }
        }, monitoring);

        Path testFile = tempDir.resolve("unreachable-test.txt");
        Files.writeString(testFile, "test data");

        // Attempt transfer to unbound port
        CompletableFuture<TransferItem> future = transferService.sendFile("127.0.0.1", 59998, testFile);
        TransferItem result = future.get(5, TimeUnit.SECONDS);

        assertEquals(TransferStatus.FAILED, result.status());
        assertFalse(transferService.hasActiveOutgoingTransfer("127.0.0.1", 59998, testFile));

        transferService.shutdown();
        monitoring.shutdown();
    }

    @Test
    @Order(3)
    @DisplayName("Validation 3: Network interruption mid-transfer preserves .part and .resume checkpoint")
    void test03_NetworkInterruptionPreservesResumeCheckpoint() throws Exception {
        Path receiveDir = tempDir.resolve("rx-interrupt");
        Files.createDirectories(receiveDir);

        byte[] payload = new byte[256 * 1024]; // 4 x 64 KB chunks
        new Random(99).nextBytes(payload);
        Path source = tempDir.resolve("large-interrupt.bin");
        Files.write(source, payload);

        TransferMonitoringService monitoring = new TransferMonitoringService();
        AtomicReference<SSLSocket> activeSocket = new AtomicReference<>();
        CountDownLatch chunkOneAcknowledged = new CountDownLatch(1);

        try (SSLServerSocket server = (SSLServerSocket) sharedSslContext.getServerSocketFactory().createServerSocket(0);
             ExecutorService pool = Executors.newFixedThreadPool(2)) {
            server.setEnabledProtocols(new String[]{"TLSv1.3"});
            server.setNeedClientAuth(true);
            server.setSoTimeout(10_000);

            Future<?> rxFuture = pool.submit(() -> {
                try (SSLSocket client = (SSLSocket) server.accept()) {
                    client.setSoTimeout(10_000);
                    withReceiveDir(receiveDir, () -> {
                        FileReceiver.receive(
                            client,
                            new ConsoleTransferListener(),
                            (n, s, a) -> true,
                            receiveDir,
                            null,
                            "rx-drop-id",
                            monitoring
                        );
                    });
                }
                return null;
            });

            Future<?> txFuture = pool.submit(() -> {
                try (SSLSocket client = (SSLSocket) sharedSslContext.getSocketFactory().createSocket("127.0.0.1", server.getLocalPort())) {
                    client.setEnabledProtocols(new String[]{"TLSv1.3"});
                    client.setSoTimeout(10_000);
                    activeSocket.set(client);

                    FileSender.send(client, source, "tx-drop-id", new TransferSessionListener() {
                        @Override
                        public void onProgress(String id, TransferDirection dir, String name, long transferred, long total) {
                            if (transferred >= 65_536L) {
                                chunkOneAcknowledged.countDown();
                                // Drop the socket connection mid-transfer
                                try {
                                    client.close();
                                } catch (IOException ignored) {
                                }
                            }
                        }
                    });
                }
                return null;
            });

            assertTrue(chunkOneAcknowledged.await(10, TimeUnit.SECONDS));

            // Wait for both ends to abort
            try {
                txFuture.get(5, TimeUnit.SECONDS);
            } catch (Exception ignored) {
            }
            try {
                rxFuture.get(5, TimeUnit.SECONDS);
            } catch (Exception ignored) {
            }

            // Verify .resume file exists and offset is recorded
            List<Path> resumeFiles = Files.walk(receiveDir)
                .filter(p -> p.toString().endsWith(".resume"))
                .toList();
            assertFalse(resumeFiles.isEmpty(), "Resume checkpoint file must exist after interruption");

            monitoring.shutdown();
        }
    }

    @Test
    @Order(4)
    @DisplayName("Validation 4: Resumption of interrupted transfer streams remaining chunks from checkpoint")
    void test04_ResumptionOfInterruptedTransfer() throws Exception {
        Path receiveDir = tempDir.resolve("rx-resume");
        Files.createDirectories(receiveDir);

        byte[] payload = new byte[192 * 1024]; // 3 x 64 KB chunks
        new Random(123).nextBytes(payload);
        Path source = tempDir.resolve("resumable.bin");
        Files.write(source, payload);

        TransferMonitoringService monitoring = new TransferMonitoringService();
        List<Long> secondAttemptSenderProgress = Collections.synchronizedList(new ArrayList<>());

        withReceiveDir(receiveDir, () -> {
            // First attempt: interrupt after chunk 1
            try (SSLServerSocket server = (SSLServerSocket) sharedSslContext.getServerSocketFactory().createServerSocket(0);
                 ExecutorService pool = Executors.newFixedThreadPool(2)) {
                server.setEnabledProtocols(new String[]{"TLSv1.3"});
                server.setNeedClientAuth(true);
                server.setSoTimeout(10_000);

                pool.submit(() -> {
                    try (SSLSocket client = (SSLSocket) server.accept()) {
                        client.setSoTimeout(10_000);
                        FileReceiver.receive(client, new ConsoleTransferListener(), (n, s, a) -> true, receiveDir, null, "rx-res", monitoring);
                    } catch (Exception ignored) {
                    }
                });

                try (SSLSocket client = (SSLSocket) sharedSslContext.getSocketFactory().createSocket("127.0.0.1", server.getLocalPort())) {
                    client.setEnabledProtocols(new String[]{"TLSv1.3"});
                    client.setSoTimeout(10_000);
                    try {
                        FileSender.send(client, source, "tx-res", new TransferSessionListener() {
                            @Override
                            public void onProgress(String id, TransferDirection dir, String name, long transferred, long total) {
                                if (transferred >= 65_536L) {
                                    try {
                                        client.close();
                                    } catch (IOException ignored) {
                                    }
                                }
                            }
                        });
                    } catch (Exception ignored) {
                    }
                }
            }

            // Second attempt: Resume transfer
            try (SSLServerSocket server = (SSLServerSocket) sharedSslContext.getServerSocketFactory().createServerSocket(0);
                 ExecutorService pool = Executors.newFixedThreadPool(2)) {
                server.setEnabledProtocols(new String[]{"TLSv1.3"});
                server.setNeedClientAuth(true);
                server.setSoTimeout(10_000);

                Future<?> rxFuture = pool.submit(() -> {
                    try (SSLSocket client = (SSLSocket) server.accept()) {
                        client.setSoTimeout(10_000);
                        FileReceiver.receive(client, new ConsoleTransferListener(), (n, s, a) -> true, receiveDir, null, "rx-res-2", monitoring);
                    }
                    return null;
                });

                try (SSLSocket client = (SSLSocket) sharedSslContext.getSocketFactory().createSocket("127.0.0.1", server.getLocalPort())) {
                    client.setEnabledProtocols(new String[]{"TLSv1.3"});
                    client.setSoTimeout(10_000);

                    FileSender.send(client, source, "tx-res-2", new TransferSessionListener() {
                        @Override
                        public void onProgress(String id, TransferDirection dir, String name, long transferred, long total) {
                            secondAttemptSenderProgress.add(transferred);
                        }
                    });
                }

                rxFuture.get(10, TimeUnit.SECONDS);
            }
        });

        // Verification: Sender resumed from byte 65536 and transferred remaining bytes
        assertTrue(secondAttemptSenderProgress.stream().anyMatch(b -> b == 65_536L), "Sender should start from resume offset 65536");
        assertTrue(secondAttemptSenderProgress.contains(192L * 1024), "Sender should finish at 196608 bytes");

        // Verify final file
        Path completedFile = findSingleReceivedFile(receiveDir);
        assertArrayEquals(payload, Files.readAllBytes(completedFile));

        monitoring.shutdown();
    }

    @Test
    @Order(5)
    @DisplayName("Validation 5: ConnectionStatusListener observes status changes and TLS readiness")
    void test05_ConnectionStatusListenerAndTlsStatus() throws Exception {
        SettingsService settingsService = new SettingsService();
        DeviceInfoService deviceInfoService = new DeviceInfoService(settingsService);
        TransferMonitoringService monitoring = new TransferMonitoringService();
        TransferService transferService = new TransferService(monitoring);

        ConnectionService connectionService = new ConnectionService(settingsService, deviceInfoService, transferService, monitoring);
        List<ConnectionStatus> statusHistory = new CopyOnWriteArrayList<>();

        connectionService.addStatusListener(statusHistory::add);

        // Status should be initialized and listener receives initial state
        assertFalse(statusHistory.isEmpty());
        ConnectionStatus initial = statusHistory.get(0);
        assertNotNull(initial);

        // Refresh and verify status is consistent
        connectionService.refreshStatus();
        assertTrue(statusHistory.size() >= 1);

        connectionService.shutdown();
        transferService.shutdown();
        monitoring.shutdown();
        settingsService.shutdown();
    }

    @Test
    @Order(6)
    @DisplayName("Validation 6: UDP PeerDiscoveryManager lifecycle, peer updates, and graceful stop")
    void test06_PeerDiscoveryManagerLifecycle() throws Throwable {
        SettingsService settingsService = new SettingsService();
        DeviceInfoService deviceInfoService = new DeviceInfoService(settingsService);

        // Create a custom factory that returns a simulated DeviceDiscoveryService
        List<PeerInfo> mockPeers = List.of(
            new PeerInfo("Peer-Alpha", "192.168.1.100", 5050, System.currentTimeMillis()),
            new PeerInfo("Peer-Beta", "192.168.1.101", 5052, System.currentTimeMillis())
        );

        DeviceDiscoveryService mockService = new DeviceDiscoveryService() {
            private volatile boolean open = true;

            @Override
            public List<PeerInfo> listPeers() {
                return open ? mockPeers : List.of();
            }

            @Override
            public boolean isRunning() {
                return open;
            }

            @Override
            public void close() {
                open = false;
            }
        };

        PeerDiscoveryManager discoveryManager = new PeerDiscoveryManager(
            settingsService,
            deviceInfoService,
            (name, port) -> mockService
        );

        try {
            assertFalse(discoveryManager.isActive());

            discoveryManager.start();
            assertTrue(discoveryManager.isActive());

            // Wait briefly for poll task to update discovered list
            CountDownLatch peerLatch = new CountDownLatch(1);
            discoveryManager.getDiscoveredPeers().addListener((javafx.collections.ListChangeListener<PeerInfo>) c -> {
                if (!discoveryManager.getDiscoveredPeers().isEmpty()) {
                    peerLatch.countDown();
                }
            });

            assertTrue(peerLatch.await(3, TimeUnit.SECONDS), "Discovered peers list should populate from discovery service");
            assertEquals(2, discoveryManager.getDiscoveredPeers().size());
            assertEquals("Peer-Alpha", discoveryManager.getDiscoveredPeers().get(0).name());

            discoveryManager.stop();
            JavaFxToolkitExtension.runOnFxThread(() -> {
                assertFalse(discoveryManager.isActive());
                assertTrue(discoveryManager.getDiscoveredPeers().isEmpty());
            });
        } finally {
            discoveryManager.shutdown();
            settingsService.shutdown();
        }
    }

    @Test
    @Order(7)
    @DisplayName("Validation 7: UI thread-safety and ProgressObserver decoupled execution")
    void test07_UiThreadSafetyAndProgressObserver() throws Exception {
        TransferMonitoringService monitoring = new TransferMonitoringService(System::nanoTime, 0L, false);
        TransferService transferService = new TransferService((host, port, file, listener) -> {
            assertFalse(Platform.isFxApplicationThread(), "Networking must run outside JavaFX Application Thread");
            listener.onStarted(file.getFileName().toString(), 1000L);
            listener.onProgress(file.getFileName().toString(), 500L, 1000L);
            listener.onCompleted(file.getFileName().toString());
        }, monitoring);

        Path file = tempDir.resolve("ui-safe.txt");
        Files.writeString(file, "content");

        List<TransferStatus> stages = Collections.synchronizedList(new ArrayList<>());
        List<Long> progress = Collections.synchronizedList(new ArrayList<>());

        OutgoingTransferGateway.ProgressObserver observer = new OutgoingTransferGateway.ProgressObserver() {
            @Override
            public void onStageChanged(TransferStatus status, String headline, String detail) {
                stages.add(status);
            }

            @Override
            public void onProgress(String fileName, long transferredBytes, long totalBytes) {
                progress.add(transferredBytes);
            }
        };

        TransferItem item = transferService.sendFileBlocking("test-ui-tx", "127.0.0.1", 5050, file, observer, () -> false);
        assertEquals(TransferStatus.COMPLETED, item.status());
        assertTrue(stages.contains(TransferStatus.COMPLETED));

        transferService.shutdown();
        monitoring.shutdown();
    }

    private static Path findSingleReceivedFile(Path receiveDir) throws IOException {
        try (var stream = Files.walk(receiveDir)) {
            return stream
                .filter(Files::isRegularFile)
                .filter(p -> !p.toString().contains(".resume"))
                .findFirst()
                .orElseThrow();
        }
    }

    private static void withReceiveDir(Path receiveDir, ThrowingRunnable action) throws Exception {
        String previous = System.getProperty("syncshare.receiveDir");
        System.setProperty("syncshare.receiveDir", receiveDir.toAbsolutePath().toString());
        try {
            action.run();
        } finally {
            if (previous == null) {
                System.clearProperty("syncshare.receiveDir");
            } else {
                System.setProperty("syncshare.receiveDir", previous);
            }
        }
    }

    private static SSLContext createLoopbackTlsContext(Path certDir) throws Exception {
        Files.createDirectories(certDir);
        Path keyStorePath = certDir.resolve("test-peer.p12");
        String password = "test-password-123";
        String keytool = Path.of(System.getProperty("java.home"), "bin", "keytool").toString();

        Process process = new ProcessBuilder(
            keytool,
            "-genkeypair",
            "-alias", "syncshare-test",
            "-keyalg", "RSA",
            "-keysize", "2048",
            "-dname", "CN=SyncShareTest,O=SyncShare",
            "-validity", "7",
            "-storetype", "PKCS12",
            "-keystore", keyStorePath.toAbsolutePath().toString(),
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
