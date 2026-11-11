package com.syncshare.model;

import com.syncshare.ui.design.SemanticTone;

/**
 * High-level network listener and peer connectivity states.
 */
public enum ConnectionState {
    LISTENING("Listening", SemanticTone.SUCCESS),
    CONNECTED("Connected", SemanticTone.TEAL),
    STANDBY("Standby", SemanticTone.INFO),
    TLS_UNCONFIGURED("TLS Setup Needed", SemanticTone.WARNING),
    OFFLINE("Offline", SemanticTone.NEUTRAL),
    ERROR("Error", SemanticTone.ERROR);

    private final String displayName;
    private final SemanticTone tone;

    ConnectionState(String displayName, SemanticTone tone) {
        this.displayName = displayName;
        this.tone = tone;
    }

    public String displayName() {
        return displayName;
    }

    public SemanticTone tone() {
        return tone;
    }
}
