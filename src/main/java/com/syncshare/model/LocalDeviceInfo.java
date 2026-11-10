package com.syncshare.model;

/**
 * Snapshot of the local workstation identity and SyncShare configuration.
 */
public record LocalDeviceInfo(
    String deviceName,
    String ipAddress,
    int listeningPort,
    String operatingSystem,
    String tlsSummary,
    String keyStorePath,
    String trustStorePath,
    String receiveDirectory,
    boolean tlsConfigured
) {
}
