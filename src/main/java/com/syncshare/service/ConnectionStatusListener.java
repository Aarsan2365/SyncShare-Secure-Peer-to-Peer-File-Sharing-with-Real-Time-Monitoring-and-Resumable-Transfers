package com.syncshare.service;

import com.syncshare.model.ConnectionStatus;

/**
 * Listener contract notified whenever peer listener readiness or network connection
 * indicator state changes.
 */
@FunctionalInterface
public interface ConnectionStatusListener {

    /**
     * Invoked when the connection status changes.
     *
     * @param status The updated ConnectionStatus snapshot
     */
    void onConnectionStatusChanged(ConnectionStatus status);
}
