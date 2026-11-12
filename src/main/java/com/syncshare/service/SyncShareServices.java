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
    private final TransferMonitoringService transferMonitoringService;

    public SyncShareServices(
        SettingsService settingsService,
        DeviceInfoService deviceInfoService,
        ConnectionService connectionService,
        TransferService transferService
    ) {
        this(
            settingsService,
            deviceInfoService,
            connectionService,
            transferService,
            transferService.getMonitoringService()
        );
    }

    public SyncShareServices(
        SettingsService settingsService,
        DeviceInfoService deviceInfoService,
        ConnectionService connectionService,
        TransferService transferService,
        TransferMonitoringService transferMonitoringService
    ) {
        this.settingsService = Objects.requireNonNull(settingsService, "settingsService");
        this.deviceInfoService = Objects.requireNonNull(deviceInfoService, "deviceInfoService");
        this.connectionService = Objects.requireNonNull(connectionService, "connectionService");
        this.transferService = Objects.requireNonNull(transferService, "transferService");
        this.transferMonitoringService = Objects.requireNonNull(transferMonitoringService, "transferMonitoringService");
    }

    public static SyncShareServices createDefault() {
        SettingsService settings = new SettingsService();
        DeviceInfoService deviceInfo = new DeviceInfoService(settings);
        TransferMonitoringService monitoring = new TransferMonitoringService();
        TransferService transfers = new TransferService(monitoring);
        ConnectionService connection = new ConnectionService(settings, deviceInfo, transfers, monitoring);
        return new SyncShareServices(settings, deviceInfo, connection, transfers, monitoring);
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

    public TransferMonitoringService transferMonitoringService() {
        return transferMonitoringService;
    }

    public void shutdown() {
        connectionService.shutdown();
        transferService.shutdown();
        transferMonitoringService.shutdown();
    }
}
