package com.syncshare.service;

import com.syncshare.approval.ApprovalHandler;
import com.syncshare.events.ConsoleTransferListener;
import com.syncshare.events.IncomingTransferObserver;
import com.syncshare.events.TransferSessionListener;
import com.syncshare.model.AppSettings;
import com.syncshare.model.ConnectionState;
import com.syncshare.model.ConnectionStatus;
import com.syncshare.model.IncomingRequestItem;
import com.syncshare.model.IncomingTransferSession;
import com.syncshare.model.TransferDirection;
import com.syncshare.model.TransferItem;
import com.syncshare.model.TransferStatus;
import com.syncshare.network.PeerServer;
import com.syncshare.reliability.CentralErrorHandler;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;

import java.io.IOException;
import java.net.BindException;
import java.net.InetAddress;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Coordinates SyncShare peer listener readiness, connection indicator state,
 * incoming transfer lifecycle events, monitoring integration, and approval decisions
 * without exposing socket APIs to UI controllers.
 */
public class ConnectionService implements IncomingTransferGateway {

    @FunctionalInterface
    public interface ServerStarter {
        AutoCloseable start(int port, ApprovalHandler approvalHandler) throws IOException;
    }

    @FunctionalInterface
    public interface ReceiverServerFactory {
        AutoCloseable start(
            int port,
            ApprovalHandler approvalHandler,
            Path receiveDirectory,
            IncomingTransferObserver observer
        ) throws IOException;
    }

    private static final class TrackedIncomingTransfer {
        volatile String fileName = "unknown";
        volatile long totalBytes;
        volatile long transferredBytes;
        volatile String senderAddress = "Unknown sender";
        volatile String peerIdentity = "";
        volatile boolean emittedNonZeroProgress;
        volatile long lastUiProgressNanos;
    }

    private static final int APPROVAL_TIMEOUT_SECONDS = 120;
    private static final long PROGRESS_THROTTLE_NANOS = 40_000_000L;

    private final SettingsService settingsService;
    private final DeviceInfoService deviceInfoService;
    private final TransferService transferService;
    private final TransferMonitoringService monitoringService;
    private final ServerStarter legacyServerStarter;
    private final ReceiverServerFactory receiverServerFactory;
    private final boolean usingLegacyStarter;
    private final boolean usingCustomReceiverFactory;

    private final ReadOnlyObjectWrapper<ConnectionStatus> status =
        new ReadOnlyObjectWrapper<>(this, "status");
    private final ReadOnlyObjectWrapper<IncomingTransferSession> incomingSession =
        new ReadOnlyObjectWrapper<>(this, "incomingSession", IncomingTransferSession.idle());
    private final ObservableList<IncomingRequestItem> pendingRequests =
        FXCollections.observableArrayList();
    private final ObservableList<IncomingRequestItem> unmodifiablePendingRequests =
        FXCollections.unmodifiableObservableList(pendingRequests);
    private final java.util.List<ConnectionStatusListener> statusListeners =
        new java.util.concurrent.CopyOnWriteArrayList<>();

    private final AtomicInteger nextRequestId = new AtomicInteger(1);
    private final ConcurrentHashMap<Integer, CompletableFuture<Boolean>> pendingDecisions =
        new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, TrackedIncomingTransfer> activeSessions =
        new ConcurrentHashMap<>();

    private volatile ConnectionStatus latestStatus;
    private volatile IncomingTransferSession latestIncomingSession = IncomingTransferSession.idle();
    private AutoCloseable activeServer;
    private volatile boolean listening;
    private volatile int boundPort = -1;

    public ConnectionService(SettingsService settingsService, DeviceInfoService deviceInfoService) {
        this(settingsService, deviceInfoService, (TransferService) null, (TransferMonitoringService) null);
    }

    public ConnectionService(
        SettingsService settingsService,
        DeviceInfoService deviceInfoService,
        TransferMonitoringService monitoringService
    ) {
        this(settingsService, deviceInfoService, (TransferService) null, monitoringService);
    }

