package com.syncshare.service;

import com.syncshare.events.ConsoleTransferListener;
import com.syncshare.events.TransferSessionListener;
import com.syncshare.history.JsonTransferHistoryRepository;
import com.syncshare.history.TransferHistoryDirection;
import com.syncshare.history.TransferHistoryRepository;
import com.syncshare.history.TransferHistoryStatus;
import com.syncshare.history.TransferRecord;
import com.syncshare.model.AppSettings;
import com.syncshare.model.TransferDirection;
import com.syncshare.model.TransferItem;
import com.syncshare.model.TransferStatus;
import com.syncshare.transfer.FileReceiver;
import com.syncshare.transfer.FileSender;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
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
import java.util.List;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TransferHistoryIntegrationTest {

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
    void persistsRealMtlsCompletedZeroByteFailedAndCancelledTransfersAcrossServiceRestart() throws Exception {
        Path receiveDir = tempDir.resolve("syncshare-downloads");
        Files.createDirectories(receiveDir);
        Path historyFile = tempDir.resolve("appdata").resolve("transfer-history.json");

        byte[] payload = new byte[128 * 1024];
        new Random(77).nextBytes(payload);
        Path normalFile = tempDir.resolve("verified-bundle.bin");
        Files.write(normalFile, payload);

        Path zeroFile = tempDir.resolve("empty-manifest.txt");
        Files.write(zeroFile, new byte[0]);

        byte[] cancelPayload = new byte[256 * 1024];
        new Random(88).nextBytes(cancelPayload);
        Path cancelFile = tempDir.resolve("cancelled-clip.bin");
        Files.write(cancelFile, cancelPayload);

        SettingsService settings = new SettingsService();
        settings.updateSettings(new AppSettings(
            "Peer-A",
            5050,
            receiveDir.toString(),
            true,
            true,
            false
        ));

        JsonTransferHistoryRepository repo1 = new JsonTransferHistoryRepository(historyFile);
        TransferMonitoringService monitoring1 = new TransferMonitoringService(System::nanoTime, 0L, false);
        HistoryService history1 = new HistoryService(repo1, settings, monitoring1);
        AtomicReference<SSLSocket> activeSocket = new AtomicReference<>();
        CountDownLatch cancelReady = new CountDownLatch(1);
        CountDownLatch allowCancelClose = new CountDownLatch(1);

        TransferService transferService = new TransferService((host, port, file, listener) -> {
            try (SSLSocket socket = (SSLSocket) sharedSslContext.getSocketFactory().createSocket(host, port)) {
                socket.setEnabledProtocols(new String[] {"TLSv1.3"});
                socket.setSoTimeout(10_000);
                activeSocket.set(socket);
                boolean pauseFirstChunk = file.getFileName().toString().equals("cancelled-clip.bin");
                FileSender.send(socket, file, "inner-" + file.getFileName(), new TransferSessionListener() {
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
                        if (pauseFirstChunk && transferred >= 65_536L) {
                            cancelReady.countDown();
                            try {
                                allowCancelClose.await(5, TimeUnit.SECONDS);
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
        }, monitoring1);

        try (SSLServerSocket server = (SSLServerSocket) sharedSslContext.getServerSocketFactory().createServerSocket(0);
             ExecutorService serverPool = Executors.newCachedThreadPool()) {
            server.setEnabledProtocols(new String[] {"TLSv1.3"});
            server.setNeedClientAuth(true);
            server.setSoTimeout(10_000);

            int port = server.getLocalPort();

            // 1. Real completed mTLS transfer
            Future<?> rxNormal = serverPool.submit(() -> {
                try (SSLSocket peer = (SSLSocket) server.accept()) {
                    peer.setSoTimeout(10_000);
                    FileReceiver.receive(peer, new ConsoleTransferListener(), (n, s, a) -> true, "rx-normal", monitoring1);
                }
                return null;
            });
            TransferItem normalDone = transferService.sendFile("127.0.0.1", port, normalFile).get(10, TimeUnit.SECONDS);
            assertEquals(TransferStatus.COMPLETED, normalDone.status());
            rxNormal.get(10, TimeUnit.SECONDS);

            // 2. Real zero-byte mTLS transfer
            Future<?> rxZero = serverPool.submit(() -> {
                try (SSLSocket peer = (SSLSocket) server.accept()) {
                    peer.setSoTimeout(10_000);
                    FileReceiver.receive(peer, new ConsoleTransferListener(), (n, s, a) -> true, "rx-zero", monitoring1);
                }
                return null;
            });
            TransferItem zeroDone = transferService.sendFile("127.0.0.1", port, zeroFile).get(10, TimeUnit.SECONDS);
            assertEquals(TransferStatus.COMPLETED, zeroDone.status());
            rxZero.get(10, TimeUnit.SECONDS);

            // 3. Real mid-stream cancelled outgoing transfer (and remote receiver failure)
            Future<?> rxCancelledPeer = serverPool.submit(() -> {
                try (SSLSocket peer = (SSLSocket) server.accept()) {
                    peer.setSoTimeout(10_000);
                    FileReceiver.receive(peer, new ConsoleTransferListener(), (n, s, a) -> true, "rx-peer-drop", monitoring1);
                }
                return null;
            });
            CompletableFuture<TransferItem> cancelFuture = transferService.sendFile("127.0.0.1", port, cancelFile);
            assertTrue(cancelReady.await(10, TimeUnit.SECONDS));

            String outgoingCancelId = monitoring1.getActiveSnapshot().stream()
                .filter(t -> t.direction() == TransferDirection.OUTGOING && t.fileName().equals("cancelled-clip.bin"))
                .findFirst()
                .orElseThrow()
                .transferId();

            assertTrue(transferService.cancelActiveTransfer(outgoingCancelId));
            SSLSocket sock = activeSocket.get();
            if (sock != null) {
                sock.close();
            }
            allowCancelClose.countDown();
            assertEquals(TransferStatus.CANCELLED, cancelFuture.get(10, TimeUnit.SECONDS).status());

            try {
                rxCancelledPeer.get(10, TimeUnit.SECONDS);
            } catch (Exception ignored) {
            }

            assertTrue(history1.awaitPendingIo(5, TimeUnit.SECONDS));
        } finally {
            transferService.shutdown();
            monitoring1.shutdown();
            history1.shutdown();
        }

        // Restart persistence verification with brand-new service instances
        JsonTransferHistoryRepository repo2 = new JsonTransferHistoryRepository(historyFile);
        TransferMonitoringService monitoring2 = new TransferMonitoringService(System::nanoTime, 0L, false);
        HistoryService history2 = new HistoryService(repo2, settings, monitoring2);
        try {
            TransferHistoryRepository.LoadResult loaded = history2.loadSync();
            assertFalse(loaded.corrupted());
            assertEquals(0, loaded.skippedInvalidCount());

            List<TransferRecord> records = history2.getSnapshot();
            // Expected 6 records: normal(SEND+RECEIVE), zero(SEND+RECEIVE), cancel(SEND=CANCELLED), peer-drop(RECEIVE=FAILED)
            assertEquals(6, records.size(), "Expected all unique terminal outcomes across both sides");

            TransferRecord rxNormalRecord = records.stream()
                .filter(r -> "rx-normal".equals(r.transferId()))
                .findFirst()
                .orElseThrow();
            assertEquals("verified-bundle.bin", rxNormalRecord.fileName());
            assertEquals(payload.length, rxNormalRecord.fileSize());
            assertEquals(TransferHistoryDirection.RECEIVE, rxNormalRecord.direction());
            assertEquals(TransferHistoryStatus.COMPLETED, rxNormalRecord.status());
            assertEquals("syncshare-downloads", rxNormalRecord.destination());
            assertFalse(rxNormalRecord.destination().contains(tempDir.toString()));
            assertNotNull(rxNormalRecord.transferredAt());

            TransferRecord zeroRecord = records.stream()
                .filter(r -> "rx-zero".equals(r.transferId()))
                .findFirst()
                .orElseThrow();
            assertEquals(0L, zeroRecord.fileSize());
            assertEquals(TransferHistoryStatus.COMPLETED, zeroRecord.status());

            TransferRecord cancelledRecord = records.stream()
                .filter(r -> r.fileName().equals("cancelled-clip.bin") && r.direction() == TransferHistoryDirection.SEND)
                .findFirst()
                .orElseThrow();
            assertEquals(TransferHistoryStatus.CANCELLED, cancelledRecord.status());

            TransferRecord failedRxRecord = records.stream()
                .filter(r -> "rx-peer-drop".equals(r.transferId()))
                .findFirst()
                .orElseThrow();
            assertEquals(TransferHistoryStatus.FAILED, failedRxRecord.status());
        } finally {
            monitoring2.shutdown();
            history2.shutdown();
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
