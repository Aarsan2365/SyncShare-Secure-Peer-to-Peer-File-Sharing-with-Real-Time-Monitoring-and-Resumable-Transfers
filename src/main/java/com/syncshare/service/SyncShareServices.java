package com.syncshare.service;

import java.util.Objects;

/**
 * Application-scoped service registry shared across all SyncShare views
 * so screen navigation never recreates underlying services or state.
 */
public final class SyncShareServices {

    private final SettingsService settingsService;
    private final DeviceInfoService deviceInfoService;
    private final ConnectionService connectionService;
    private final TransferService transferService;

    public SyncShareServices(
        SettingsService settingsService,
        DeviceInfoService deviceInfoService,
        ConnectionService connectionService,
        TransferService transferService
    ) {
        this.settingsService = Objects.requireNonNull(settingsService, "settingsService");
        this.deviceInfoService = Objects.requireNonNull(deviceInfoService, "deviceInfoService");
        this.connectionService = Objects.requireNonNull(connectionService, "connectionService");
        this.transferService = Objects.requireNonNull(transferService, "transferService");
    }

    public static SyncShareServices createDefault() {
        SettingsService settings = new SettingsService();
        DeviceInfoService deviceInfo = new DeviceInfoService(settings);
        TransferService transfers = new TransferService();
        ConnectionService connection = new ConnectionService(settings, deviceInfo, transfers);
        return new SyncShareServices(settings, deviceInfo, connection, transfers);
    }

    public SettingsService settingsService() {
        return settingsService;
    }

    public DeviceInfoService deviceInfoService() {
        return deviceInfoService;
    }

    public ConnectionService connectionService() {
        return connectionService;
    }

    public TransferService transferService() {
        return transferService;
    }

    public void shutdown() {
        connectionService.shutdown();
        transferService.shutdown();
    }
}