    public ConnectionService(
        SettingsService settingsService,
        DeviceInfoService deviceInfoService,
        TransferService transferService
    ) {
        this(
            settingsService,
            deviceInfoService,
            transferService,
            transferService != null ? transferService.getMonitoringService() : null
        );
    }

    public ConnectionService(
        SettingsService settingsService,
        DeviceInfoService deviceInfoService,
        TransferService transferService,
        TransferMonitoringService monitoringService
    ) {
        this.settingsService = Objects.requireNonNull(settingsService, "settingsService");
        this.deviceInfoService = Objects.requireNonNull(deviceInfoService, "deviceInfoService");
        this.transferService = transferService;
        this.monitoringService = monitoringService != null
            ? monitoringService
            : (transferService != null ? transferService.getMonitoringService() : null);
        this.legacyServerStarter = null;
        this.receiverServerFactory = this::startManagedPeerServer;
        this.usingLegacyStarter = false;
        this.usingCustomReceiverFactory = false;

        initializeServiceListeners();
    }

    public ConnectionService(
        SettingsService settingsService,
        DeviceInfoService deviceInfoService,
        ServerStarter serverStarter
    ) {
        this.settingsService = Objects.requireNonNull(settingsService, "settingsService");
        this.deviceInfoService = Objects.requireNonNull(deviceInfoService, "deviceInfoService");
        this.transferService = null;
        this.monitoringService = null;
        this.legacyServerStarter = Objects.requireNonNull(serverStarter, "serverStarter");
        this.receiverServerFactory = (port, handler, dir, observer) -> this.legacyServerStarter.start(port, handler);
        this.usingLegacyStarter = true;
        this.usingCustomReceiverFactory = false;

        initializeServiceListeners();
    }

    public ConnectionService(
        SettingsService settingsService,
        DeviceInfoService deviceInfoService,
        TransferService transferService,
        ReceiverServerFactory receiverServerFactory
    ) {
        this(
            settingsService,
            deviceInfoService,
            transferService,
            transferService != null ? transferService.getMonitoringService() : null,
            receiverServerFactory
        );
    }

    public ConnectionService(
        SettingsService settingsService,
        DeviceInfoService deviceInfoService,
        TransferService transferService,
        TransferMonitoringService monitoringService,
        ReceiverServerFactory receiverServerFactory
    ) {
        this.settingsService = Objects.requireNonNull(settingsService, "settingsService");
        this.deviceInfoService = Objects.requireNonNull(deviceInfoService, "deviceInfoService");
        this.transferService = transferService;
        this.monitoringService = monitoringService != null
            ? monitoringService
            : (transferService != null ? transferService.getMonitoringService() : null);
        this.legacyServerStarter = null;
        this.receiverServerFactory = Objects.requireNonNull(receiverServerFactory, "receiverServerFactory");
        this.usingLegacyStarter = false;
        this.usingCustomReceiverFactory = true;

        initializeServiceListeners();
    }

    private void initializeServiceListeners() {
        this.settingsService.settingsProperty().addListener((obs, oldVal, newVal) -> {
            if (listening) {
                return;
            }
            boolean portChanged = oldVal == null || newVal == null || oldVal.listeningPort() != newVal.listeningPort();
            ConnectionStatus current = latestStatus;
            if (portChanged || current == null || current.state() != ConnectionState.ERROR) {
                refreshStatus();
            }
        });

        refreshStatus();
        if (this.settingsService.getSettings().autoStartReceiver() && this.deviceInfoService.isTlsConfigured()) {
            startReceiver();
        }
    }

    private PeerServer startManagedPeerServer(
        int port,
        ApprovalHandler handler,
        Path receiveDir,
        IncomingTransferObserver observer
    ) throws IOException {
        AtomicReference<PeerServer> serverRef = new AtomicReference<>();
        TransferSessionListener sessionListener = createSessionListener(serverRef);
        PeerServer server = new PeerServer(
            port,
            handler,
            new ConsoleTransferListener(),
            receiveDir,
            observer,
            null,
            sessionListener
        );
        serverRef.set(server);
        server.start();
        return server;
    }

