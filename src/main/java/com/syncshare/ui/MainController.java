package com.syncshare.ui;

import javafx.fxml.FXML;
import javafx.scene.control.Label;

public class MainController {

    @FXML
    private Label statusLabel;

    @FXML
    private Label runtimeInfoLabel;

    private volatile boolean initialized;

    @FXML
    public void initialize() {
        initialized = true;

        if (statusLabel != null) {
            statusLabel.setText("Status: Ready");
        }

        if (runtimeInfoLabel != null) {
            String javaVersion = System.getProperty("java.version", "unknown");
            String javafxVersion = System.getProperty("javafx.version", "21");
            runtimeInfoLabel.setText(
                "Runtime: Java " + javaVersion + " | JavaFX " + javafxVersion
            );
        }
    }

    public boolean isInitialized() {
        return initialized;
    }

    public String getStatusText() {
        return statusLabel != null ? statusLabel.getText() : "";
    }

    public void shutdown() {
        if (statusLabel != null) {
            statusLabel.setText("Status: Shutting down...");
        }
    }
}
