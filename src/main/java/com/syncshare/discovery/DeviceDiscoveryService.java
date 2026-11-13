package com.syncshare.discovery;

import java.util.List;

/**
 * Service contract for LAN device discovery operations over UDP.
 */
public interface DeviceDiscoveryService extends AutoCloseable {

    /**
     * Lists active peers discovered on the local network within the expiry window.
     *
     * @return Sorted list of active PeerInfo instances
     */
    List<PeerInfo> listPeers();

    /**
     * Returns whether the discovery service is actively listening and broadcasting.
     *
     * @return true if running and socket is open
     */
    boolean isRunning();

    /**
     * Closes the discovery listener and background announcement scheduler.
     */
    @Override
    void close();
}