    private TransferSessionListener createSessionListener(AtomicReference<PeerServer> serverRef) {
        if (monitoringService == null) {
            return TransferSessionListener.NO_OP;
        }
        return new TransferSessionListener() {
            @Override
            public void onConnecting(
                String transferId,
                TransferDirection direction,
                String fileName,
                long totalBytes,
                String peerAddress,
                String statusNote
            ) {
                monitoringService.onConnecting(transferId, direction, fileName, totalBytes, peerAddress, statusNote);
                monitoringService.registerCancelHandler(transferId, () -> {
                    PeerServer peerServer = serverRef.get();
                    if (peerServer != null) {
                        peerServer.cancelTransfer(transferId);
                    }
                });
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
                monitoringService.onStarted(transferId, direction, fileName, totalBytes, initialBytes, peerAddress);
            }

            @Override
            public void onProgress(
                String transferId,
                TransferDirection direction,
                String fileName,
                long transferredBytes,
                long totalBytes
            ) {
                monitoringService.onProgress(transferId, direction, fileName, transferredBytes, totalBytes);
            }

            @Override
            public void onCompleted(String transferId, TransferDirection direction, String fileName) {
                monitoringService.onCompleted(transferId, direction, fileName);
            }

            @Override
            public void onFailed(
                String transferId,
                TransferDirection direction,
                String fileName,
                String reason,
                Exception error
            ) {
                monitoringService.onFailed(transferId, direction, fileName, reason, error);
            }

            @Override
            public void onCancelled(String transferId, TransferDirection direction, String fileName, String reason) {
                monitoringService.onCancelled(transferId, direction, fileName, reason);
            }
        };
    }

    @Override
    public ConnectionStatus getStatus() {
        ConnectionStatus current = latestStatus;
        return current != null ? current : status.get();
    }

    @Override
    public ReadOnlyObjectProperty<ConnectionStatus> statusProperty() {
        return status.getReadOnlyProperty();
    }

    @Override
    public IncomingTransferSession getIncomingSession() {
        IncomingTransferSession current = latestIncomingSession;
        return current != null ? current : incomingSession.get();
    }

    @Override
    public ReadOnlyObjectProperty<IncomingTransferSession> incomingSessionProperty() {
        return incomingSession.getReadOnlyProperty();
    }

    @Override
    public ObservableList<IncomingRequestItem> getPendingRequests() {
        return unmodifiablePendingRequests;
    }

    @Override
    public boolean isListening() {
        return listening;
    }

    @Override
    public int getBoundPort() {
        return boundPort;
    }

    @Override
    public ReceiveDestinationValidator.ValidationResult validateDestination(String directoryPath) {
        return ReceiveDestinationValidator.validateDirectory(directoryPath);
    }

    @Override
    public ReceiveDestinationValidator.ValidationResult createAndValidateDestination(String directoryPath) {
        ReceiveDestinationValidator.ValidationResult result =
            ReceiveDestinationValidator.createAndValidateDirectory(directoryPath);
        if (result.valid() && result.resolvedDirectory() != null) {
            syncReceiveDirectorySetting(result.resolvedDirectory());
        }
        return result;
    }

    public synchronized boolean startReceiver() {
        if (usingLegacyStarter) {
            int port = settingsService.getSettings().listeningPort();
            boolean tlsConfigured = deviceInfoService.isTlsConfigured();
            if (isServerActive()) {
                updateStatus(ConnectionStatus.listening(resolveDisplayPort(port), pendingRequests.size()));
                return true;
            }
            if (!tlsConfigured) {
                updateStatus(ConnectionStatus.tlsSetupRequired(port));
                return false;
            }
            try {
                activeServer = legacyServerStarter.start(port, this::handleIncomingApproval);
                listening = true;
                boundPort = resolveBoundPort(activeServer, port);
                updateStatus(ConnectionStatus.listening(boundPort, pendingRequests.size()));
                return true;
            } catch (IOException | RuntimeException error) {
                listening = false;
                activeServer = null;
                boundPort = -1;
                updateStatus(ConnectionStatus.error(port, describeStartupError(port, error)));
                CentralErrorHandler.getInstance().handleError(error, "port " + port);
                return false;
            }
        }

        String configuredDir = settingsService.getSettings().receiveDirectory();
        ReceiveDestinationValidator.ValidationResult dirCheck =
            ReceiveDestinationValidator.validateDirectory(configuredDir);
        if (!dirCheck.valid()
            && dirCheck.missingDirectory()
            && AppSettings.DEFAULT_RECEIVE_DIR.equals(configuredDir)) {
            dirCheck = ReceiveDestinationValidator.createAndValidateDirectory(configuredDir);
        }
        if (!dirCheck.valid() || dirCheck.resolvedDirectory() == null) {
            int port = settingsService.getSettings().listeningPort();
            updateStatus(ConnectionStatus.error(port, dirCheck.message()));
            return false;
        }
        return startReceiving(dirCheck.resolvedDirectory());
    }

