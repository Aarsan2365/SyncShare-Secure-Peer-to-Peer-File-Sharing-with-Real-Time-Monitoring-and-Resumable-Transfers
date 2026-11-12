package com.syncshare.history;

import com.syncshare.model.TransferDirection;

/**
 * Direction of a completed, failed, or cancelled transfer in persistent history.
 */
public enum TransferHistoryDirection {
    SEND("Send"),
    RECEIVE("Receive");

    private final String displayName;

    TransferHistoryDirection(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }

    public static TransferHistoryDirection from(TransferDirection direction) {
        if (direction == null) {
            throw new IllegalArgumentException("direction must not be null");
        }
        return switch (direction) {
            case OUTGOING -> SEND;
            case INCOMING -> RECEIVE;
        };
    }
}
