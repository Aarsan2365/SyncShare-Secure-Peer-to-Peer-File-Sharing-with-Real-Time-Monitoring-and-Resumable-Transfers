package com.syncshare.service;

import com.syncshare.events.ConsoleTransferListener;
import com.syncshare.events.TransferListener;
import com.syncshare.model.AppSettings;
import com.syncshare.model.ConnectionState;
import com.syncshare.model.IncomingRequestItem;
import com.syncshare.model.IncomingTransferSession;
import com.syncshare.model.TransferDirection;
import com.syncshare.model.TransferItem;
import com.syncshare.model.TransferStatus;
import com.syncshare.network.PeerServer;
import com.syncshare.network.Protocol;
import com.syncshare.network.ProtocolIO;
import com.syncshare.resume.ResumeStore;
import com.syncshare.security.TlsSupport;
import com.syncshare.transfer.FileSender;
import com.syncshare.transfer.HashUtils;
import com.syncshare.ui.JavaFxToolkitExtension;
import javafx.beans.value.ChangeListener;
import javafx.collections.ListChangeListener;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryFlag;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.UserPrincipal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(JavaFxToolkitExtension.class)
class ReceiveFilesBackendTest {

    private static final char[] TLS_PASSWORD = "syncshare-test-pass".toCharArray();

    private static Path sharedCertDir;
    private static SSLContext receiverContext;
    private static SSLContext senderContext;
    private static String senderCertFingerprint;

    @TempDir
    Path tempDir;

    private String previousReceiveDirProperty;