    @Override
    public synchronized boolean startReceiving(Path validatedDestination) {
        int port = settingsService.getSettings().listeningPort();
        if (isServerActive()) {
            updateStatus(ConnectionStatus.listening(resolveDisplayPort(port), pendingRequests.size()));
            return true;
        }
        // Clean up stale closed reference if server died unexpectedly
        if (activeServer != null) {
            closeQuietly(activeServer);
            activeServer = null;
            listening = false;
            boundPort = -1;
        }

        ReceiveDestinationValidator.ValidationResult dirCheck =
            ReceiveDestinationValidator.validateDirectory(validatedDestination);
        if (!dirCheck.valid() || dirCheck.resolvedDirectory() == null) {
            updateStatus(ConnectionStatus.error(port, dirCheck.message()));
            return false;
        }

        boolean tlsConfigured = deviceInfoService.isTlsConfigured();
        if (!tlsConfigured) {
            updateStatus(ConnectionStatus.tlsSetupRequired(port));
            return false;
        }

        Path resolvedDir = dirCheck.resolvedDirectory();

        try {
            activeServer = receiverServerFactory.start(
                port,
                this::handleIncomingApproval,
                resolvedDir,
                createIncomingObserver()
            );
            listening = true;
            boundPort = resolveBoundPort(activeServer, port);
            syncReceiveDirectorySetting(resolvedDir);
            IncomingTransferSession currentSession = getIncomingSession();
            if (currentSession == null
                || currentSession.stage() == IncomingTransferSession.Stage.COMPLETED
                || currentSession.stage() == IncomingTransferSession.Stage.FAILED
                || currentSession.stage() == IncomingTransferSession.Stage.REJECTED) {
                updateIncomingSession(IncomingTransferSession.idle());
            }
            updateStatus(ConnectionStatus.listening(boundPort, pendingRequests.size()));
            return true;
        } catch (IOException | RuntimeException error) {
            listening = false;
            activeServer = null;
            boundPort = -1;
            updateStatus(ConnectionStatus.error(port, describeStartupError(port, error)));
            CentralErrorHandler.getInstance().handleError(error, "port " + port);
            return false;
        }
    }

    @Override
    public synchronized void stopReceiving() {
        stopReceiver();
    }

    public synchronized void stopReceiver() {
        listening = false;
        boundPort = -1;
        if (activeServer != null) {
            try {
                activeServer.close();
            } catch (Exception ignored) {
            } finally {
                activeServer = null;
            }
        }
        rejectAllPending();
        IncomingTransferSession current = getIncomingSession();
        if (current != null
            && (current.stage() == IncomingTransferSession.Stage.CONNECTED
                || current.stage() == IncomingTransferSession.Stage.AWAITING_APPROVAL)) {
            updateIncomingSession(IncomingTransferSession.idle());
        }
        refreshStatus();
    }

    public void refreshStatus() {
        int configuredPort = settingsService.getSettings().listeningPort();
        int port = boundPort > 0 ? boundPort : configuredPort;
        boolean tlsConfigured = deviceInfoService.isTlsConfigured();
        if (isServerActive()) {
            updateStatus(ConnectionStatus.listening(port, pendingRequests.size()));
        } else {
            updateStatus(ConnectionStatus.standby(configuredPort, tlsConfigured));
        }
    }

