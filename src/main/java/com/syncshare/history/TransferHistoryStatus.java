package com.syncshare.history;

import com.syncshare.model.TransferStatus;
import com.syncshare.monitoring.TransferMonitorState;
import com.syncshare.ui.design.SemanticTone;

/**
 * Terminal outcome states permitted in persistent transfer history.
 */
public enum TransferHistoryStatus {
    COMPLETED("Completed", SemanticTone.SUCCESS),
    FAILED("Failed", SemanticTone.ERROR),
    CANCELLED("Cancelled", SemanticTone.WARNING);

    private final String displayName;
    private final SemanticTone tone;

    TransferHistoryStatus(String displayName, SemanticTone tone) {
        this.displayName = displayName;
        this.tone = tone;
    }

    public String displayName() {
        return displayName;
    }

    public SemanticTone tone() {
        return tone;
    }

    public static TransferHistoryStatus from(TransferMonitorState state) {
        if (state == null) {
            throw new IllegalArgumentException("state must not be null");
        }
        return switch (state) {
            case COMPLETED -> COMPLETED;
            case FAILED -> FAILED;
            case CANCELLED -> CANCELLED;
            case QUEUED, CONNECTING, RUNNING -> throw new IllegalArgumentException(
                "Only terminal transfer states can be recorded in history: " + state
            );
        };
    }

    public static TransferHistoryStatus from(TransferStatus status) {
        if (status == null) {
            throw new IllegalArgumentException("status must not be null");
        }
        return switch (status) {
            case COMPLETED -> COMPLETED;
            case FAILED, REJECTED -> FAILED;
            case CANCELLED -> CANCELLED;
            case QUEUED, CONNECTING, AWAITING_APPROVAL, TRANSFERRING, PAUSED -> throw new IllegalArgumentException(
                "Only terminal transfer statuses can be recorded in history: " + status
            );
        };
    }
}
