package com.syncshare.service;

import com.syncshare.model.AppSettings;
import com.syncshare.model.LocalDeviceInfo;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.ReadOnlyObjectWrapper;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.Objects;

/**
 * Resolves local device identity, LAN IPv4 address, OS metadata, and TLS readiness
 * without placing network inspection logic inside JavaFX controllers.
 */
public class DeviceInfoService {

    private final SettingsService settingsService;
    private final ReadOnlyObjectWrapper<LocalDeviceInfo> deviceInfo =
        new ReadOnlyObjectWrapper<>(this, "deviceInfo");

    public DeviceInfoService(SettingsService settingsService) {
        this.settingsService = Objects.requireNonNull(settingsService, "settingsService");
        this.settingsService.settingsProperty().addListener((obs, oldVal, newVal) -> refresh());
        refresh();
    }

    public LocalDeviceInfo getDeviceInfo() {
        return deviceInfo.get();
    }

    public ReadOnlyObjectProperty<LocalDeviceInfo> deviceInfoProperty() {
        return deviceInfo.getReadOnlyProperty();
    }

    public boolean isTlsConfigured() {
        String password = System.getenv("SYNCSHARE_TLS_PASSWORD");
        if (password == null || password.isBlank()) {
            return false;
        }
        Path keyPath = Path.of(System.getProperty("syncshare.keyStore", "certs/identity.p12"));
        Path trustPath = Path.of(System.getProperty("syncshare.trustStore", "certs/trusted-peers.p12"));
        return Files.isRegularFile(keyPath) && Files.isRegularFile(trustPath);
    }

    public LocalDeviceInfo refresh() {
        AppSettings settings = settingsService.getSettings();
        String hostName = resolveHostName(settings.deviceAlias());
        String ipAddress = resolveLocalIpv4Address();
        String os = System.getProperty("os.name", "Desktop OS")
            + " (" + System.getProperty("os.arch", "x64") + ")";
        String keyStorePath = System.getProperty("syncshare.keyStore", "certs/identity.p12");
        String trustStorePath = System.getProperty("syncshare.trustStore", "certs/trusted-peers.p12");
        boolean tlsReady = isTlsConfigured();
        String tlsSummary = tlsReady
            ? "Mutual TLS 1.3 Ready"
            : "Setup Required (certs / SYNCSHARE_TLS_PASSWORD)";

        LocalDeviceInfo snapshot = new LocalDeviceInfo(
            hostName,
            ipAddress,
            settings.listeningPort(),
            os,
            tlsSummary,
            keyStorePath,
            trustStorePath,
            settings.receiveDirectory(),
            tlsReady
        );
        runOnFxThread(() -> deviceInfo.set(snapshot));
        return snapshot;
    }

    private static String resolveHostName(String alias) {
        if (alias != null && !alias.isBlank()) {
            return alias;
        }
        try {
            String host = InetAddress.getLocalHost().getHostName();
            if (host != null && !host.isBlank()) {
                return host;
            }
        } catch (Exception ignored) {
        }
        return "SyncShare-Workstation";
    }

    private static String resolveLocalIpv4Address() {
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            String siteLocalCandidate = null;
            while (interfaces != null && interfaces.hasMoreElements()) {
                NetworkInterface nic = interfaces.nextElement();
                if (!nic.isUp() || nic.isLoopback() || nic.isVirtual()) {
                    continue;
                }
                Enumeration<InetAddress> addresses = nic.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress addr = addresses.nextElement();
                    if (addr instanceof Inet4Address ipv4 && !ipv4.isLoopbackAddress()) {
                        if (ipv4.isSiteLocalAddress()) {
                            return ipv4.getHostAddress();
                        }
                        if (siteLocalCandidate == null) {
                            siteLocalCandidate = ipv4.getHostAddress();
                        }
                    }
                }
            }
            if (siteLocalCandidate != null) {
                return siteLocalCandidate;
            }
            InetAddress localHost = InetAddress.getLocalHost();
            if (localHost != null && localHost.getHostAddress() != null) {
                return localHost.getHostAddress();
            }
        } catch (Exception ignored) {
        }
        return "127.0.0.1";
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
