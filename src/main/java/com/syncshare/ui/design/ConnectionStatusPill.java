package com.syncshare.ui.design;

import com.syncshare.model.ConnectionState;
import com.syncshare.model.ConnectionStatus;
import javafx.geometry.Pos;
import javafx.scene.AccessibleRole;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;

import java.util.List;
import java.util.Objects;

/**
 * Reusable header pill displaying live SyncShare receiver and peer connectivity state.
 */
public class ConnectionStatusPill extends HBox {

    private static final List<String> ALL_PILL_TONE_CLASSES = List.of(
        "connection-pill-success",
        "connection-pill-teal",
        "connection-pill-info",
        "connection-pill-warning",
        "connection-pill-error"
    );

    private final Region statusDot = new Region();
    private final Label statusLabel = new Label();
    private final Label detailLabel = new Label();
    private final Tooltip tooltip = new Tooltip();

    public ConnectionStatusPill() {
        getStyleClass().add("connection-pill");
        setAlignment(Pos.CENTER_LEFT);
        setMinSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
        setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
        setAccessibleRole(AccessibleRole.TEXT);

        statusDot.getStyleClass().addAll("status-dot", "status-dot-info");

        statusLabel.getStyleClass().add("connection-pill-label");
        statusLabel.setMinWidth(Region.USE_PREF_SIZE);

        detailLabel.getStyleClass().add("connection-pill-detail");
        detailLabel.setMinWidth(Region.USE_PREF_SIZE);

        getChildren().addAll(statusDot, statusLabel, detailLabel);
        Tooltip.install(this, tooltip);

        setStatus(ConnectionStatus.standby(5050, false));
    }

    public void setStatus(ConnectionStatus status) {
        ConnectionStatus resolved = Objects.requireNonNullElseGet(
            status,
            () -> ConnectionStatus.standby(5050, false)
        );
        ConnectionState state = resolved.state();
        SemanticTone tone = state.tone();

        statusLabel.setText(resolved.headline());
        String tlsTag = resolved.tlsConfigured() ? "mTLS 1.3" : "Local";
        detailLabel.setText("• " + tlsTag);
        tooltip.setText(resolved.detail());
        setAccessibleText(resolved.headline() + " - " + resolved.detail());

        DesignSystem.swapExclusiveClass(
            statusDot,
            SemanticTone.allDotClasses(),
            tone.dotStyleClass()
        );

        String pillToneClass = switch (tone) {
            case SUCCESS -> "connection-pill-success";
            case TEAL -> "connection-pill-teal";
            case INFO -> "connection-pill-info";
            case WARNING -> "connection-pill-warning";
            case ERROR -> "connection-pill-error";
            case NEUTRAL -> null;
        };
        DesignSystem.swapExclusiveClass(this, ALL_PILL_TONE_CLASSES, pillToneClass);
    }

    public String getStatusHeadline() {
        return statusLabel.getText();
    }
}