    @BeforeAll
    static void initMtlsMaterials() throws Exception {
        sharedCertDir = Files.createTempDirectory("syncshare-mtls-certs-");
        Path receiverKey = sharedCertDir.resolve("receiver-id.p12");
        Path receiverTrust = sharedCertDir.resolve("receiver-trust.p12");
        Path receiverCert = sharedCertDir.resolve("receiver.cer");

        Path senderKey = sharedCertDir.resolve("sender-id.p12");
        Path senderTrust = sharedCertDir.resolve("sender-trust.p12");
        Path senderCert = sharedCertDir.resolve("sender.cer");

        runKeytool(
            "-genkeypair", "-alias", "receiver",
            "-keyalg", "EC", "-groupname", "secp256r1", "-sigalg", "SHA256withECDSA",
            "-dname", "CN=SyncShare-Receiver, O=SyncShare",
            "-validity", "30",
            "-keystore", receiverKey.toString(), "-storetype", "PKCS12",
            "-storepass", new String(TLS_PASSWORD), "-keypass", new String(TLS_PASSWORD)
        );
        runKeytool(
            "-genkeypair", "-alias", "sender",
            "-keyalg", "EC", "-groupname", "secp256r1", "-sigalg", "SHA256withECDSA",
            "-dname", "CN=SyncShare-Sender, O=SyncShare",
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

        byte[] der = readPemBody(senderCert);
        senderCertFingerprint = HexFormat.of().formatHex(HashUtils.newSha256().digest(der));
    }

    @BeforeEach
    void captureSystemProperty() {
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
    void validDirectoryPassesAndMissingDirectoryCanBeCreatedAndValidated() throws IOException {
        Path validDir = tempDir.resolve("valid-dest");
        Files.createDirectories(validDir);

        ReceiveDestinationValidator.ValidationResult validResult =
            ReceiveDestinationValidator.validateDirectory(validDir.toString());
        assertTrue(validResult.valid(), validResult.message());
        assertFalse(validResult.missingDirectory());
        assertEquals(validDir.toRealPath(), validResult.resolvedDirectory());

        Path missingDir = tempDir.resolve("new-folder").resolve("nested");
        ReceiveDestinationValidator.ValidationResult missingResult =
            ReceiveDestinationValidator.validateDirectory(missingDir.toString());
        assertFalse(missingResult.valid());
        assertTrue(missingResult.missingDirectory());

        ReceiveDestinationValidator.ValidationResult createdResult =
            ReceiveDestinationValidator.createAndValidateDirectory(missingDir.toString());
        assertTrue(createdResult.valid());
        assertTrue(Files.isDirectory(missingDir));
    }

    @Test
    void invalidRegularFileBlankAndNullCharDirectoriesAreRejected() throws IOException {
        Path regularFile = tempDir.resolve("not-a-folder.txt");
        Files.writeString(regularFile, "data", StandardCharsets.UTF_8);

        assertFalse(ReceiveDestinationValidator.validateDirectory("").valid());
        assertFalse(ReceiveDestinationValidator.validateDirectory((String) null).valid());
        assertFalse(ReceiveDestinationValidator.validateDirectory("bad\0path").valid());

        ReceiveDestinationValidator.ValidationResult fileResult =
            ReceiveDestinationValidator.validateDirectory(regularFile.toString());
        assertFalse(fileResult.valid());
        assertFalse(fileResult.missingDirectory());
        assertTrue(fileResult.message().toLowerCase().contains("not a directory"));
    }

    @Test
    void nonWritableDirectoryIsRejectedByValidatorAndConnectionService() throws Exception {
        Path lockedDir = tempDir.resolve("locked-dest");
        Files.createDirectories(lockedDir);

        AclFileAttributeView aclView = Files.getFileAttributeView(lockedDir, AclFileAttributeView.class);
        if (aclView == null) {
            return;
        }
        List<AclEntry> originalAcl = List.copyOf(aclView.getAcl());
        UserPrincipal owner = aclView.getOwner();
        AclEntry denyWrite = AclEntry.newBuilder()
            .setType(AclEntryType.DENY)
            .setPrincipal(owner)
            .setPermissions(EnumSet.of(
                AclEntryPermission.WRITE_DATA,
                AclEntryPermission.APPEND_DATA,
                AclEntryPermission.ADD_FILE,
                AclEntryPermission.ADD_SUBDIRECTORY
            ))
            .setFlags(AclEntryFlag.FILE_INHERIT, AclEntryFlag.DIRECTORY_INHERIT)
            .build();
        List<AclEntry> lockedAcl = new ArrayList<>(originalAcl.size() + 1);
        lockedAcl.add(denyWrite);
        lockedAcl.addAll(originalAcl);

        try {
            aclView.setAcl(lockedAcl);

            ReceiveDestinationValidator.ValidationResult check =
                ReceiveDestinationValidator.validateDirectory(lockedDir);
            assertFalse(check.valid(), "Non-writable directory must fail validation");
            assertFalse(check.missingDirectory());

            int port = findFreePort();
            SettingsService settings = createSettings("TestHost", port, lockedDir, false);
            ConnectionService connection = createMtlsConnectionService(settings, new TransferService(), true);
            try {
                assertFalse(connection.startReceiving(lockedDir));
                assertFalse(connection.isListening());
                assertEquals(ConnectionState.ERROR, connection.getStatus().state());
            } finally {
                connection.shutdown();
            }
        } finally {
            aclView.setAcl(originalAcl);
        }
    }

    @Test
    void pathTraversalNamesAreBlockedAndRejectedOverLiveWireProtocol() throws Exception {
        Path destRoot = tempDir.resolve("safe-root");
        Files.createDirectories(destRoot);

        List<String> maliciousNames = List.of(
            "../secret.txt",
            "..\\secret.txt",
            "nested/secret.txt",
            "nested\\secret.txt",
            "/etc/passwd",
            "C:evil.txt",
            "C:\\temp\\evil.txt",
            ".",
            "..",
            "CON",
            "NUL.txt",
            "bad\0name.txt",
            " "
        );
        for (String bad : maliciousNames) {
            assertFalse(
                ReceiveDestinationValidator.isSafeSingleFileName(bad),
                "Expected unsafe filename to be rejected: " + bad
            );
            assertThrows(
                IOException.class,
                () -> ReceiveDestinationValidator.resolveSafeFile(destRoot, bad)
            );
        }
        assertTrue(ReceiveDestinationValidator.isSafeSingleFileName("quarterly-report_v2.pdf"));

        int port = findFreePort();
        SettingsService settings = createSettings("Receiver", port, destRoot, false);
        TransferService transfers = new TransferService();
        ConnectionService connection = createMtlsConnectionService(settings, transfers, true);

        CountDownLatch errorObserved = new CountDownLatch(1);
        AtomicReference<IncomingTransferSession> failedSession = new AtomicReference<>();
        ChangeListener<IncomingTransferSession> sessionListener = (obs, o, n) -> {
            if (n != null && n.stage() == IncomingTransferSession.Stage.FAILED) {
                failedSession.set(n);
                errorObserved.countDown();
            }
        };
        connection.incomingSessionProperty().addListener(sessionListener);

        try {
            assertTrue(connection.startReceiving(destRoot));

            byte wireResponse;
            try (SSLSocket socket = TlsSupport.connect("127.0.0.1", port, senderContext)) {
                socket.setSoTimeout(10_000);
                DataOutputStream out = new DataOutputStream(socket.getOutputStream());
                DataInputStream in = new DataInputStream(socket.getInputStream());

                ProtocolIO.writeHeader(out);
                out.writeByte(Protocol.FILE_OFFER);
                out.writeUTF("../../escaped-payload.txt");
                out.writeLong(16L);
                out.writeUTF("0".repeat(64));
                out.flush();

                wireResponse = in.readByte();
            }

            assertEquals(Protocol.REJECT, wireResponse);
            assertTrue(errorObserved.await(10, TimeUnit.SECONDS));
            assertNotNull(failedSession.get());
            assertTrue(failedSession.get().statusDetail().toLowerCase().contains("unsafe"));
            assertFalse(Files.exists(tempDir.resolve("escaped-payload.txt")));
            assertFalse(Files.exists(destRoot.getParent().resolve("escaped-payload.txt")));
        } finally {
            connection.incomingSessionProperty().removeListener(sessionListener);
            connection.shutdown();
            transfers.shutdown();
        }
    }

    @Test
    void existingFilesAreNeverOverwrittenAndUniqueNamesAreAllocated() throws IOException {
        Path destFolder = tempDir.resolve("non-overwrite");
        Files.createDirectories(destFolder);

        Path existing = destFolder.resolve("report.pdf");
        Files.writeString(existing, "original-content", StandardCharsets.UTF_8);

        Path next = ReceiveDestinationValidator.resolveNonOverwritingPath(existing);
        assertEquals("report (1).pdf", next.getFileName().toString());
        assertEquals("original-content", Files.readString(existing, StandardCharsets.UTF_8));

        // Verify ResumeStore.finish() avoids overwriting when an unrelated file exists at the target name
        byte[] payload = "brand-new-payload-data".getBytes(StandardCharsets.UTF_8);
        String hash = HexFormat.of().formatHex(HashUtils.newSha256().digest(payload));
        String identity = "notes.txt\n" + payload.length + "\n" + hash;
        String id = HexFormat.of().formatHex(
            HashUtils.newSha256().digest(identity.getBytes(StandardCharsets.UTF_8))
        );
        Path preExistingTarget = destFolder.resolve(id + "-notes.txt");
        Files.writeString(preExistingTarget, "do-not-overwrite-me", StandardCharsets.UTF_8);

        Path savedPath;
        try (ResumeStore store = new ResumeStore(destFolder, "notes.txt", payload.length, hash)) {
            assertEquals(0L, store.offset());
            store.append(payload, payload.length);
            savedPath = store.finish();
        }

        assertNotEquals(preExistingTarget, savedPath);
        assertEquals(id + "-notes (1).txt", savedPath.getFileName().toString());
        assertEquals("do-not-overwrite-me", Files.readString(preExistingTarget, StandardCharsets.UTF_8));
        assertArrayEquals(payload, Files.readAllBytes(savedPath));
    }

    @Test
    void realMtlsIncomingConnectionApprovalProgressAndVerifiedCompletion() throws Throwable {
        Path destDir = tempDir.resolve("mtls-receive-dest");
        Files.createDirectories(destDir);
        Path sourceFile = tempDir.resolve("telemetry-archive.bin");
        byte[] sourceBytes = new byte[220_000];
        new Random(1337L).nextBytes(sourceBytes);
        Files.write(sourceFile, sourceBytes);
        String expectedSha = HashUtils.calculateSHA256(sourceFile);

        int port = findFreePort();
        SettingsService settings = createSettings("Lab-Receiver", port, destDir, true);
        TransferService transfers = new TransferService();
        ConnectionService connection = createMtlsConnectionService(settings, transfers, true);

        List<IncomingTransferSession.Stage> observedStages = new CopyOnWriteArrayList<>();
        List<Long> observedReceivingBytes = new CopyOnWriteArrayList<>();
        CountDownLatch approvalQueuedLatch = new CountDownLatch(1);
        CountDownLatch completedLatch = new CountDownLatch(1);
        AtomicReference<IncomingTransferSession> finalSessionRef = new AtomicReference<>();

        ChangeListener<IncomingTransferSession> sessionListener = (obs, oldVal, nextVal) -> {
            if (nextVal == null || nextVal.stage() == IncomingTransferSession.Stage.IDLE) {
                return;
            }
            observedStages.add(nextVal.stage());
            if (nextVal.stage() == IncomingTransferSession.Stage.RECEIVING) {
                observedReceivingBytes.add(nextVal.transferredBytes());
            } else if (nextVal.stage() == IncomingTransferSession.Stage.COMPLETED) {
                finalSessionRef.set(nextVal);
                completedLatch.countDown();
            }
        };
        connection.incomingSessionProperty().addListener(sessionListener);

        ListChangeListener<IncomingRequestItem> pendingListener = change -> {
            if (!connection.getPendingRequests().isEmpty()) {
                approvalQueuedLatch.countDown();
            }
        };
        connection.getPendingRequests().addListener(pendingListener);

        try {
            assertTrue(connection.startReceiving(destDir));
            assertTrue(connection.isListening());
            assertEquals(port, connection.getBoundPort());

            AtomicReference<Throwable> senderError = new AtomicReference<>();
            CountDownLatch senderFinished = new CountDownLatch(1);
            Thread senderThread = new Thread(() -> {
                try (SSLSocket socket = TlsSupport.connect("127.0.0.1", port, senderContext)) {
                    socket.setSoTimeout(15_000);
                    FileSender.send(socket, sourceFile, new ConsoleTransferListener());
                } catch (Throwable t) {
                    senderError.set(t);
                } finally {
                    senderFinished.countDown();
                }
            }, "test-mtls-sender");
            senderThread.start();

            assertTrue(approvalQueuedLatch.await(10, TimeUnit.SECONDS), "Approval request never arrived");
            JavaFxToolkitExtension.runOnFxThread(() -> {
                assertEquals(1, connection.getPendingRequests().size());
                IncomingRequestItem req = connection.getPendingRequests().get(0);
                assertEquals("telemetry-archive.bin", req.fileName());
                assertEquals(sourceBytes.length, req.fileSize());
                assertTrue(connection.approveRequest(req.requestId(), true));
            });

            assertTrue(senderFinished.await(15, TimeUnit.SECONDS), "Sender did not finish in time");
            if (senderError.get() != null) {
                throw new AssertionError("Sender failed unexpectedly", senderError.get());
            }
            assertTrue(completedLatch.await(10, TimeUnit.SECONDS), "Completed session event not observed");

            IncomingTransferSession done = finalSessionRef.get();
            assertNotNull(done);
            assertEquals("telemetry-archive.bin", done.fileName());
            assertEquals(sourceBytes.length, done.totalBytes());
            assertEquals(sourceBytes.length, done.transferredBytes());
            assertTrue(done.senderAddress().contains("CN=SyncShare-Sender"));
            assertEquals(senderCertFingerprint, done.peerIdentity());
            assertNotNull(done.savedPath());
            assertTrue(Files.isRegularFile(done.savedPath()));
            assertEquals(sourceBytes.length, Files.size(done.savedPath()));
            assertEquals(expectedSha, HashUtils.calculateSHA256(done.savedPath()));
            assertArrayEquals(sourceBytes, Files.readAllBytes(done.savedPath()));
            assertTrue(done.savedPath().toAbsolutePath().startsWith(destDir.toRealPath()));

            assertTrue(observedStages.contains(IncomingTransferSession.Stage.CONNECTED));
            assertTrue(observedStages.contains(IncomingTransferSession.Stage.AWAITING_APPROVAL));
            assertTrue(observedStages.contains(IncomingTransferSession.Stage.RECEIVING));
            assertTrue(
                observedReceivingBytes.stream().anyMatch(b -> b > 0L && b < sourceBytes.length),
                "Expected real intermediate chunk progress before completion, got: " + observedReceivingBytes
            );

            JavaFxToolkitExtension.runOnFxThread(() -> {
                List<TransferItem> history = transfers.getTransferHistory();
                assertEquals(1, history.size());
                TransferItem item = history.get(0);
                assertEquals(TransferDirection.INCOMING, item.direction());
                assertEquals(TransferStatus.COMPLETED, item.status());
                assertEquals("telemetry-archive.bin", item.fileName());
                assertEquals(sourceBytes.length, item.totalBytes());
            });
        } finally {
            connection.incomingSessionProperty().removeListener(sessionListener);
            connection.getPendingRequests().removeListener(pendingListener);
            connection.shutdown();
            transfers.shutdown();
        }
    }

    @Test
    void manualRejectionCancelsTransferWithoutCreatingDestinationFile() throws Throwable {
        Path destDir = tempDir.resolve("reject-dest");
        Files.createDirectories(destDir);
        Path sourceFile = tempDir.resolve("unwanted.bin");
        Files.write(sourceFile, "unwanted-content".getBytes(StandardCharsets.UTF_8));

        int port = findFreePort();
        SettingsService settings = createSettings("Receiver", port, destDir, true);
        TransferService transfers = new TransferService();
        ConnectionService connection = createMtlsConnectionService(settings, transfers, true);

        CountDownLatch approvalQueued = new CountDownLatch(1);
        CountDownLatch rejectedLatch = new CountDownLatch(1);
        connection.getPendingRequests().addListener((ListChangeListener<IncomingRequestItem>) c -> {
            if (!connection.getPendingRequests().isEmpty()) {
                approvalQueued.countDown();
            }
        });
        connection.incomingSessionProperty().addListener((obs, o, n) -> {
            if (n != null && n.stage() == IncomingTransferSession.Stage.REJECTED) {
                rejectedLatch.countDown();
            }
        });

        try {
            assertTrue(connection.startReceiving(destDir));

            CountDownLatch senderDone = new CountDownLatch(1);
            AtomicReference<Throwable> senderError = new AtomicReference<>();
            Thread senderThread = new Thread(() -> {
                try (SSLSocket socket = TlsSupport.connect("127.0.0.1", port, senderContext)) {
                    socket.setSoTimeout(10_000);
                    FileSender.send(socket, sourceFile, new ConsoleTransferListener());
                } catch (Throwable t) {
                    senderError.set(t);
                } finally {
                    senderDone.countDown();
                }
            }, "test-reject-sender");
            senderThread.start();

            assertTrue(approvalQueued.await(10, TimeUnit.SECONDS));
            JavaFxToolkitExtension.runOnFxThread(() -> {
                int id = connection.getPendingRequests().get(0).requestId();
                assertTrue(connection.approveRequest(id, false));
            });

            assertTrue(senderDone.await(10, TimeUnit.SECONDS));
            assertNull(senderError.get());
            assertTrue(rejectedLatch.await(10, TimeUnit.SECONDS));
            assertFalse(Files.exists(destDir.resolve(senderCertFingerprint)));
        } finally {
            connection.shutdown();
            transfers.shutdown();
        }
    }

    @Test
    void interruptedTransferEmitsFailurePreservesResumeCheckpointAndResumesCleanly() throws Exception {
        Path destDir = tempDir.resolve("resume-dest");
        Files.createDirectories(destDir);
        Path sourceFile = tempDir.resolve("resumable-movie.dat");
        byte[] payload = new byte[260_000];
        new Random(99L).nextBytes(payload);
        Files.write(sourceFile, payload);
        String expectedSha = HashUtils.calculateSHA256(sourceFile);

        int port = findFreePort();
        SettingsService settings = createSettings("Receiver", port, destDir, false);
        TransferService transfers = new TransferService();
        ConnectionService connection = createMtlsConnectionService(settings, transfers, true);

        CountDownLatch failedLatch = new CountDownLatch(1);
        CountDownLatch completedLatch = new CountDownLatch(1);
        AtomicReference<IncomingTransferSession> completedRef = new AtomicReference<>();
        connection.incomingSessionProperty().addListener((obs, o, n) -> {
            if (n != null && n.stage() == IncomingTransferSession.Stage.FAILED) {
                failedLatch.countDown();
            } else if (n != null && n.stage() == IncomingTransferSession.Stage.COMPLETED) {
                completedRef.set(n);
                completedLatch.countDown();
            }
        });

        try {
            assertTrue(connection.startReceiving(destDir));

            // Attempt 1: Interrupt connection right after first chunk ACK (64 KB committed to ResumeStore)
            SSLSocket socket1 = TlsSupport.connect("127.0.0.1", port, senderContext);
            socket1.setSoTimeout(10_000);
            AtomicBoolean closedMidStream = new AtomicBoolean(false);
            assertThrows(IOException.class, () -> {
                try {
                    FileSender.send(socket1, sourceFile, new TransferListener() {
                        @Override
                        public void onStarted(String name, long totalBytes) {
                        }

                        @Override
                        public void onProgress(String name, long transferredBytes, long totalBytes) {
                            if (transferredBytes >= 65_536L && !closedMidStream.getAndSet(true)) {
                                try {
                                    socket1.close();
                                } catch (IOException ignored) {
                                }
                            }
                        }

                        @Override
                        public void onCompleted(String name) {
                        }

                        @Override
                        public void onError(String name, Exception error) {
                        }
                    });
                } finally {
                    try {
                        socket1.close();
                    } catch (IOException ignored) {
                    }
                }
            });

            assertTrue(failedLatch.await(10, TimeUnit.SECONDS), "Interrupted transfer must emit FAILED stage");
            assertTrue(connection.isListening(), "Listener must recover and stay active after client interruption");

            // Attempt 2: Retry from sender; verify resume starts at > 0 offset and completes accurately
            AtomicLong resumedFromOffset = new AtomicLong(-1L);
            try (SSLSocket socket2 = TlsSupport.connect("127.0.0.1", port, senderContext)) {
                socket2.setSoTimeout(15_000);
                FileSender.send(socket2, sourceFile, new TransferListener() {
                    @Override
                    public void onStarted(String name, long totalBytes) {
                    }

                    @Override
                    public void onProgress(String name, long transferredBytes, long totalBytes) {
                        if (resumedFromOffset.get() < 0L) {
                            resumedFromOffset.set(transferredBytes);
                        }
                    }

                    @Override
                    public void onCompleted(String name) {
                    }

                    @Override
                    public void onError(String name, Exception error) {
                    }
                });
            }

            assertTrue(completedLatch.await(10, TimeUnit.SECONDS), "Resumed transfer must emit COMPLETED stage");
            assertTrue(
                resumedFromOffset.get() >= 65_536L,
                "Expected sender retry to resume from >= 65536 checkpoint, was " + resumedFromOffset.get()
            );
            Path saved = completedRef.get().savedPath();
            assertNotNull(saved);
            assertEquals(payload.length, Files.size(saved));
            assertEquals(expectedSha, HashUtils.calculateSHA256(saved));
        } finally {
            connection.shutdown();
            transfers.shutdown();
        }
    }

    @Test
    void duplicateListenerPreventedAndRepeatedStartStopLifecycleCleanlyRebindsSamePort() throws Exception {
        Path destDir = tempDir.resolve("lifecycle-dest");
        Files.createDirectories(destDir);

        int port = findFreePort();
        SettingsService settings = createSettings("Receiver", port, destDir, false);
        AtomicInteger factoryInvocations = new AtomicInteger(0);

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
                factoryInvocations.incrementAndGet();
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

        try {
            // Cycle 1 with duplicate start guard
            assertTrue(connection.startReceiving(destDir));
            assertTrue(connection.startReceiving(destDir));
            assertEquals(1, factoryInvocations.get(), "Duplicate start must not create second PeerServer");
            assertTrue(connection.isListening());

            try (SSLSocket probe = TlsSupport.connect("127.0.0.1", port, senderContext)) {
                assertTrue(probe.isConnected());
            }

            connection.stopReceiving();
            assertFalse(connection.isListening());

            // Cycle 2 and Cycle 3 rebinding exact same port cleanly
            for (int cycle = 2; cycle <= 3; cycle++) {
                assertTrue(connection.startReceiving(destDir), "Restart cycle " + cycle + " must succeed");
                assertEquals(cycle, factoryInvocations.get());
                try (SSLSocket probe = TlsSupport.connect("127.0.0.1", port, senderContext)) {
                    assertTrue(probe.isConnected());
                }
                connection.stopReceiving();
                assertFalse(connection.isListening());
            }

            // Port must now be available externally with zero leak
            try (ServerSocket verifyFree = new ServerSocket(port)) {
                assertEquals(port, verifyFree.getLocalPort());
            }
        } finally {
            connection.shutdown();
            transfers.shutdown();
        }
    }

    @Test
    void portConflictFailsCleanlyWithMeaningfulMessageAndDoesNotSetListeningState() throws Exception {
        Path destDir = tempDir.resolve("conflict-dest");
        Files.createDirectories(destDir);

        int occupiedPort = findFreePort();
        SettingsService settings = createSettings("Receiver", occupiedPort, destDir, false);
        TransferService transfers = new TransferService();
        ConnectionService connection = createMtlsConnectionService(settings, transfers, true);

        try (ServerSocket conflictingSocket = new ServerSocket(occupiedPort)) {
            assertEquals(occupiedPort, conflictingSocket.getLocalPort());

            assertFalse(connection.startReceiving(destDir));
            assertFalse(connection.isListening());
            assertEquals(ConnectionState.ERROR, connection.getStatus().state());
            assertTrue(
                connection.getStatus().detail().contains("Port " + occupiedPort + " is already in use"),
                "Unexpected port conflict message: " + connection.getStatus().detail()
            );

            // Also verify missing TLS config is reported cleanly
            ConnectionService unconfiguredTls = createMtlsConnectionService(settings, transfers, false);
            try {
                assertFalse(unconfiguredTls.startReceiving(destDir));
                assertEquals(ConnectionState.TLS_UNCONFIGURED, unconfiguredTls.getStatus().state());
            } finally {
                unconfiguredTls.shutdown();
            }
        } finally {
            connection.shutdown();
            transfers.shutdown();
        }
    }

    @Test
    void applicationShutdownReleasesListeningSocketActiveConnectionsAndListenerThreads() throws Exception {
        Path destDir = tempDir.resolve("shutdown-dest");
        Files.createDirectories(destDir);

        int port = findFreePort();
        SettingsService settings = createSettings("ShutdownTest", port, destDir, true);
        DeviceInfoService deviceInfo = new DeviceInfoService(settings) {
            @Override
            public boolean isTlsConfigured() {
                return true;
            }
        };
        TransferService transfers = new TransferService();
        ConnectionService connection = createMtlsConnectionService(settings, transfers, true);
        SyncShareServices services = new SyncShareServices(settings, deviceInfo, connection, transfers);

        CountDownLatch approvalQueued = new CountDownLatch(1);
        connection.getPendingRequests().addListener((ListChangeListener<IncomingRequestItem>) c -> {
            if (!connection.getPendingRequests().isEmpty()) {
                approvalQueued.countDown();
            }
        });

        assertTrue(connection.startReceiving(destDir));
        Path sourceFile = tempDir.resolve("blocked-on-approval.bin");
        Files.writeString(sourceFile, "wait-for-approval-payload", StandardCharsets.UTF_8);

        CountDownLatch senderFinished = new CountDownLatch(1);
        Thread senderThread = new Thread(() -> {
            try (SSLSocket socket = TlsSupport.connect("127.0.0.1", port, senderContext)) {
                socket.setSoTimeout(15_000);
                FileSender.send(socket, sourceFile, new ConsoleTransferListener());
            } catch (IOException ignored) {
            } finally {
                senderFinished.countDown();
            }
        }, "test-shutdown-sender");
        senderThread.start();

        assertTrue(approvalQueued.await(10, TimeUnit.SECONDS));

        long startNanos = System.nanoTime();
        services.shutdown();
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);

        assertTrue(elapsedMs < 5_000L, "Shutdown stalled for " + elapsedMs + " ms");
        assertFalse(connection.isListening());
        assertTrue(senderFinished.await(5, TimeUnit.SECONDS), "Blocked sender connection was not released on shutdown");

        try (ServerSocket verifyFree = new ServerSocket(port)) {
            assertEquals(port, verifyFree.getLocalPort());
        }

        // Wait briefly for daemon listener/worker threads to exit and verify none remain alive
        long deadline = System.currentTimeMillis() + 3_000L;
        while (System.currentTimeMillis() < deadline && hasLiveSyncShareReceiverThreads()) {
            Thread.sleep(25L);
        }
        assertFalse(hasLiveSyncShareReceiverThreads(), "SyncShare receiver threads lingered after shutdown");
    }

    private static SettingsService createSettings(
        String alias,
        int port,
        Path receiveDir,
        boolean requireApproval
    ) throws Exception {
        try {
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
        } catch (Throwable error) {
            if (error instanceof Exception ex) {
                throw ex;
            }
            throw new RuntimeException(error);
        }
    }

    private static ConnectionService createMtlsConnectionService(
        SettingsService settings,
        TransferService transfers,
        boolean tlsConfigured
    ) {
        DeviceInfoService deviceInfo = new DeviceInfoService(settings) {
            @Override
            public boolean isTlsConfigured() {
                return tlsConfigured;
            }
        };
        return new ConnectionService(
            settings,
            deviceInfo,
            transfers,
            (port, approvalHandler, receiveDir, observer) -> {
                PeerServer server = new PeerServer(
                    port,
                    approvalHandler,
                    new ConsoleTransferListener(),
                    receiveDir,
                    observer,
                    receiverContext
                );
                server.start();
                return server;
            }
        );
    }

    private static boolean hasLiveSyncShareReceiverThreads() {
        Set<Thread> threads = Thread.getAllStackTraces().keySet();
        for (Thread t : threads) {
            if (t.isAlive() && (t.getName().equals("syncshare-listener")
                || t.getName().startsWith("syncshare-receiver-worker-"))) {
                return true;
            }
        }
        return false;
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

    @AfterAll
    static void cleanupMtlsMaterials() throws IOException {
        if (sharedCertDir != null && Files.exists(sharedCertDir)) {
            try (var paths = Files.walk(sharedCertDir)) {
                for (Path p : paths.sorted(Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(p);
                }
            }
        }
    }

    private static byte[] readPemBody(Path pemFile) throws IOException {
        String text = Files.readString(pemFile, StandardCharsets.UTF_8);
        String base64 = text.lines()
            .filter(line -> !line.startsWith("-----"))
            .reduce("", String::concat);
        return java.util.Base64.getDecoder().decode(base64);
    }
}
