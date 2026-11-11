package com.syncshare.service;

import com.syncshare.model.AppSettings;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.ReadOnlyObjectWrapper;

import java.util.Objects;

/**
 * Manages application configuration state and validation across SyncShare views.
 */
public class SettingsService {

    public record ValidationResult(boolean valid, String message) {
        public static ValidationResult ok(String message) {
            return new ValidationResult(true, message);
        }

        public static ValidationResult error(String message) {
            return new ValidationResult(false, message);
        }
    }

    private final ReadOnlyObjectWrapper<AppSettings> settings =
        new ReadOnlyObjectWrapper<>(this, "settings", AppSettings.defaults());

    public SettingsService() {
        applySystemProperties(settings.get());
    }

    public AppSettings getSettings() {
        return settings.get();
    }

    public ReadOnlyObjectProperty<AppSettings> settingsProperty() {
        return settings.getReadOnlyProperty();
    }

    public ValidationResult validate(
        String deviceAlias,
        String portText,
        String receiveDirectory
    ) {
        if (deviceAlias == null || deviceAlias.isBlank()) {
            return ValidationResult.error("Device alias cannot be empty.");
        }
        int port;
        try {
            port = Integer.parseInt(portText != null ? portText.trim() : "");
        } catch (NumberFormatException e) {
            return ValidationResult.error("Listening port must be a valid integer.");
        }
        if (port < 1024 || port > 65535) {
            return ValidationResult.error("Listening port must be between 1024 and 65535.");
        }
        if (receiveDirectory == null || receiveDirectory.isBlank()) {
            return ValidationResult.error("Receive directory cannot be empty.");
        }
        return ValidationResult.ok("Settings input valid.");
    }

    public ValidationResult updateSettings(AppSettings updated) {
        Objects.requireNonNull(updated, "updated");
        ValidationResult check = validate(
            updated.deviceAlias(),
            Integer.toString(updated.listeningPort()),
            updated.receiveDirectory()
        );
        if (!check.valid()) {
            return check;
        }
        AppSettings normalized = new AppSettings(
            updated.deviceAlias().trim(),
            updated.listeningPort(),
            updated.receiveDirectory().trim(),
            updated.requireApproval(),
            updated.verifySha256(),
            updated.autoStartReceiver()
        );
        applySystemProperties(normalized);
        runOnFxThread(() -> settings.set(normalized));
        return ValidationResult.ok("Settings saved and applied.");
    }

    public void resetDefaults() {
        AppSettings defaults = AppSettings.defaults();
        applySystemProperties(defaults);
        runOnFxThread(() -> settings.set(defaults));
    }

    private static void applySystemProperties(AppSettings value) {
        System.setProperty("syncshare.receiveDir", value.receiveDirectory());
    }

    private static void runOnFxThread(Runnable task) {
        if (Platform.isFxApplicationThread()) {
            task.run();
        } else {
            try {
                Platform.runLater(task);
            } catch (IllegalStateException toolkitNotStarted) {
                task.run();
            }
        }
    }
}