    @Override
    public boolean approveRequest(int requestId, boolean accepted) {
        CompletableFuture<Boolean> future = pendingDecisions.remove(requestId);
        if (future == null) {
            return false;
        }
        boolean completed = future.complete(accepted);
        runOnFxThread(() -> pendingRequests.removeIf(item -> item.requestId() == requestId));
        return completed;
    }

    public void submitPendingRequestForPreview(String fileName, long fileSize, String senderAddress) {
        int id = nextRequestId.getAndIncrement();
        IncomingRequestItem item = new IncomingRequestItem(id, fileName, fileSize, senderAddress, Instant.now());
        pendingDecisions.put(id, new CompletableFuture<>());
        runOnFxThread(() -> pendingRequests.add(item));
    }

    public void rejectAllPending() {
        pendingDecisions.forEach((id, future) -> future.complete(false));
        pendingDecisions.clear();
        runOnFxThread(pendingRequests::clear);
    }

    @Override
    public void clearSessionNotice() {
        updateIncomingSession(IncomingTransferSession.idle());
    }

    private void recordIncomingActiveItem(TransferItem item) {
        if (transferService == null) {
            return;
        }
        if (usingCustomReceiverFactory) {
            transferService.upsertActiveTransfer(item);
        } else {
            transferService.upsertActiveTransferLocal(item);
        }
    }

    private void recordIncomingTerminalItem(TransferItem item) {
        if (transferService == null) {
            return;
        }
        if (usingCustomReceiverFactory) {
            transferService.moveToHistory(item);
        } else {
            transferService.moveToHistoryLocal(item);
        }
    }

