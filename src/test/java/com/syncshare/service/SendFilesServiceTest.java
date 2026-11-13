package com.syncshare.service;

import com.syncshare.events.ConsoleTransferListener;
import com.syncshare.model.TransferItem;
import com.syncshare.model.TransferStatus;
import com.syncshare.monitoring.MonitoredTransfer;
import com.syncshare.monitoring.TransferMonitorState;
import com.syncshare.transfer.FileReceiver;
import com.syncshare.transfer.FileSender;
import com.syncshare.ui.JavaFxToolkitExtension;
import com.syncshare.ui.controller.SendFileTransferService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManagerFactory;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Queue;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(JavaFxToolkitExtension.class)
class SendFilesServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void validatesHostnamesIpv4PortsAndFileConstraints() throws Exception {
        Path smallFile = tempDir.resolve("notes.txt");
        Files.writeString(smallFile, "syncshare");
        Path emptyFile = tempDir.resolve("empty.bin");
        Files.createFile(emptyFile);
        Path kbFile = tempDir.resolve("chunk.dat");
        Files.write(kbFile, new byte[1536]);

        TransferService service = new TransferService((h, p, f, l) -> {});
        try {
            // Invalid IPs & hostnames
            for (String badHost : List.of(
                "", "   ", "999.999.999.999", "256.1.1.1", "192.168.1",
                "1.2.3.4.5", "01.02.03.04", "192.168.1.42:5050", "bad host!", "-leading"
            )) {
                TransferService.ValidationResult res = service.validateSendInput(badHost, "5050", smallFile.toString());
                assertFalse(res.valid(), "Expected host to be rejected: '" + badHost + "'");
                assertNotNull(res.peerMessage());
            }

            // Valid IPs & hostnames
            for (String goodHost : List.of("127.0.0.1", "192.168.1.42", "localhost", "peer-01.local")) {
                TransferService.ValidationResult res = service.validateSendInput(goodHost, "5050", smallFile.toString());
                assertTrue(res.valid(), "Expected host to be valid: '" + goodHost + "'");
            }

            // Invalid ports
            for (String badPort : List.of("", "   ", "abc", "0", "-1", "65536", "99999")) {
                TransferService.ValidationResult res = service.validateSendInput("127.0.0.1", badPort, smallFile.toString());
                assertFalse(res.valid(), "Expected port to be rejected: '" + badPort + "'");
                assertNotNull(res.portMessage());
            }

            // Missing and directory files
            TransferService.ValidationResult missing = service.validateSendInput(
                "127.0.0.1",
                "5050",
                tempDir.resolve("does-not-exist.zip").toString()
            );
            assertFalse(missing.valid());
            assertNotNull(missing.fileMessage());

            TransferService.ValidationResult directory = service.validateSendInput("127.0.0.1", "5050", tempDir.toString());
            assertFalse(directory.valid());
            assertTrue(directory.fileMessage().toLowerCase().contains("directory"));

            // File summaries and readable size formatting
            TransferService.FileSummary emptySummary = service.inspectSelectedFile(emptyFile.toString());
            assertTrue(emptySummary.valid());
            assertEquals("0 B", emptySummary.formattedSize());

            TransferService.FileSummary kbSummary = service.inspectSelectedFile(kbFile.toString());
            assertTrue(kbSummary.valid());
            assertEquals("1.5 KB", kbSummary.formattedSize());
        } finally {
            service.shutdown();
        }
    }

    @Test
    void blockingSendEmitsRealProgressAndWaitsForVerifiedConfirmation() throws Throwable {
        Path payload = tempDir.resolve("report.bin");
        Files.write(payload, new byte[1000]);

        List<String> stageHistory = new ArrayList<>();
        List<Long> observedBytes = new ArrayList<>();
        CountDownLatch onCompletedGate = new CountDownLatch(1);
        CountDownLatch reachedFullBytes = new CountDownLatch(1);

        TransferService service = new TransferService((host, port, file, listener) -> {
            listener.onStarted(file.getFileName().toString(), 1000L);
            listener.onProgress(file.getFileName().toString(), 400L, 1000L);
            listener.onProgress(file.getFileName().toString(), 1000L, 1000L);
            reachedFullBytes.countDown();
            assertTrue(onCompletedGate.await(5, TimeUnit.SECONDS));
            listener.onCompleted(file.getFileName().toString());
        });

        AtomicReference<TransferItem> finalResult = new AtomicReference<>();
        Thread runner = new Thread(() -> {
            try {
                TransferItem item = service.sendFileBlocking(
                    "tx-progress-1",
                    "127.0.0.1",
                    5050,
                    payload,
                    new OutgoingTransferGateway.ProgressObserver() {
                        @Override
                        public void onStageChanged(TransferStatus status, String headline, String detail) {
                            synchronized (stageHistory) {
                                stageHistory.add(status + ":" + headline);
                            }
                        }

                        @Override
                        public void onProgress(String fileName, long transferredBytes, long totalBytes) {
                            synchronized (observedBytes) {
                                observedBytes.add(transferredBytes);
                            }
                        }
                    },
                    () -> false
                );
                finalResult.set(item);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        try {
            runner.start();
            assertTrue(reachedFullBytes.await(5, TimeUnit.SECONDS));
            // Must NOT mark transfer COMPLETED before FileSender calls onCompleted (after Protocol.VERIFIED)
            synchronized (stageHistory) {
                assertFalse(stageHistory.stream().anyMatch(s -> s.startsWith("COMPLETED:")));
                assertTrue(stageHistory.stream().anyMatch(s -> s.contains("Verifying Integrity")));
            }
            assertTrue(service.hasActiveOutgoingTransfer("127.0.0.1", 5050, payload));
            JavaFxToolkitExtension.runOnFxThread(() ->
                assertEquals(1, service.getActiveTransfers().size())
            );

            onCompletedGate.countDown();
            runner.join(5000);
            assertNotNull(finalResult.get());
            assertEquals(TransferStatus.COMPLETED, finalResult.get().status());
            assertEquals(List.of(0L, 400L, 1000L), observedBytes);
            assertFalse(service.hasActiveOutgoingTransfer("127.0.0.1", 5050, payload));
            JavaFxToolkitExtension.runOnFxThread(() -> {
                assertEquals(0, service.getActiveTransfers().size());
                assertEquals(1, service.getTransferHistory().size());
            });
        } finally {
            onCompletedGate.countDown();
            service.shutdown();
        }
    }

    @Test
    void peerRejectionWithoutOnCompletedIsReportedAsRejectedNeverCompleted() throws Exception {
        Path payload = tempDir.resolve("declined.txt");
        Files.writeString(payload, "decline-me");

        TransferService service = new TransferService((host, port, file, listener) -> {
            // Simulates FileSender returning immediately on Protocol.REJECT without calling onCompleted/onError
        });

        try {
            TransferItem blocking = service.sendFileBlocking(
                "tx-reject-1",
                "127.0.0.1",
                5050,
                payload,
                null,
                () -> false
            );
            assertEquals(TransferStatus.REJECTED, blocking.status());
            assertTrue(blocking.note().toLowerCase().contains("rejected"));

            TransferItem async = service.sendFile("127.0.0.1", 5050, payload).get(5, TimeUnit.SECONDS);
            assertEquals(TransferStatus.REJECTED, async.status());
        } finally {
            service.shutdown();
        }
    }

    @Test
    void connectionFailuresDescribeCausesClearly() throws Throwable {
        Path payload = tempDir.resolve("fail.bin");
        Files.write(payload, new byte[128]);

        assertTrue(TransferService.describeNetworkError(new ConnectException("Connection refused"), "10.0.0.5", 5050)
            .contains("Unable to connect to 10.0.0.5:5050"));
        assertTrue(TransferService.describeNetworkError(new UnknownHostException("unknown.peer"), "unknown.peer", 5050)
            .contains("Cannot resolve destination host"));
        assertTrue(TransferService.describeNetworkError(new SocketTimeoutException("Read timed out"), "10.0.0.5", 5050)
            .contains("timed out"));
        assertTrue(TransferService.describeNetworkError(new SSLHandshakeException("bad cert"), "10.0.0.5", 5050)
            .contains("Mutual TLS 1.3 handshake failed"));

        TransferService service = new TransferService((host, port, file, listener) -> {
            ConnectException error = new ConnectException("Connection refused: connect");
            listener.onError(file.getFileName().toString(), error);
            throw error;
        });

        try {
            assertThrows(
                ConnectException.class,
                () -> service.sendFileBlocking("tx-fail-1", "10.0.0.5", 5050, payload, null, () -> false)
            );
            JavaFxToolkitExtension.runOnFxThread(() -> {
                assertEquals(0, service.getActiveTransfers().size());
                assertEquals(1, service.getTransferHistory().size());
                assertEquals(TransferStatus.FAILED, service.getTransferHistory().get(0).status());
                assertTrue(service.getTransferHistory().get(0).note().contains("Unable to connect to 10.0.0.5:5050"));
            });
        } finally {
            service.shutdown();
        }
    }

    @Test
    void sendFileTransferServiceRunsOffFxThreadAndCleansUpOnCancel() throws Throwable {
        Path payload = tempDir.resolve("cancel.bin");
        Files.write(payload, new byte[4096]);

        CountDownLatch startedLatch = new CountDownLatch(1);
        CountDownLatch allowLateCallbacksLatch = new CountDownLatch(1);
        CountDownLatch transportFinishedLatch = new CountDownLatch(1);
        AtomicBoolean transportRanOnFxThread = new AtomicBoolean(true);

        TransferService gateway = new TransferService((host, port, file, listener) -> {
            try {
                transportRanOnFxThread.set(javafx.application.Platform.isFxApplicationThread());
                listener.onStarted(file.getFileName().toString(), 4096L);
                listener.onProgress(file.getFileName().toString(), 1024L, 4096L);
                startedLatch.countDown();
                try {
                    allowLateCallbacksLatch.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) {
                    // Wait on allowLateCallbacksLatch so we explicitly verify late callbacks after cancel are ignored.
                    allowLateCallbacksLatch.await(5, TimeUnit.SECONDS);
                }
                // Late callbacks after cancellation must be ignored cleanly
                listener.onProgress(file.getFileName().toString(), 4096L, 4096L);
                listener.onCompleted(file.getFileName().toString());
            } finally {
                transportFinishedLatch.countDown();
            }
        });

        CountDownLatch cancelledUiLatch = new CountDownLatch(1);
        SendFileTransferService fxService = new SendFileTransferService();

        try {
            JavaFxToolkitExtension.runOnFxThread(() -> {
                fxService.setOnCancelled(e -> cancelledUiLatch.countDown());
                fxService.configure(gateway, "127.0.0.1", 5050, payload);
                fxService.start();
            });

            assertTrue(startedLatch.await(5, TimeUnit.SECONDS));
            assertFalse(transportRanOnFxThread.get(), "Network send must run off JavaFX Application Thread");

            JavaFxToolkitExtension.runOnFxThread(fxService::cancelCurrentTransfer);
            assertTrue(cancelledUiLatch.await(5, TimeUnit.SECONDS));

            allowLateCallbacksLatch.countDown();
            assertTrue(transportFinishedLatch.await(5, TimeUnit.SECONDS));

            JavaFxToolkitExtension.runOnFxThread(() -> {
                assertEquals(0, gateway.getActiveTransfers().size());
                assertEquals(1, gateway.getTransferHistory().size());
                TransferItem cancelledItem = gateway.getTransferHistory().get(0);
                assertEquals(TransferStatus.CANCELLED, cancelledItem.status());
                assertEquals("Cancelled by user", cancelledItem.note());
            });
        } finally {
            allowLateCallbacksLatch.countDown();
            gateway.shutdown();
        }
    }

    @Test
    void realEndToEndMtlsTransferAndRejectionOverTlsStreams() throws Throwable {
        Path keytool = resolveJdkTool("keytool");
        Path certDir = tempDir.resolve("certs");
        Files.createDirectories(certDir);

        Path identityStore = certDir.resolve("identity.p12");
        Path trustStore = certDir.resolve("trusted-peers.p12");
        Path exportedCert = certDir.resolve("peer.cer");
        String password = "syncshare-test-pass";

        runTool(
            keytool.toString(), "-genkeypair",
            "-alias", "syncshare-peer",
            "-keyalg", "RSA", "-keysize", "2048",
            "-validity", "2",
            "-storetype", "PKCS12",
            "-keystore", identityStore.toString(),
            "-storepass", password, "-keypass", password,
            "-dname", "CN=SyncShareTestPeer,O=SyncShare",
            "-ext", "SAN=IP:127.0.0.1,DNS:localhost"
        );
        runTool(
            keytool.toString(), "-exportcert",
            "-alias", "syncshare-peer",
            "-keystore", identityStore.toString(),
            "-storepass", password,
            "-file", exportedCert.toString()
        );
        runTool(
            keytool.toString(), "-importcert", "-noprompt",
            "-alias", "syncshare-peer",
            "-keystore", trustStore.toString(),
            "-storepass", password,
            "-file", exportedCert.toString()
        );

        SSLContext sslContext = createTestSslContext(identityStore, trustStore, password.toCharArray());

        Path receiveDir = tempDir.resolve("received");
        Path sourceFile = tempDir.resolve("end-to-end-sample.bin");
        byte[] payload = new byte[256 * 1024]; // 4 x 64 KB chunks
        new Random(12345L).nextBytes(payload);
        Files.write(sourceFile, payload);

        String previousReceiveDir = System.getProperty("syncshare.receiveDir");
        System.setProperty("syncshare.receiveDir", receiveDir.toAbsolutePath().toString());
        AtomicBoolean acceptIncoming = new AtomicBoolean(true);

        TransferService transferService = new TransferService((host, port, file, listener) -> {
            InMemorySocket[] pair = InMemorySocket.createPair(port);
            InMemorySocket rawClient = pair[0];
            InMemorySocket rawServer = pair[1];

            SSLSocket clientTls = (SSLSocket) sslContext.getSocketFactory()
                .createSocket(rawClient, host, port, true);
            clientTls.setEnabledProtocols(new String[] {"TLSv1.3"});

            SSLSocket serverTls = (SSLSocket) sslContext.getSocketFactory()
                .createSocket(rawServer, new ByteArrayInputStream(new byte[0]), true);
            serverTls.setEnabledProtocols(new String[] {"TLSv1.3"});
            serverTls.setNeedClientAuth(true);

            AtomicReference<Throwable> serverError = new AtomicReference<>();
            Thread receiverThread = new Thread(() -> {
                try (serverTls) {
                    serverTls.setSoTimeout(15_000);
                    FileReceiver.receive(
                        serverTls,
                        new ConsoleTransferListener(),
                        (name, size, sender) -> acceptIncoming.get()
                    );
                } catch (Throwable t) {
                    serverError.set(t);
                }
            }, "test-mtls-receiver");
            receiverThread.setDaemon(true);
            receiverThread.start();

            try (clientTls) {
                clientTls.setSoTimeout(15_000);
                FileSender.send(clientTls, file, listener);
            } finally {
                receiverThread.join(10_000);
            }
            if (serverError.get() != null) {
                throw new IOException("Receiver failed: " + serverError.get().getMessage(), serverError.get());
            }
        });

        try {
            List<Long> ackOffsets = Collections.synchronizedList(new ArrayList<>());
            List<String> stages = Collections.synchronizedList(new ArrayList<>());

            // 1. Successful multi-chunk mTLS 1.3 transfer with real SHA-256 verification
            TransferItem ok = transferService.sendFileBlocking(
                "mtls-ok",
                "127.0.0.1",
                5050,
                sourceFile,
                new OutgoingTransferGateway.ProgressObserver() {
                    @Override
                    public void onStageChanged(TransferStatus status, String headline, String detail) {
                        stages.add(status + ":" + headline);
                    }

                    @Override
                    public void onProgress(String fileName, long transferredBytes, long totalBytes) {
                        ackOffsets.add(transferredBytes);
                    }
                },
                () -> false
            );

            assertEquals(TransferStatus.COMPLETED, ok.status());
            assertTrue(ackOffsets.size() >= 4, "Expected >= 4 chunk ACKs, got " + ackOffsets);
            assertEquals(payload.length, ackOffsets.get(ackOffsets.size() - 1));
            int verifyIdx = stages.indexOf("TRANSFERRING:Verifying Integrity");
            int verifiedIdx = stages.indexOf("COMPLETED:Verified & Completed");
            assertTrue(verifyIdx >= 0, "Expected Verifying Integrity stage in " + stages);
            assertTrue(verifiedIdx > verifyIdx, "Verified & Completed must occur only after Verifying Integrity");

            Path savedFile;
            try (var walked = Files.walk(receiveDir)) {
                savedFile = walked
                    .filter(Files::isRegularFile)
                    .filter(p -> !p.toString().contains(".resume"))
                    .filter(p -> p.getFileName().toString().endsWith("-" + sourceFile.getFileName().toString()))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("Received file missing under " + receiveDir));
            }
            assertTrue(Arrays.equals(payload, Files.readAllBytes(savedFile)), "Received file bytes mismatch");

            // 2. Peer rejection over real mTLS 1.3 (Protocol.REJECT)
            acceptIncoming.set(false);
            TransferItem rejected = transferService.sendFileBlocking(
                "mtls-reject",
                "127.0.0.1",
                5050,
                sourceFile,
                null,
                () -> false
            );
            assertEquals(TransferStatus.REJECTED, rejected.status());

            MonitoredTransfer okMonitored = transferService.getMonitoringService().getTransfer("mtls-ok").orElseThrow();
            assertEquals(TransferMonitorState.COMPLETED, okMonitored.state());
            assertEquals(payload.length, okMonitored.transferredBytes());
            MonitoredTransfer rejectedMonitored = transferService.getMonitoringService().getTransfer("mtls-reject").orElseThrow();
            assertEquals(TransferMonitorState.FAILED, rejectedMonitored.state());
        } finally {
            transferService.shutdown();
            if (previousReceiveDir == null) {
                System.clearProperty("syncshare.receiveDir");
            } else {
                System.setProperty("syncshare.receiveDir", previousReceiveDir);
            }
        }
    }

    @Test
    void sendFileBlockingEmitsMonitoringEventsAndSupportsMonitoringCancellation() throws Throwable {
        Path payload = tempDir.resolve("monitored-blocking.bin");
        Files.write(payload, new byte[4096]);

        TransferMonitoringService monitoring = new TransferMonitoringService(System::nanoTime, 0L, false);
        CountDownLatch inFlightLatch = new CountDownLatch(1);
        CountDownLatch releaseLatch = new CountDownLatch(1);
        AtomicBoolean workerInterrupted = new AtomicBoolean(false);

        TransferService gateway = new TransferService((host, port, file, listener) -> {
            listener.onStarted(file.getFileName().toString(), 4096L);
            listener.onProgress(file.getFileName().toString(), 2048L, 4096L);
            inFlightLatch.countDown();
            try {
                releaseLatch.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                workerInterrupted.set(true);
                throw e;
            }
        }, monitoring);

        try {
            AtomicReference<TransferItem> resultRef = new AtomicReference<>();
            AtomicReference<Throwable> errorRef = new AtomicReference<>();
            Thread senderWorker = new Thread(() -> {
                try {
                    resultRef.set(gateway.sendFileBlocking(
                        "tx-blocking-mon",
                        "127.0.0.1",
                        5050,
                        payload,
                        null,
                        () -> false
                    ));
                } catch (Throwable t) {
                    errorRef.set(t);
                }
            }, "test-blocking-mon");
            senderWorker.start();

            assertTrue(inFlightLatch.await(5, TimeUnit.SECONDS));
            MonitoredTransfer active = monitoring.getTransfer("tx-blocking-mon").orElseThrow();
            assertEquals(TransferMonitorState.RUNNING, active.state());
            assertEquals(2048L, active.transferredBytes());

            // Cancel via TransferMonitoringService cancel handler
            assertTrue(monitoring.cancelTransfer("tx-blocking-mon"));

            senderWorker.join(5_000);
            assertFalse(senderWorker.isAlive());
            assertTrue(workerInterrupted.get());
            if (errorRef.get() != null) {
                throw errorRef.get();
            }
            assertNotNull(resultRef.get());
            assertEquals(TransferStatus.CANCELLED, resultRef.get().status());

            MonitoredTransfer terminal = monitoring.getTransfer("tx-blocking-mon").orElseThrow();
            assertEquals(TransferMonitorState.CANCELLED, terminal.state());
            JavaFxToolkitExtension.runOnFxThread(() -> {
                assertEquals(0, gateway.getActiveTransfers().size());
                assertEquals(1, gateway.getTransferHistory().size());
                assertEquals(TransferStatus.CANCELLED, gateway.getTransferHistory().get(0).status());
            });
        } finally {
            releaseLatch.countDown();
            gateway.shutdown();
        }
    }

    private static SSLContext createTestSslContext(Path keyStorePath, Path trustStorePath, char[] password) throws Exception {
        KeyStore identity = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(keyStorePath)) {
            identity.load(in, password);
        }
        KeyStore trust = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(trustStorePath)) {
            trust.load(in, password);
        }
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(identity, password);
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(trust);

        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(kmf.getKeyManagers(), tmf.getTrustManagers(), null);
        return ctx;
    }

    private static Path resolveJdkTool(String name) {
        String exeName = System.getProperty("os.name", "").toLowerCase().contains("win")
            ? name + ".exe"
            : name;
        Path candidate = Path.of(System.getProperty("java.home"), "bin", exeName);
        assertTrue(Files.isRegularFile(candidate), "Missing JDK tool: " + candidate);
        return candidate;
    }

    private static void runTool(String... command) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true);
        Process process = pb.start();
        String output = new String(process.getInputStream().readAllBytes());
        assertTrue(process.waitFor(20, TimeUnit.SECONDS), "Tool timed out: " + String.join(" ", command));
        assertEquals(0, process.exitValue(), "Tool failed (" + String.join(" ", command) + "):\n" + output);
    }

    private static final class BlockingBytePipe {
        private final ReentrantLock lock = new ReentrantLock();
        private final Condition dataAvailable = lock.newCondition();
        private final Queue<byte[]> chunks = new ArrayDeque<>();
        private byte[] currentChunk;
        private int currentOffset;
        private boolean writeClosed;
        private boolean readClosed;

        void write(byte[] b, int off, int len) throws IOException {
            if (off < 0 || len < 0 || off + len > b.length) {
                throw new IndexOutOfBoundsException();
            }
            if (len == 0) {
                return;
            }
            byte[] copy = Arrays.copyOfRange(b, off, off + len);
            lock.lock();
            try {
                if (writeClosed || readClosed) {
                    throw new IOException("Pipe closed");
                }
                chunks.add(copy);
                dataAvailable.signalAll();
            } finally {
                lock.unlock();
            }
        }

        int read(byte[] b, int off, int len, int timeoutMs) throws IOException {
            if (off < 0 || len < 0 || off + len > b.length) {
                throw new IndexOutOfBoundsException();
            }
            if (len == 0) {
                return 0;
            }
            long deadlineNanos = timeoutMs > 0 ? System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs) : 0L;
            lock.lock();
            try {
                while (!readClosed && currentChunk == null && chunks.isEmpty() && !writeClosed) {
                    try {
                        if (timeoutMs > 0) {
                            long remaining = deadlineNanos - System.nanoTime();
                            if (remaining <= 0) {
                                throw new SocketTimeoutException("Read timed out");
                            }
                            dataAvailable.awaitNanos(remaining);
                        } else {
                            dataAvailable.await();
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new InterruptedIOException("Interrupted while reading");
                    }
                }
                if (readClosed) {
                    throw new IOException("Socket closed");
                }
                if (currentChunk == null) {
                    currentChunk = chunks.poll();
                    currentOffset = 0;
                }
                if (currentChunk == null) {
                    return -1;
                }
                int available = currentChunk.length - currentOffset;
                int toCopy = Math.min(len, available);
                System.arraycopy(currentChunk, currentOffset, b, off, toCopy);
                currentOffset += toCopy;
                if (currentOffset >= currentChunk.length) {
                    currentChunk = null;
                    currentOffset = 0;
                }
                return toCopy;
            } finally {
                lock.unlock();
            }
        }

        void closeWrite() {
            lock.lock();
            try {
                writeClosed = true;
                dataAvailable.signalAll();
            } finally {
                lock.unlock();
            }
        }

        void closeRead() {
            lock.lock();
            try {
                readClosed = true;
                dataAvailable.signalAll();
            } finally {
                lock.unlock();
            }
        }
    }

    private static final class InMemorySocket extends Socket {
        private final BlockingBytePipe inbound;
        private final BlockingBytePipe outbound;
        private final int port;
        private volatile int soTimeoutMs;
        private volatile boolean tcpNoDelay;
        private volatile boolean closed;
        private volatile boolean inputShutdown;
        private volatile boolean outputShutdown;
        private final InputStream inputStream = new InputStream() {
            @Override
            public int read() throws IOException {
                byte[] single = new byte[1];
                int count = inbound.read(single, 0, 1, soTimeoutMs);
                return count < 0 ? -1 : (single[0] & 0xFF);
            }

            @Override
            public int read(byte[] b, int off, int len) throws IOException {
                return inbound.read(b, off, len, soTimeoutMs);
            }

            @Override
            public void close() {
                closeSocketStreams();
            }
        };
        private final OutputStream outputStream = new OutputStream() {
            @Override
            public void write(int b) throws IOException {
                outbound.write(new byte[] {(byte) b}, 0, 1);
            }

            @Override
            public void write(byte[] b, int off, int len) throws IOException {
                outbound.write(b, off, len);
            }

            @Override
            public void close() {
                closeSocketStreams();
            }
        };

        private InMemorySocket(BlockingBytePipe inbound, BlockingBytePipe outbound, int port) {
            this.inbound = inbound;
            this.outbound = outbound;
            this.port = port;
        }

        static InMemorySocket[] createPair(int port) {
            BlockingBytePipe clientToServer = new BlockingBytePipe();
            BlockingBytePipe serverToClient = new BlockingBytePipe();
            return new InMemorySocket[] {
                new InMemorySocket(serverToClient, clientToServer, port),
                new InMemorySocket(clientToServer, serverToClient, port)
            };
        }

        @Override
        public InputStream getInputStream() {
            return inputStream;
        }

        @Override
        public OutputStream getOutputStream() {
            return outputStream;
        }

        @Override
        public void setSoTimeout(int timeout) {
            this.soTimeoutMs = Math.max(0, timeout);
        }

        @Override
        public int getSoTimeout() {
            return soTimeoutMs;
        }

        @Override
        public void setTcpNoDelay(boolean on) {
            this.tcpNoDelay = on;
        }

        @Override
        public boolean getTcpNoDelay() {
            return tcpNoDelay;
        }

        @Override
        public boolean isConnected() {
            return !closed;
        }

        @Override
        public boolean isClosed() {
            return closed;
        }

        @Override
        public boolean isInputShutdown() {
            return inputShutdown || closed;
        }

        @Override
        public boolean isOutputShutdown() {
            return outputShutdown || closed;
        }

        @Override
        public void shutdownInput() {
            inputShutdown = true;
            inbound.closeRead();
        }

        @Override
        public void shutdownOutput() {
            outputShutdown = true;
            outbound.closeWrite();
        }

        @Override
        public InetAddress getInetAddress() {
            return InetAddress.getLoopbackAddress();
        }

        @Override
        public InetAddress getLocalAddress() {
            return InetAddress.getLoopbackAddress();
        }

        @Override
        public int getPort() {
            return port;
        }

        @Override
        public int getLocalPort() {
            return port;
        }

        @Override
        public SocketAddress getRemoteSocketAddress() {
            return new InetSocketAddress(InetAddress.getLoopbackAddress(), port);
        }

        @Override
        public SocketAddress getLocalSocketAddress() {
            return new InetSocketAddress(InetAddress.getLoopbackAddress(), port);
        }

        private void closeSocketStreams() {
            if (!closed) {
                closed = true;
                outbound.closeWrite();
                inbound.closeRead();
            }
        }

        @Override
        public void close() {
            closeSocketStreams();
        }
    }
}
