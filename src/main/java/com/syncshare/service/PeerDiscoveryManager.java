package com.syncshare.service;

import com.syncshare.discovery.DeviceDiscoveryService;
import com.syncshare.discovery.DiscoveryService;
import com.syncshare.discovery.PeerInfo;
import com.syncshare.model.AppSettings;
import com.syncshare.reliability.CentralErrorHandler;
import com.syncshare.reliability.ReliabilityLogger;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyBooleanProperty;
import javafx.beans.property.ReadOnlyBooleanWrapper;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;

import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Coordinates background UDP device discovery, maintaining a live thread-safe
 * list of nearby SyncShare peers for JavaFX views.
 */
public class PeerDiscoveryManager implements AutoCloseable {

    private static final ReliabilityLogger LOGGER = ReliabilityLogger.getLogger(PeerDiscoveryManager.class);
    private static final long POLL_INTERVAL_SECONDS = 2L;

    @FunctionalInterface
    public interface DiscoveryServiceFactory {
        DeviceDiscoveryService create(String name, int port) throws IOException;
    }

    private final SettingsService settingsService;
    private final DeviceInfoService deviceInfoService;
    private final DiscoveryServiceFactory serviceFactory;

    private final ObservableList<PeerInfo> discoveredPeers = FXCollections.observableArrayList();
    private final ObservableList<PeerInfo> unmodifiablePeers = FXCollections.unmodifiableObservableList(discoveredPeers);
    private final ReadOnlyBooleanWrapper active = new ReadOnlyBooleanWrapper(this, "active", false);

    private DeviceDiscoveryService discoveryService;
    private ScheduledExecutorService poller;
    private volatile boolean started;

    public PeerDiscoveryManager(SettingsService settingsService, DeviceInfoService deviceInfoService) {
        this(settingsService, deviceInfoService, DiscoveryService::new);
    }

    public PeerDiscoveryManager(
        SettingsService settingsService,
        DeviceInfoService deviceInfoService,
        DiscoveryServiceFactory serviceFactory
    ) {
        this.settingsService = Objects.requireNonNull(settingsService, "settingsService");
        this.deviceInfoService = Objects.requireNonNull(deviceInfoService, "deviceInfoService");
        this.serviceFactory = Objects.requireNonNull(serviceFactory, "serviceFactory");

        // React to port or alias changes in settings
        this.settingsService.settingsProperty().addListener((obs, oldVal, newVal) -> {
            if (started) {
                boolean aliasChanged = oldVal == null || !Objects.equals(oldVal.deviceAlias(), newVal.deviceAlias());
                boolean portChanged = oldVal == null || oldVal.listeningPort() != newVal.listeningPort();
                if (aliasChanged || portChanged) {
                    restart();
                }
            }
        });
    }

    public ObservableList<PeerInfo> getDiscoveredPeers() {
        return unmodifiablePeers;
    }

    public ReadOnlyBooleanProperty activeProperty() {
        return active.getReadOnlyProperty();
    }

    public boolean isActive() {
        return active.get();
    }

    public synchronized void start() {
        if (started) {
            return;
        }
        started = true;
        launchService();
    }

    private synchronized void launchService() {
        stopServiceOnly();

        AppSettings settings = settingsService.getSettings();
        String name = settings.deviceAlias();
        if (name == null || name.isBlank()) {
            name = deviceInfoService.getDeviceInfo() != null
                ? deviceInfoService.getDeviceInfo().deviceName()
                : "SyncShare-PC";
        }
        int port = settings.listeningPort();

        try {
            this.discoveryService = serviceFactory.create(name, port);
            runOnFxThread(() -> active.set(true));

            poller = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "syncshare-discovery-poll");
                t.setDaemon(true);
                return t;
            });
            poller.scheduleWithFixedDelay(this::pollPeers, 1, POLL_INTERVAL_SECONDS, TimeUnit.SECONDS);
        } catch (Exception e) {
            LOGGER.warn("UDP Discovery could not be started on port 5051: " + e.getMessage(), e);
            CentralErrorHandler.getInstance().handleError(e, "discovery:5051");
            runOnFxThread(() -> active.set(false));
        }
    }

    private void pollPeers() {
        DeviceDiscoveryService currentService = this.discoveryService;
        if (currentService == null || !currentService.isRunning()) {
            return;
        }
        try {
            List<PeerInfo> current = currentService.listPeers();
            runOnFxThread(() -> {
                if (!discoveredPeers.equals(current)) {
                    discoveredPeers.setAll(current);
                }
            });
        } catch (Exception e) {
            LOGGER.debug("Error polling discovered peers: " + e.getMessage());
        }
    }

    public synchronized void restart() {
        if (started) {
            launchService();
        }
    }

    public synchronized void stop() {
        started = false;
        stopServiceOnly();
        runOnFxThread(() -> {
            active.set(false);
            discoveredPeers.clear();
        });
    }

    private void stopServiceOnly() {
        if (poller != null) {
            poller.shutdownNow();
            poller = null;
        }
        if (discoveryService != null) {
            try {
                discoveryService.close();
            } catch (Exception ignored) {
            }
            discoveryService = null;
        }
    }

    @Override
    public void close() {
        stop();
    }

    public void shutdown() {
        close();
    }

    private static void runOnFxThread(Runnable action) {
        if (Platform.isFxApplicationThread()) {
            action.run();
        } else {
            Platform.runLater(action);
        }
    }
}
