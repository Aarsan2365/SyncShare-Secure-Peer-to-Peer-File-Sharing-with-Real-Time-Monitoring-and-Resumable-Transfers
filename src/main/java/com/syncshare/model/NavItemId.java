package com.syncshare.model;

/**
 * Identifiers for primary SyncShare application screens.
 */
public enum NavItemId {
    DASHBOARD("dashboard"),
    SEND_FILES("send-files"),
    RECEIVE_FILES("receive-files"),
    ACTIVE_TRANSFERS("active-transfers"),
    TRANSFER_HISTORY("transfer-history"),
    SETTINGS("settings");

    private final String routeKey;

    NavItemId(String routeKey) {
        this.routeKey = routeKey;
    }

    public String routeKey() {
        return routeKey;
    }
}
