package com.syncshare.model;

import java.util.Objects;

/**
 * Immutable snapshot of SyncShare receiver and peer connectivity state.
 */
public record ConnectionStatus(
    ConnectionState state,
    String headline,
    String detail,
    int listenPort,
    boolean tlsConfigured,
    int activePeers
) {
    public ConnectionStatus {
        state = Objects.requireNonNullElse(state, ConnectionState.STANDBY);
        headline = headline != null ? headline : state.displayName();
        detail = detail != null ? detail : "";
    }

    public static ConnectionStatus standby(int port, boolean tlsConfigured) {
        String detail = tlsConfigured
            ? "mTLS 1.3 credentials verified. Receiver ready on port " + port + "."
            : "Set SYNCSHARE_TLS_PASSWORD and peer certificates to start listening.";
        return new ConnectionStatus(
            ConnectionState.STANDBY,
            "Standby • Port " + port,
            detail,
            port,
            tlsConfigured,
            0
        );
    }

    public static ConnectionStatus listening(int port, int activePeers) {
        String label = activePeers > 0
            ? "Listening • " + activePeers + " Peer" + (activePeers == 1 ? "" : "s")
            : "Listening • Port " + port;
        return new ConnectionStatus(
            activePeers > 0 ? ConnectionState.CONNECTED : ConnectionState.LISTENING,
            label,
            "Accepting Mutual TLS 1.3 peer connections on TCP port " + port + ".",
            port,
            true,
            activePeers
        );
    }

    public static ConnectionStatus tlsSetupRequired(int port) {
        return new ConnectionStatus(
            ConnectionState.TLS_UNCONFIGURED,
            "TLS Setup Needed",
            "Missing SYNCSHARE_TLS_PASSWORD or keystores (certs/identity.p12, certs/trusted-peers.p12).",
            port,
            false,
            0
        );
    }

    public static ConnectionStatus error(int port, String reason) {
        return new ConnectionStatus(
            ConnectionState.ERROR,
            "Listener Error",
            reason != null && !reason.isBlank() ? reason : "Unable to bind TCP listener on port " + port + ".",
            port,
            false,
            0
        );
    }
}