    private IncomingTransferObserver createIncomingObserver() {
        return new IncomingTransferObserver() {
            @Override
            public void onConnectionOpened(String transferId, String senderAddress, String peerId) {
                TrackedIncomingTransfer tracked = activeSessions.computeIfAbsent(
                    transferId,
                    key -> new TrackedIncomingTransfer()
                );
                tracked.senderAddress = senderAddress != null ? senderAddress : "Unknown sender";
                tracked.peerIdentity = peerId != null ? peerId : "";

                updateIncomingSession(new IncomingTransferSession(
                    transferId,
                    IncomingTransferSession.Stage.CONNECTED,
                    "Negotiating offer...",
                    0L,
                    0L,
                    tracked.senderAddress,
                    tracked.peerIdentity,
                    null,
                    "Incoming Connection",
                    "Authenticated mTLS 1.3 peer: " + tracked.senderAddress,
                    Instant.now()
                ));

                if (listening) {
                    int port = resolveDisplayPort(settingsService.getSettings().listeningPort());
                    updateStatus(new ConnectionStatus(
                        ConnectionState.CONNECTED,
                        "Incoming Connection",
                        "Authenticated peer " + tracked.senderAddress,
                        port,
                        true,
                        Math.max(1, pendingRequests.size())
                    ));
                }
            }

            @Override
            public void onFileOffered(
                String transferId,
                String fileName,
                long fileSize,
                String senderAddress,
                String peerId
            ) {
                TrackedIncomingTransfer tracked = activeSessions.computeIfAbsent(
                    transferId,
                    key -> new TrackedIncomingTransfer()
                );
                tracked.fileName = fileName;
                tracked.totalBytes = Math.max(0L, fileSize);
                if (senderAddress != null && !senderAddress.isBlank()) {
                    tracked.senderAddress = senderAddress;
                }
                if (peerId != null && !peerId.isBlank()) {
                    tracked.peerIdentity = peerId;
                }

                boolean manualApproval = settingsService.getSettings().requireApproval();
                IncomingTransferSession.Stage nextStage = manualApproval
                    ? IncomingTransferSession.Stage.AWAITING_APPROVAL
                    : IncomingTransferSession.Stage.CONNECTED;

                updateIncomingSession(new IncomingTransferSession(
                    transferId,
                    nextStage,
                    fileName,
                    fileSize,
                    0L,
                    tracked.senderAddress,
                    tracked.peerIdentity,
                    null,
                    manualApproval ? "Awaiting Approval" : "File Offer Received",
                    manualApproval
                        ? "Approve or reject " + fileName + " (" + TransferItem.formatByteCount(fileSize) + ")"
                        : "Preparing destination and resume checkpoint...",
                    Instant.now()
                ));

                recordIncomingActiveItem(new TransferItem(
                    transferId,
                    fileName,
                    fileSize,
                    0L,
                    TransferDirection.INCOMING,
                    manualApproval ? TransferStatus.AWAITING_APPROVAL : TransferStatus.QUEUED,
                    tracked.senderAddress,
                    "Pending SHA-256",
                    Instant.now(),
                    manualApproval ? "Awaiting local approval" : "Offer accepted automatically"
                ));
            }

            @Override
            public void onApprovalDecision(String transferId, String fileName, boolean accepted) {
                TrackedIncomingTransfer tracked = activeSessions.get(transferId);
                String sender = tracked != null ? tracked.senderAddress : "Unknown sender";
                String peerId = tracked != null ? tracked.peerIdentity : "";
                long total = tracked != null ? tracked.totalBytes : 0L;

                if (!accepted) {
                    activeSessions.remove(transferId);
                    updateIncomingSession(new IncomingTransferSession(
                        transferId,
                        IncomingTransferSession.Stage.REJECTED,
                        fileName,
                        total,
                        0L,
                        sender,
                        peerId,
                        null,
                        "Incoming Offer Rejected",
                        "Declined " + fileName + " from " + sender,
                        Instant.now()
                    ));
                    recordIncomingTerminalItem(new TransferItem(
                        transferId,
                        fileName,
                        total,
                        0L,
                        TransferDirection.INCOMING,
                        TransferStatus.REJECTED,
                        sender,
                        "Rejected",
                        Instant.now(),
                        "Rejected by receiver"
                    ));
                    refreshStatus();
                }
            }

            @Override
            public void onStarted(
                String transferId,
                String fileName,
                long totalBytes,
                String senderAddress,
                String peerId
            ) {
                TrackedIncomingTransfer tracked = activeSessions.computeIfAbsent(
                    transferId,
                    key -> new TrackedIncomingTransfer()
                );
                tracked.fileName = fileName;
                tracked.totalBytes = Math.max(0L, totalBytes);
                if (senderAddress != null && !senderAddress.isBlank()) {
                    tracked.senderAddress = senderAddress;
                }
                if (peerId != null && !peerId.isBlank()) {
                    tracked.peerIdentity = peerId;
                }

                updateIncomingSession(new IncomingTransferSession(
                    transferId,
                    IncomingTransferSession.Stage.RECEIVING,
                    fileName,
                    totalBytes,
                    0L,
                    tracked.senderAddress,
                    tracked.peerIdentity,
                    null,
                    "Receiving File",
                    "Streaming encrypted chunks from " + tracked.senderAddress,
                    Instant.now()
                ));

                recordIncomingActiveItem(new TransferItem(
                    transferId,
                    fileName,
                    totalBytes,
                    0L,
                    TransferDirection.INCOMING,
                    TransferStatus.TRANSFERRING,
                    tracked.senderAddress,
                    "SHA-256 active",
                    Instant.now(),
                    "Receiving encrypted chunks"
                ));

                if (listening) {
                    int port = resolveDisplayPort(settingsService.getSettings().listeningPort());
                    updateStatus(new ConnectionStatus(
                        ConnectionState.CONNECTED,
                        "Receiving • " + fileName,
                        "Receiving from " + tracked.senderAddress,
                        port,
                        true,
                        1
                    ));
                }
            }

            @Override
            public void onProgress(String transferId, String fileName, long transferredBytes, long totalBytes) {
                TrackedIncomingTransfer tracked = activeSessions.computeIfAbsent(
                    transferId,
                    key -> new TrackedIncomingTransfer()
                );
                tracked.fileName = fileName;
                tracked.totalBytes = Math.max(0L, totalBytes);
                tracked.transferredBytes = Math.max(0L, transferredBytes);

                long now = System.nanoTime();
                boolean isBoundary = transferredBytes == 0L || (totalBytes > 0L && transferredBytes >= totalBytes);
                boolean firstNonZeroChunk = transferredBytes > 0L && !tracked.emittedNonZeroProgress;
                if (!isBoundary && !firstNonZeroChunk && (now - tracked.lastUiProgressNanos) < PROGRESS_THROTTLE_NANOS) {
                    return;
                }
                if (transferredBytes > 0L) {
                    tracked.emittedNonZeroProgress = true;
                    tracked.lastUiProgressNanos = now;
                }

                updateIncomingSession(new IncomingTransferSession(
                    transferId,
                    IncomingTransferSession.Stage.RECEIVING,
                    fileName,
                    totalBytes,
                    transferredBytes,
                    tracked.senderAddress,
                    tracked.peerIdentity,
                    null,
                    "Receiving File",
                    "Verified chunk checkpoint at " + TransferItem.formatByteCount(transferredBytes),
                    Instant.now()
                ));

                recordIncomingActiveItem(new TransferItem(
                    transferId,
                    fileName,
                    totalBytes,
                    transferredBytes,
                    TransferDirection.INCOMING,
                    TransferStatus.TRANSFERRING,
                    tracked.senderAddress,
                    "SHA-256 active",
                    Instant.now(),
                    "Receiving encrypted chunks"
                ));
            }

            @Override
            public void onCompleted(String transferId, String fileName, Path savedDestination) {
                TrackedIncomingTransfer tracked = activeSessions.remove(transferId);
                long total = tracked != null ? tracked.totalBytes : 0L;
                String sender = tracked != null ? tracked.senderAddress : "Unknown sender";
                String peerId = tracked != null ? tracked.peerIdentity : "";
                String locationText = savedDestination != null
                    ? savedDestination.toAbsolutePath().toString()
                    : "receive directory";

                updateIncomingSession(new IncomingTransferSession(
                    transferId,
                    IncomingTransferSession.Stage.COMPLETED,
                    fileName,
                    total,
                    total,
                    sender,
                    peerId,
                    savedDestination,
                    "File Received & Verified",
                    "SHA-256 verified • Saved to " + locationText,
                    Instant.now()
                ));

                recordIncomingTerminalItem(new TransferItem(
                    transferId,
                    fileName,
                    total,
                    total,
                    TransferDirection.INCOMING,
                    TransferStatus.COMPLETED,
                    sender,
                    "SHA-256 verified",
                    Instant.now(),
                    "Saved to " + locationText
                ));
                refreshStatus();
            }

            @Override
            public void onError(String transferId, String fileName, String senderAddress, Exception error) {
                TrackedIncomingTransfer tracked = activeSessions.remove(transferId);
                long total = tracked != null ? tracked.totalBytes : 0L;
                long done = tracked != null ? tracked.transferredBytes : 0L;
                String sender = (senderAddress != null && !senderAddress.isBlank())
                    ? senderAddress
                    : (tracked != null ? tracked.senderAddress : "Unknown sender");
                String peerId = tracked != null ? tracked.peerIdentity : "";
                String resolvedName = (fileName != null && !fileName.isBlank())
                    ? fileName
                    : (tracked != null ? tracked.fileName : "unknown");
                String detail = error != null && error.getMessage() != null && !error.getMessage().isBlank()
                    ? error.getMessage()
                    : "Incoming transfer interrupted";

                updateIncomingSession(new IncomingTransferSession(
                    transferId,
                    IncomingTransferSession.Stage.FAILED,
                    resolvedName,
                    total,
                    done,
                    sender,
                    peerId,
                    null,
                    "Incoming Transfer Failed",
                    detail,
                    Instant.now()
                ));

                recordIncomingTerminalItem(new TransferItem(
                    transferId,
                    resolvedName,
                    total,
                    done,
                    TransferDirection.INCOMING,
                    TransferStatus.FAILED,
                    sender,
                    "Interrupted",
                    Instant.now(),
                    detail
                ));
                refreshStatus();
            }
        };
    }

