package com.syncshare.model;

/**
 * User-configurable SyncShare desktop settings.
 */
public record AppSettings(
    String deviceAlias,
    int listeningPort,
    String receiveDirectory,
    boolean requireApproval,
    boolean verifySha256,
    boolean autoStartReceiver
) {
    public static final int DEFAULT_PORT = 5050;
    public static final String DEFAULT_RECEIVE_DIR = "received_files";

    public static AppSettings defaults() {
        String hostFallback = System.getenv("COMPUTERNAME");
        if (hostFallback == null || hostFallback.isBlank()) {
            hostFallback = System.getenv("HOSTNAME");
        }
        if (hostFallback == null || hostFallback.isBlank()) {
            hostFallback = "SyncShare-Workstation";
        }
        String configuredDir = System.getProperty("syncshare.receiveDir", DEFAULT_RECEIVE_DIR);
        return new AppSettings(
            hostFallback,
            DEFAULT_PORT,
            configuredDir,
            true,
            true,
            false
        );
    }
}
