package com.syncshare.model;

import com.syncshare.ui.design.SemanticTone;

/**
 * Lifecycle state of an active or historical file transfer.
 */
public enum TransferStatus {
    QUEUED("Queued", SemanticTone.NEUTRAL, true),
    AWAITING_APPROVAL("Pending Approval", SemanticTone.WARNING, true),
    TRANSFERRING("Transferring", SemanticTone.TEAL, true),
    PAUSED("Paused", SemanticTone.WARNING, true),
    COMPLETED("Verified", SemanticTone.SUCCESS, false),
    FAILED("Failed", SemanticTone.ERROR, false),
    REJECTED("Rejected", SemanticTone.ERROR, false);

    private final String displayName;
    private final SemanticTone tone;
    private final boolean active;

    TransferStatus(String displayName, SemanticTone tone, boolean active) {
        this.displayName = displayName;
        this.tone = tone;
        this.active = active;
    }

    public String displayName() {
        return displayName;
    }

    public SemanticTone tone() {
        return tone;
    }

    public boolean isActive() {
        return active;
    }
}
