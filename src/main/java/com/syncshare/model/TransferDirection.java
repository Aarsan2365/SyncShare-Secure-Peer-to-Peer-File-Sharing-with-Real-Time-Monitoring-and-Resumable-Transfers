package com.syncshare.model;

/**
 * Direction of a peer-to-peer file transfer relative to this device.
 */
public enum TransferDirection {
    OUTGOING("Outgoing"),
    INCOMING("Incoming");

    private final String label;

    TransferDirection(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