    private boolean handleIncomingApproval(String fileName, long fileSize, InetAddress sender) throws IOException {
        if (!settingsService.getSettings().requireApproval()) {
            return true;
        }
        int id = nextRequestId.getAndIncrement();
        String senderText = sender != null ? sender.getHostAddress() : "unknown";
        IncomingRequestItem item = new IncomingRequestItem(id, fileName, fileSize, senderText, Instant.now());
        CompletableFuture<Boolean> decision = new CompletableFuture<>();
        pendingDecisions.put(id, decision);

        runOnFxThread(() -> {
            pendingRequests.add(item);
            if (listening) {
                int port = resolveDisplayPort(settingsService.getSettings().listeningPort());
                updateStatus(new ConnectionStatus(
                    ConnectionState.CONNECTED,
                    "Incoming Offer • #" + id,
                    "Awaiting approval for " + fileName + " from " + senderText,
                    port,
                    true,
                    pendingRequests.size()
                ));
            }
        });

        try {
            return decision.get(APPROVAL_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Approval interrupted", e);
        } catch (ExecutionException e) {
            throw new IOException("Approval failed", e);
        } finally {
            pendingDecisions.remove(id, decision);
            runOnFxThread(() -> {
                pendingRequests.removeIf(req -> req.requestId() == id);
                refreshStatus();
            });
        }
    }

    public void shutdown() {
        stopReceiver();
        activeSessions.clear();
    }

    private boolean isServerActive() {
        if (!listening || activeServer == null) {
            return false;
        }
        if (activeServer instanceof PeerServer peerServer) {
            return peerServer.isRunning();
        }
        return true;
    }

    private int resolveDisplayPort(int fallback) {
        return boundPort > 0 ? boundPort : fallback;
    }

    private static int resolveBoundPort(AutoCloseable server, int fallback) {
        if (server instanceof PeerServer peerServer) {
            return peerServer.getLocalPort();
        }
        return fallback;
    }

    private void syncReceiveDirectorySetting(Path resolvedDir) {
        String normalizedPath = resolvedDir.toAbsolutePath().normalize().toString();
        AppSettings current = settingsService.getSettings();
        if (!normalizedPath.equals(current.receiveDirectory())) {
            settingsService.updateSettings(current.withReceiveDirectory(normalizedPath));
        } else {
            System.setProperty("syncshare.receiveDir", normalizedPath);
        }
    }

    private static String describeStartupError(int port, Throwable error) {
        if (error instanceof BindException) {
            return "Port " + port + " is already in use. Stop any conflicting listener or choose another port.";
        }
        String message = error.getMessage();
        return (message != null && !message.isBlank())
            ? message
            : "Unable to start receiver on port " + port + ".";
    }

    private static void closeQuietly(AutoCloseable resource) {
        if (resource != null) {
            try {
                resource.close();
            } catch (Exception ignored) {
            }
        }
    }

    public void addStatusListener(ConnectionStatusListener listener) {
        if (listener != null) {
            statusListeners.add(listener);
            ConnectionStatus current = latestStatus;
            if (current != null) {
                listener.onConnectionStatusChanged(current);
            }
        }
    }

    public void removeStatusListener(ConnectionStatusListener listener) {
        if (listener != null) {
            statusListeners.remove(listener);
        }
    }

    private void updateStatus(ConnectionStatus next) {
        this.latestStatus = next;
        runOnFxThread(() -> {
            status.set(next);
            for (ConnectionStatusListener listener : statusListeners) {
                try {
                    listener.onConnectionStatusChanged(next);
                } catch (Exception ignored) {
                }
            }
        });
    }

    private void updateIncomingSession(IncomingTransferSession next) {
        this.latestIncomingSession = next;
        runOnFxThread(() -> incomingSession.set(next));
    }

    private static void runOnFxThread(Runnable task) {
        if (Platform.isFxApplicationThread()) {
            task.run();
        } else {
            try {
                Platform.runLater(task);
            } catch (IllegalStateException toolkitNotStarted) {
                task.run();
            }
        }
    }
}
