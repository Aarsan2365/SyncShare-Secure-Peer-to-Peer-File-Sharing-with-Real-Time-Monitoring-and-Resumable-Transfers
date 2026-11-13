package com.syncshare.model;

import java.io.File;
import java.nio.file.Path;
import java.util.Objects;

/**
 * User-configurable SyncShare desktop application settings model.
 *
 * <p>Encapsulates storage preferences, application lifecycle flags, notification settings,
 * network peer identity, security verification policies, and theme preference.
 * Independent of JavaFX presentation code.</p>
 */
public record AppSettings(
    String deviceAlias,
    int listeningPort,
    String receiveDirectory,
    String downloadDirectory,
    boolean requireApproval,
    boolean verifySha256,
    boolean autoStartReceiver,
    boolean startMinimized,
    boolean notificationsEnabled,
    boolean notifyOnTransferComplete,
    boolean notifyOnIncomingRequest,
    boolean soundEnabled,
    String theme
) {
    public static final int DEFAULT_PORT = 5050;
    public static final int MIN_PORT = 1024;
    public static final int MAX_PORT = 65535;
    public static final String DEFAULT_RECEIVE_DIR = "received_files";
    public static final String DEFAULT_THEME = "DEFAULT";
    public static final String DEFAULT_ALIAS = "SyncShare-Workstation";

    /**
     * Backward-compatible 6-parameter constructor for existing codebase and tests.
     */
    public AppSettings(
        String deviceAlias,
        int listeningPort,
        String receiveDirectory,
        boolean requireApproval,
        boolean verifySha256,
        boolean autoStartReceiver
    ) {
        this(
            deviceAlias,
            listeningPort,
            receiveDirectory,
            resolveDefaultDownloadDir(receiveDirectory),
            requireApproval,
            verifySha256,
            autoStartReceiver,
            false, // startMinimized
            true,  // notificationsEnabled
            true,  // notifyOnTransferComplete
            true,  // notifyOnIncomingRequest
            true,  // soundEnabled
            DEFAULT_THEME
        );
    }

    /**
     * Canonical constructor validating non-null essential string references.
     */
    public AppSettings {
        deviceAlias = deviceAlias != null ? deviceAlias.trim() : DEFAULT_ALIAS;
        receiveDirectory = receiveDirectory != null && !receiveDirectory.isBlank()
            ? receiveDirectory.trim()
            : DEFAULT_RECEIVE_DIR;
        downloadDirectory = downloadDirectory != null && !downloadDirectory.isBlank()
            ? downloadDirectory.trim()
            : resolveDefaultDownloadDir(receiveDirectory);
        theme = theme != null && !theme.isBlank() ? theme.trim() : DEFAULT_THEME;
    }

    /**
     * Alias for {@link #downloadDirectory()} matching domain terminology.
     */
    public String defaultDownloadDirectory() {
        return downloadDirectory;
    }

    /**
     * Alias for {@link #receiveDirectory()} matching domain terminology.
     */
    public String defaultSaveLocation() {
        return receiveDirectory;
    }

    /**
     * Alias for {@link #receiveDirectory()} matching domain terminology.
     */
    public String saveLocation() {
        return receiveDirectory;
    }

    public static AppSettings defaults() {
        String hostFallback = System.getenv("COMPUTERNAME");
        if (hostFallback == null || hostFallback.isBlank()) {
            hostFallback = System.getenv("HOSTNAME");
        }
        if (hostFallback == null || hostFallback.isBlank()) {
            hostFallback = DEFAULT_ALIAS;
        }
        String configuredReceiveDir = System.getProperty("syncshare.receiveDir", DEFAULT_RECEIVE_DIR);
        String configuredDownloadDir = System.getProperty(
            "syncshare.downloadDir",
            resolveUserDownloadsDir(configuredReceiveDir)
        );

        return new AppSettings(
            hostFallback,
            DEFAULT_PORT,
            configuredReceiveDir,
            configuredDownloadDir,
            true,   // requireApproval
            true,   // verifySha256
            false,  // autoStartReceiver
            false,  // startMinimized
            true,   // notificationsEnabled
            true,   // notifyOnTransferComplete
            true,   // notifyOnIncomingRequest
            true,   // soundEnabled
            DEFAULT_THEME
        );
    }

    public static String resolveDefaultDownloadDir(String fallbackReceiveDir) {
        String prop = System.getProperty("syncshare.downloadDir");
        if (prop != null && !prop.isBlank()) {
            return prop.trim();
        }
        return resolveUserDownloadsDir(fallbackReceiveDir);
    }

    private static String resolveUserDownloadsDir(String fallback) {
        String userHome = System.getProperty("user.home");
        if (userHome != null && !userHome.isBlank()) {
            Path downloadsPath = Path.of(userHome, "Downloads");
            return downloadsPath.toAbsolutePath().normalize().toString();
        }
        return fallback != null && !fallback.isBlank() ? fallback : DEFAULT_RECEIVE_DIR;
    }

    public Builder toBuilder() {
        return new Builder(this);
    }

    public static Builder builder() {
        return new Builder(defaults());
    }

    public AppSettings withDeviceAlias(String deviceAlias) {
        return toBuilder().deviceAlias(deviceAlias).build();
    }

    public AppSettings withListeningPort(int listeningPort) {
        return toBuilder().listeningPort(listeningPort).build();
    }

    public AppSettings withReceiveDirectory(String receiveDirectory) {
        return toBuilder().receiveDirectory(receiveDirectory).build();
    }

    public AppSettings withDownloadDirectory(String downloadDirectory) {
        return toBuilder().downloadDirectory(downloadDirectory).build();
    }

    public AppSettings withRequireApproval(boolean requireApproval) {
        return toBuilder().requireApproval(requireApproval).build();
    }

    public AppSettings withVerifySha256(boolean verifySha256) {
        return toBuilder().verifySha256(verifySha256).build();
    }

    public AppSettings withAutoStartReceiver(boolean autoStartReceiver) {
        return toBuilder().autoStartReceiver(autoStartReceiver).build();
    }

    public AppSettings withStartMinimized(boolean startMinimized) {
        return toBuilder().startMinimized(startMinimized).build();
    }

    public AppSettings withNotificationsEnabled(boolean notificationsEnabled) {
        return toBuilder().notificationsEnabled(notificationsEnabled).build();
    }

    public AppSettings withNotifyOnTransferComplete(boolean notifyOnTransferComplete) {
        return toBuilder().notifyOnTransferComplete(notifyOnTransferComplete).build();
    }

    public AppSettings withNotifyOnIncomingRequest(boolean notifyOnIncomingRequest) {
        return toBuilder().notifyOnIncomingRequest(notifyOnIncomingRequest).build();
    }

    public AppSettings withSoundEnabled(boolean soundEnabled) {
        return toBuilder().soundEnabled(soundEnabled).build();
    }

    public AppSettings withTheme(String theme) {
        return toBuilder().theme(theme).build();
    }

    public static final class Builder {
        private String deviceAlias;
        private int listeningPort;
        private String receiveDirectory;
        private String downloadDirectory;
        private boolean requireApproval;
        private boolean verifySha256;
        private boolean autoStartReceiver;
        private boolean startMinimized;
        private boolean notificationsEnabled;
        private boolean notifyOnTransferComplete;
        private boolean notifyOnIncomingRequest;
        private boolean soundEnabled;
        private String theme;

        public Builder(AppSettings initial) {
            Objects.requireNonNull(initial, "initial");
            this.deviceAlias = initial.deviceAlias();
            this.listeningPort = initial.listeningPort();
            this.receiveDirectory = initial.receiveDirectory();
            this.downloadDirectory = initial.downloadDirectory();
            this.requireApproval = initial.requireApproval();
            this.verifySha256 = initial.verifySha256();
            this.autoStartReceiver = initial.autoStartReceiver();
            this.startMinimized = initial.startMinimized();
            this.notificationsEnabled = initial.notificationsEnabled();
            this.notifyOnTransferComplete = initial.notifyOnTransferComplete();
            this.notifyOnIncomingRequest = initial.notifyOnIncomingRequest();
            this.soundEnabled = initial.soundEnabled();
            this.theme = initial.theme();
        }

        public Builder deviceAlias(String deviceAlias) {
            this.deviceAlias = deviceAlias;
            return this;
        }

        public Builder listeningPort(int listeningPort) {
            this.listeningPort = listeningPort;
            return this;
        }

        public Builder receiveDirectory(String receiveDirectory) {
            this.receiveDirectory = receiveDirectory;
            return this;
        }

        public Builder downloadDirectory(String downloadDirectory) {
            this.downloadDirectory = downloadDirectory;
            return this;
        }

        public Builder requireApproval(boolean requireApproval) {
            this.requireApproval = requireApproval;
            return this;
        }

        public Builder verifySha256(boolean verifySha256) {
            this.verifySha256 = verifySha256;
            return this;
        }

        public Builder autoStartReceiver(boolean autoStartReceiver) {
            this.autoStartReceiver = autoStartReceiver;
            return this;
        }

        public Builder startMinimized(boolean startMinimized) {
            this.startMinimized = startMinimized;
            return this;
        }

        public Builder notificationsEnabled(boolean notificationsEnabled) {
            this.notificationsEnabled = notificationsEnabled;
            return this;
        }

        public Builder notifyOnTransferComplete(boolean notifyOnTransferComplete) {
            this.notifyOnTransferComplete = notifyOnTransferComplete;
            return this;
        }

        public Builder notifyOnIncomingRequest(boolean notifyOnIncomingRequest) {
            this.notifyOnIncomingRequest = notifyOnIncomingRequest;
            return this;
        }

        public Builder soundEnabled(boolean soundEnabled) {
            this.soundEnabled = soundEnabled;
            return this;
        }

        public Builder theme(String theme) {
            this.theme = theme;
            return this;
        }

        public AppSettings build() {
            return new AppSettings(
                deviceAlias,
                listeningPort,
                receiveDirectory,
                downloadDirectory,
                requireApproval,
                verifySha256,
                autoStartReceiver,
                startMinimized,
                notificationsEnabled,
                notifyOnTransferComplete,
                notifyOnIncomingRequest,
                soundEnabled,
                theme
            );
        }
    }
}
