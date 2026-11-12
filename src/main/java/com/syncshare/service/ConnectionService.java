package com.syncshare.service;

import com.syncshare.approval.ApprovalHandler;
import com.syncshare.events.TransferSessionListener;
import com.syncshare.model.ConnectionState;
import com.syncshare.model.ConnectionStatus;
import com.syncshare.model.IncomingRequestItem;
import com.syncshare.model.TransferDirection;
import com.syncshare.network.PeerServer;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;

import java.io.IOException;
import java.net.InetAddress;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Coordinates SyncShare peer listener readiness, connection indicator state,
 * and incoming transfer approval decisions without exposing socket APIs to UI controllers.
 */
public class ConnectionService {

    @FunctionalInterface
    public interface ServerStarter {
        AutoCloseable start(int port, ApprovalHandler approvalHandler) throws IOException;
    }

    private static final int APPROVAL_TIMEOUT_SECONDS = 120;

    private final SettingsService settingsService;
    private final DeviceInfoService deviceInfoService;
    private final ServerStarter serverStarter;
    private final TransferMonitoringService monitoringService;

    private final ReadOnlyObjectWrapper<ConnectionStatus> status =
        new ReadOnlyObjectWrapper<>(this, "status");
    private final ObservableList<IncomingRequestItem> pendingRequests =
        FXCollections.observableArrayList();
    private final ObservableList<IncomingRequestItem> unmodifiablePendingRequests =
        FXCollections.unmodifiableObservableList(pendingRequests);

    private final AtomicInteger nextRequestId = new AtomicInteger(1);
    private final ConcurrentHashMap<Integer, CompletableFuture<Boolean>> pendingDecisions =
        new ConcurrentHashMap<>();

    private AutoCloseable activeServer;
    private volatile boolean listening;

    public ConnectionService(SettingsService settingsService, DeviceInfoService deviceInfoService) {
        this(settingsService, deviceInfoService, (TransferMonitoringService) null);
    }

    public ConnectionService(
        SettingsService settingsService,
        DeviceInfoService deviceInfoService,
        TransferMonitoringService monitoringService
    ) {
        this.settingsService = Objects.requireNonNull(settingsService, "settingsService");
        this.deviceInfoService = Objects.requireNonNull(deviceInfoService, "deviceInfoService");
        this.monitoringService = monitoringService;
        this.serverStarter = (port, handler) -> startManagedPeerServer(port, handler);
        initCommon();
    }

    public ConnectionService(
        SettingsService settingsService,
        DeviceInfoService deviceInfoService,
        ServerStarter serverStarter
    ) {
        this.settingsService = Objects.requireNonNull(settingsService, "settingsService");
        this.deviceInfoService = Objects.requireNonNull(deviceInfoService, "deviceInfoService");
        this.monitoringService = null;
        this.serverStarter = Objects.requireNonNull(serverStarter, "serverStarter");
        initCommon();
    }

    private void initCommon() {
        this.settingsService.settingsProperty().addListener((obs, oldVal, newVal) -> {
            if (!listening) {
                refreshStatus();
            }
        });

        refreshStatus();
        if (this.settingsService.getSettings().autoStartReceiver() && this.deviceInfoService.isTlsConfigured()) {
            startReceiver();
        }
    }

    private PeerServer startManagedPeerServer(int port, ApprovalHandler handler) throws IOException {
        TransferSessionListener sessionListener = monitoringService != null
            ? new TransferSessionListener() {
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
                    AutoCloseable current = activeServer;
                    if (current instanceof PeerServer peerServer) {
                        monitoringService.registerCancelHandler(transferId, () -> peerServer.cancelTransfer(transferId));
                    }
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
            }
            : TransferSessionListener.NO_OP;

        PeerServer server = new PeerServer(port, handler, sessionListener);
        server.start();
        return server;
    }

    public ConnectionStatus getStatus() {
        return status.get();
    }

    public ReadOnlyObjectProperty<ConnectionStatus> statusProperty() {
        return status.getReadOnlyProperty();
    }

    public ObservableList<IncomingRequestItem> getPendingRequests() {
        return unmodifiablePendingRequests;
    }

    public boolean isListening() {
        return listening;
    }

    public synchronized boolean startReceiver() {
        int port = settingsService.getSettings().listeningPort();
        boolean tlsConfigured = deviceInfoService.isTlsConfigured();
        if (listening && activeServer != null) {
            updateStatus(ConnectionStatus.listening(port, pendingRequests.size()));
            return true;
        }
        if (!tlsConfigured) {
            updateStatus(ConnectionStatus.tlsSetupRequired(port));
            return false;
        }

        try {
            activeServer = serverStarter.start(port, this::handleIncomingApproval);
            listening = true;
            updateStatus(ConnectionStatus.listening(port, pendingRequests.size()));
            return true;
        } catch (IOException | RuntimeException error) {
            listening = false;
            activeServer = null;
            updateStatus(ConnectionStatus.error(port, error.getMessage()));
            return false;
        }
    }

    public synchronized void stopReceiver() {
        listening = false;
        if (activeServer != null) {
            try {
                activeServer.close();
            } catch (Exception ignored) {
            } finally {
                activeServer = null;
            }
        }
        rejectAllPending();
        refreshStatus();
    }

    public void refreshStatus() {
        int port = settingsService.getSettings().listeningPort();
        boolean tlsConfigured = deviceInfoService.isTlsConfigured();
        if (listening && activeServer != null) {
            updateStatus(ConnectionStatus.listening(port, pendingRequests.size()));
        } else {
            updateStatus(ConnectionStatus.standby(port, tlsConfigured));
        }
    }

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
                updateStatus(new ConnectionStatus(
                    ConnectionState.CONNECTED,
                    "Incoming Offer • #" + id,
                    "Awaiting approval for " + fileName + " from " + senderText,
                    settingsService.getSettings().listeningPort(),
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
    }

    private void updateStatus(ConnectionStatus next) {
        runOnFxThread(() -> status.set(next));
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
