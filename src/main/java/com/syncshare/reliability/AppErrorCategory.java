package com.syncshare.reliability;

import com.syncshare.ui.design.SemanticTone;

/**
 * Standardized categorization for application and network reliability errors.
 */
public enum AppErrorCategory {
    CONNECTION_FAILED("Connection Failed", SemanticTone.ERROR, true),
    FILE_NOT_FOUND("File Not Found", SemanticTone.ERROR, true),
    DESTINATION_UNAVAILABLE("Destination Unavailable", SemanticTone.ERROR, true),
    INSUFFICIENT_STORAGE("Insufficient Storage", SemanticTone.WARNING, true),
    PERMISSION_DENIED("Permission Denied", SemanticTone.ERROR, true),
    TRANSFER_INTERRUPTED("Transfer Interrupted", SemanticTone.WARNING, true),
    INVALID_CONFIGURATION("Invalid Configuration", SemanticTone.ERROR, true),
    STARTUP_FAILED("Startup Failed", SemanticTone.ERROR, false),
    UNEXPECTED_ERROR("Unexpected Error", SemanticTone.ERROR, false);

    private final String displayName;
    private final SemanticTone tone;
    private final boolean recoverable;

    AppErrorCategory(String displayName, SemanticTone tone, boolean recoverable) {
        this.displayName = displayName;
        this.tone = tone;
        this.recoverable = recoverable;
    }

    public String displayName() {
        return displayName;
    }

    public SemanticTone tone() {
        return tone;
    }

    public boolean isRecoverable() {
        return recoverable;
    }
}
