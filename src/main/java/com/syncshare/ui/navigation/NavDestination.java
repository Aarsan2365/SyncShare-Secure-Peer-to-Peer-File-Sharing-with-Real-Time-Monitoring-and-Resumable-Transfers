package com.syncshare.ui.navigation;

import com.syncshare.model.NavItemId;
import com.syncshare.ui.design.UiIcons;

import java.util.List;
import java.util.Objects;

/**
 * Metadata descriptor for a SyncShare primary navigation destination.
 */
public record NavDestination(
    NavItemId id,
    String label,
    String headerTitle,
    String headerSubtitle,
    UiIcons.IconType iconType,
    String fxmlPath
) {
    public NavDestination {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(label, "label");
        Objects.requireNonNull(headerTitle, "headerTitle");
        headerSubtitle = headerSubtitle != null ? headerSubtitle : "";
        Objects.requireNonNull(iconType, "iconType");
        Objects.requireNonNull(fxmlPath, "fxmlPath");
    }

    public static List<NavDestination> defaultDestinations() {
        return List.of(
            new NavDestination(
                NavItemId.DASHBOARD,
                "Dashboard",
                "Dashboard",
                "Connectivity overview, local device identity, and transfer summaries",
                UiIcons.IconType.DASHBOARD,
                "/com/syncshare/ui/views/dashboard-view.fxml"
            ),
            new NavDestination(
                NavItemId.SEND_FILES,
                "Send Files",
                "Send Files",
                "Validate a peer endpoint and transmit files over Mutual TLS 1.3",
                UiIcons.IconType.SEND,
                "/com/syncshare/ui/views/send-files-view.fxml"
            ),
            new NavDestination(
                NavItemId.RECEIVE_FILES,
                "Receive Files",
                "Receive Files",
                "Control the background listener and approve incoming peer requests",
                UiIcons.IconType.RECEIVE,
                "/com/syncshare/ui/views/receive-files-view.fxml"
            ),
            new NavDestination(
                NavItemId.ACTIVE_TRANSFERS,
                "Active Transfers",
                "Active Transfers",
                "Monitor live chunk streaming progress and resumable checkpoints",
                UiIcons.IconType.ACTIVITY,
                "/com/syncshare/ui/views/active-transfers-view.fxml"
            ),
            new NavDestination(
                NavItemId.TRANSFER_HISTORY,
                "Transfer History",
                "Transfer History",
                "Audit completed, verified, rejected, and interrupted transfers",
                UiIcons.IconType.CLOCK,
                "/com/syncshare/ui/views/transfer-history-view.fxml"
            ),
            new NavDestination(
                NavItemId.SETTINGS,
                "Settings",
                "Settings",
                "Configure device alias, TCP listening port, receive storage, and mTLS",
                UiIcons.IconType.SETTINGS,
                "/com/syncshare/ui/views/settings-view.fxml"
            )
        );
    }
}
