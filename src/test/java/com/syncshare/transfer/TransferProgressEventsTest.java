package com.syncshare.transfer;

import com.syncshare.events.ConsoleTransferListener;
import com.syncshare.events.TransferSessionListener;
import com.syncshare.model.TransferDirection;
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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TransferProgressEventsTest {

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
    void realMtlsTransferExposesActualChunkProgressAndCompletion() throws Exception {
        Path receiveDir = tempDir.resolve("received-ok");
        Files.createDirectories(receiveDir);

        byte[] payload = new byte[192 * 1024]; // 3 x 64 KB chunks
        new Random(77).nextBytes(payload);
        Path source = tempDir.resolve("sample-payload.bin");
        Files.write(source, payload);

        RecordingListener senderEvents = new RecordingListener();
        RecordingListener receiverEvents = new RecordingListener();

        withReceiveDir(receiveDir, () -> {
            try (SSLServerSocket server = (SSLServerSocket) sharedSslContext.getServerSocketFactory().createServerSocket(0);
                 ExecutorService executor = Executors.newFixedThreadPool(2)) {
                server.setEnabledProtocols(new String[] {"TLSv1.3"});
                server.setNeedClientAuth(true);
                server.setSoTimeout(10_000);

                Future<?> receiverFuture = executor.submit(() -> {
                    try (SSLSocket client = (SSLSocket) server.accept()) {
                        client.setSoTimeout(10_000);
                        FileReceiver.receive(
                            client,
                            new ConsoleTransferListener(),
                            (name, size, addr) -> true,
                            "rx-1",
                            receiverEvents
                        );
                    }
                    return null;
                });

                try (SSLSocket client = (SSLSocket) sharedSslContext.getSocketFactory().createSocket("127.0.0.1", server.getLocalPort())) {
                    client.setEnabledProtocols(new String[] {"TLSv1.3"});
                    client.setSoTimeout(10_000);
                    FileSender.send(client, source, "tx-1", senderEvents);
                }

                receiverFuture.get(10, TimeUnit.SECONDS);
            }
        });

        assertTrue(senderEvents.connectingCount > 0);
        assertTrue(senderEvents.started);
        assertTrue(senderEvents.completed);
        assertFalse(senderEvents.failed);
        assertEquals(List.of(0L, 65536L, 131072L, 196608L), senderEvents.progressBytes);
        assertEquals(TransferDirection.OUTGOING, senderEvents.lastDirection);

        assertTrue(receiverEvents.connectingCount > 0);
        assertTrue(receiverEvents.started);
        assertTrue(receiverEvents.completed);
        assertFalse(receiverEvents.failed);
        assertEquals(List.of(0L, 65536L, 131072L, 196608L), receiverEvents.progressBytes);
        assertEquals(TransferDirection.INCOMING, receiverEvents.lastDirection);

        Path savedFile = findSingleReceivedFile(receiveDir);
        assertTrue(Arrays.equals(payload, Files.readAllBytes(savedFile)));
    }

    @Test
    void zeroByteFileCompletesOnlyAfterProtocolVerification() throws Exception {
        Path receiveDir = tempDir.resolve("received-zero");
        Files.createDirectories(receiveDir);

        Path emptyFile = tempDir.resolve("empty.txt");
        Files.write(emptyFile, new byte[0]);

        RecordingListener senderEvents = new RecordingListener();
        RecordingListener receiverEvents = new RecordingListener();

        withReceiveDir(receiveDir, () -> {
            try (SSLServerSocket server = (SSLServerSocket) sharedSslContext.getServerSocketFactory().createServerSocket(0);
                 ExecutorService executor = Executors.newFixedThreadPool(2)) {
                server.setEnabledProtocols(new String[] {"TLSv1.3"});
                server.setNeedClientAuth(true);
                server.setSoTimeout(10_000);

                Future<?> receiverFuture = executor.submit(() -> {
                    try (SSLSocket client = (SSLSocket) server.accept()) {
                        client.setSoTimeout(10_000);
                        FileReceiver.receive(
                            client,
                            new ConsoleTransferListener(),
                            (name, size, addr) -> true,
                            "rx-zero",
                            receiverEvents
                        );
                    }
                    return null;
                });

                try (SSLSocket client = (SSLSocket) sharedSslContext.getSocketFactory().createSocket("127.0.0.1", server.getLocalPort())) {
                    client.setEnabledProtocols(new String[] {"TLSv1.3"});
                    client.setSoTimeout(10_000);
                    FileSender.send(client, emptyFile, "tx-zero", senderEvents);
                }

                receiverFuture.get(10, TimeUnit.SECONDS);
            }
        });

        assertTrue(senderEvents.started);
        assertTrue(senderEvents.completed);
        assertFalse(senderEvents.failed);
        assertEquals(0L, senderEvents.totalBytes);
        assertEquals(List.of(0L), senderEvents.progressBytes);

        assertTrue(receiverEvents.started);
        assertTrue(receiverEvents.completed);
        assertEquals(0L, Files.size(findSingleReceivedFile(receiveDir)));
    }

    @Test
    void receiverRejectionReportsFailureAndNeverCompletion() throws Exception {
        Path source = tempDir.resolve("rejected.dat");
        Files.write(source, new byte[1024]);

        RecordingListener senderEvents = new RecordingListener();
        RecordingListener receiverEvents = new RecordingListener();

        try (SSLServerSocket server = (SSLServerSocket) sharedSslContext.getServerSocketFactory().createServerSocket(0);
             ExecutorService executor = Executors.newFixedThreadPool(2)) {
            server.setEnabledProtocols(new String[] {"TLSv1.3"});
            server.setNeedClientAuth(true);
            server.setSoTimeout(10_000);

            Future<?> receiverFuture = executor.submit(() -> {
                try (SSLSocket client = (SSLSocket) server.accept()) {
                    client.setSoTimeout(10_000);
                    FileReceiver.receive(
                        client,
                        new ConsoleTransferListener(),
                        (name, size, addr) -> false,
                        "rx-reject",
                        receiverEvents
                    );
                }
                return null;
            });

            try (SSLSocket client = (SSLSocket) sharedSslContext.getSocketFactory().createSocket("127.0.0.1", server.getLocalPort())) {
                client.setEnabledProtocols(new String[] {"TLSv1.3"});
                client.setSoTimeout(10_000);
                assertThrows(IOException.class, () -> FileSender.send(client, source, "tx-reject", senderEvents));
            }

            receiverFuture.get(10, TimeUnit.SECONDS);
        }

        assertFalse(senderEvents.completed);
        assertTrue(senderEvents.failed);
        assertTrue(senderEvents.lastFailureReason.toLowerCase().contains("rejected"));

        assertFalse(receiverEvents.completed);
        assertTrue(receiverEvents.failed);
    }

    @Test
    void interruptedConnectionMidTransferReportsFailureAndPreservesPartialProgress() throws Exception {
        Path receiveDir = tempDir.resolve("received-drop");
        Files.createDirectories(receiveDir);

        byte[] payload = new byte[256 * 1024]; // 4 chunks
        new Random(99).nextBytes(payload);
        Path source = tempDir.resolve("large.bin");
        Files.write(source, payload);

        AtomicReference<SSLSocket> acceptedSocket = new AtomicReference<>();
        RecordingListener senderEvents = new RecordingListener();
        RecordingListener receiverEvents = new RecordingListener() {
            @Override
            public void onProgress(String transferId, TransferDirection direction, String fileName, long transferredBytes, long totalBytes) {
                super.onProgress(transferId, direction, fileName, transferredBytes, totalBytes);
                if (transferredBytes >= 65536L) {
                    SSLSocket sock = acceptedSocket.get();
                    if (sock != null && !sock.isClosed()) {
                        try {
                            sock.close();
                        } catch (IOException ignored) {
                        }
                    }
                }
            }
        };

        withReceiveDir(receiveDir, () -> {
            try (SSLServerSocket server = (SSLServerSocket) sharedSslContext.getServerSocketFactory().createServerSocket(0);
                 ExecutorService executor = Executors.newFixedThreadPool(2)) {
                server.setEnabledProtocols(new String[] {"TLSv1.3"});
                server.setNeedClientAuth(true);
                server.setSoTimeout(10_000);

                Future<?> receiverFuture = executor.submit(() -> {
                    try (SSLSocket client = (SSLSocket) server.accept()) {
                        client.setSoTimeout(10_000);
                        acceptedSocket.set(client);
                        FileReceiver.receive(
                            client,
                            new ConsoleTransferListener(),
                            (name, size, addr) -> true,
                            "rx-drop",
                            receiverEvents
                        );
                    }
                    return null;
                });

                try (SSLSocket client = (SSLSocket) sharedSslContext.getSocketFactory().createSocket("127.0.0.1", server.getLocalPort())) {
                    client.setEnabledProtocols(new String[] {"TLSv1.3"});
                    client.setSoTimeout(10_000);
                    assertThrows(IOException.class, () -> FileSender.send(client, source, "tx-drop", senderEvents));
                }

                assertThrows(Exception.class, () -> receiverFuture.get(10, TimeUnit.SECONDS));
            }
        });

        assertFalse(senderEvents.completed);
        assertTrue(senderEvents.failed);
        assertTrue(receiverEvents.progressBytes.stream().anyMatch(b -> b >= 65536L && b < payload.length));
        assertFalse(receiverEvents.completed);
        assertTrue(receiverEvents.failed);
    }

    @Test
    void concurrentTransfersEmitIndependentIdsAndProgress() throws Exception {
        Path receiveDir = tempDir.resolve("received-concurrent");
        Files.createDirectories(receiveDir);

        byte[] dataA = new byte[128 * 1024];
        byte[] dataB = new byte[128 * 1024];
        new Random(1).nextBytes(dataA);
        new Random(2).nextBytes(dataB);
        Path fileA = tempDir.resolve("alpha.bin");
        Path fileB = tempDir.resolve("beta.bin");
        Files.write(fileA, dataA);
        Files.write(fileB, dataB);

        CopyOnWriteArrayList<String> completedIds = new CopyOnWriteArrayList<>();
        TransferSessionListener sharedListener = new TransferSessionListener() {
            @Override
            public void onCompleted(String transferId, TransferDirection direction, String fileName) {
                completedIds.add(transferId + ":" + direction.name() + ":" + fileName);
            }
        };

        withReceiveDir(receiveDir, () -> {
            try (SSLServerSocket server = (SSLServerSocket) sharedSslContext.getServerSocketFactory().createServerSocket(0);
                 ExecutorService executor = Executors.newFixedThreadPool(4)) {
                server.setEnabledProtocols(new String[] {"TLSv1.3"});
                server.setNeedClientAuth(true);
                server.setSoTimeout(10_000);

                Future<?> rx1 = executor.submit(() -> acceptAndReceive(server, sharedListener, "rx-a"));
                Future<?> rx2 = executor.submit(() -> acceptAndReceive(server, sharedListener, "rx-b"));

                Future<?> tx1 = executor.submit(() -> {
                    try (SSLSocket client = (SSLSocket) sharedSslContext.getSocketFactory().createSocket("127.0.0.1", server.getLocalPort())) {
                        client.setEnabledProtocols(new String[] {"TLSv1.3"});
                        client.setSoTimeout(10_000);
                        FileSender.send(client, fileA, "tx-a", sharedListener);
                    }
                    return null;
                });

                Future<?> tx2 = executor.submit(() -> {
                    try (SSLSocket client = (SSLSocket) sharedSslContext.getSocketFactory().createSocket("127.0.0.1", server.getLocalPort())) {
                        client.setEnabledProtocols(new String[] {"TLSv1.3"});
                        client.setSoTimeout(10_000);
                        FileSender.send(client, fileB, "tx-b", sharedListener);
                    }
                    return null;
                });

                tx1.get(15, TimeUnit.SECONDS);
                tx2.get(15, TimeUnit.SECONDS);
                rx1.get(15, TimeUnit.SECONDS);
                rx2.get(15, TimeUnit.SECONDS);
            }
        });

        assertEquals(4, completedIds.size());
        assertTrue(completedIds.contains("tx-a:OUTGOING:alpha.bin"));
        assertTrue(completedIds.contains("tx-b:OUTGOING:beta.bin"));
    }

    private static Void acceptAndReceive(
        SSLServerSocket server,
        TransferSessionListener listener,
        String rxId
    ) throws IOException {
        try (SSLSocket client = (SSLSocket) server.accept()) {
            client.setSoTimeout(10_000);
            FileReceiver.receive(
                client,
                new ConsoleTransferListener(),
                (name, size, addr) -> true,
                rxId,
                listener
            );
        }
        return null;
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

    private static class RecordingListener implements TransferSessionListener {
        volatile int connectingCount;
        volatile boolean started;
        volatile boolean completed;
        volatile boolean failed;
        volatile long totalBytes = -1L;
        volatile String lastFailureReason = "";
        volatile TransferDirection lastDirection;
        final List<Long> progressBytes = Collections.synchronizedList(new ArrayList<>());

        @Override
        public void onConnecting(
            String transferId,
            TransferDirection direction,
            String fileName,
            long totalBytes,
            String peerAddress,
            String statusNote
        ) {
            connectingCount++;
            lastDirection = direction;
        }

        @Override
        public void onStarted(
            String transferId,
            TransferDirection direction,
            String fileName,
            long totalBytes,
            long initialBytes,
            String peerAddress
        ) {
            started = true;
            this.totalBytes = totalBytes;
            lastDirection = direction;
        }

        @Override
        public void onProgress(
            String transferId,
            TransferDirection direction,
            String fileName,
            long transferredBytes,
            long totalBytes
        ) {
            progressBytes.add(transferredBytes);
            lastDirection = direction;
        }

        @Override
        public void onCompleted(String transferId, TransferDirection direction, String fileName) {
            completed = true;
            lastDirection = direction;
        }

        @Override
        public void onFailed(
            String transferId,
            TransferDirection direction,
            String fileName,
            String reason,
            Exception error
        ) {
            failed = true;
            lastFailureReason = reason != null ? reason : "";
            lastDirection = direction;
        }

        @Override
        public void onCancelled(String transferId, TransferDirection direction, String fileName, String reason) {
            lastDirection = direction;
        }
    }
}
