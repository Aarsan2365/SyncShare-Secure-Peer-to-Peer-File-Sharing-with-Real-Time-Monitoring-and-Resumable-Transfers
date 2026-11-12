package com.syncshare.monitoring;

import java.util.List;

/**
 * Listener notified when monitored active or recent transfer snapshots change.
 */
@FunctionalInterface
public interface TransferMonitorListener {

    void onTransfersUpdated(
        List<MonitoredTransfer> activeTransfers,
        List<MonitoredTransfer> recentTransfers
    );
}
