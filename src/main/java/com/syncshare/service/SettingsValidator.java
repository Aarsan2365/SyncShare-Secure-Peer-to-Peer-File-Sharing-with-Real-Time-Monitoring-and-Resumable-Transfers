package com.syncshare.service;

import com.syncshare.model.AppSettings;

import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Validates application configuration values, network boundaries, and storage directories.
 * Free of JavaFX presentation dependencies.
 */
public final class SettingsValidator {

    public static final int MAX_ALIAS_LENGTH = 64;

    private static final Set<String> SUPPORTED_THEMES = Set.of(
        AppSettings.DEFAULT_THEME,
        "SYSTEM"
    );

    public record ValidationResult(
        boolean valid,
        String message,
        Path resolvedPath,
        boolean missingDirectory
    ) {
        public static ValidationResult ok(String message) {
            return new ValidationResult(true, message, null, false);
        }

        public static ValidationResult ok(Path resolvedPath, String message) {
            return new ValidationResult(true, message, resolvedPath, false);
        }

        public static ValidationResult error(String message) {
            return new ValidationResult(false, message, null, false);
        }

        public static ValidationResult error(String message, Path resolvedPath, boolean missingDirectory) {
            return new ValidationResult(false, message, resolvedPath, missingDirectory);
        }
    }

    private SettingsValidator() {
    }

    public static ValidationResult validateDeviceAlias(String deviceAlias) {
        if (deviceAlias == null || deviceAlias.isBlank()) {
            return ValidationResult.error("Device alias cannot be empty.");
        }
        String trimmed = deviceAlias.trim();
        if (trimmed.length() > MAX_ALIAS_LENGTH) {
            return ValidationResult.error("Device alias cannot exceed " + MAX_ALIAS_LENGTH + " characters.");
        }
        for (int i = 0; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            if (c < 32 || c == 127) {
                return ValidationResult.error("Device alias contains invalid control characters.");
            }
        }
        return ValidationResult.ok("Device alias is valid.");
    }

    public static ValidationResult validatePort(String portText) {
        if (portText == null || portText.isBlank()) {
            return ValidationResult.error("Listening port cannot be empty.");
        }
        try {
            int port = Integer.parseInt(portText.trim());
            return validatePort(port);
        } catch (NumberFormatException e) {
            return ValidationResult.error("Listening port must be a valid integer.");
        }
    }

    public static ValidationResult validatePort(int port) {
        if (port < AppSettings.MIN_PORT || port > AppSettings.MAX_PORT) {
            return ValidationResult.error(
                "Listening port must be between " + AppSettings.MIN_PORT + " and " + AppSettings.MAX_PORT + "."
            );
        }
        return ValidationResult.ok("Listening port is valid.");
    }

    public static ValidationResult validateDirectory(
        String directoryPath,
        String fieldLabel,
        boolean createIfMissing,
        boolean requireWritable
    ) {
        String label = (fieldLabel != null && !fieldLabel.isBlank()) ? fieldLabel : "Directory";
        if (directoryPath == null || directoryPath.isBlank()) {
            return ValidationResult.error(label + " cannot be empty.");
        }
        String trimmed = directoryPath.trim();
        if (trimmed.indexOf('\0') >= 0) {
            return ValidationResult.error(label + " path contains an invalid NUL character.");
        }

        Path path;
        try {
            path = Path.of(trimmed).toAbsolutePath().normalize();
        } catch (InvalidPathException e) {
            return ValidationResult.error(label + " path is invalid: " + e.getReason());
        }

        if (Files.notExists(path)) {
            if (createIfMissing) {
                try {
                    Files.createDirectories(path);
                } catch (AccessDeniedException e) {
                    return ValidationResult.error(
                        label + " does not exist and cannot be created (access denied): " + path,
                        path,
                        true
                    );
                } catch (IOException | SecurityException e) {
                    return ValidationResult.error(
                        label + " does not exist and creation failed: " + e.getMessage(),
                        path,
                        true
                    );
                }
            } else {
                return ValidationResult.error(
                    label + " does not exist: " + path,
                    path,
                    true
                );
            }
        }

        if (!Files.isDirectory(path)) {
            return ValidationResult.error(
                label + " path exists but is not a directory: " + path,
                path,
                false
            );
        }

        Path realDir;
        try {
            realDir = path.toRealPath();
        } catch (AccessDeniedException e) {
            return ValidationResult.error(
                label + " is inaccessible or access is denied: " + path,
                path,
                false
            );
        } catch (IOException | SecurityException e) {
            return ValidationResult.error(
                "Cannot access " + label.toLowerCase() + ": " + e.getMessage(),
                path,
                false
            );
        }

        if (requireWritable) {
            if (!Files.isWritable(realDir)) {
                return ValidationResult.error(
                    label + " is not writable: " + realDir,
                    realDir,
                    false
                );
            }

            try {
                Path probe = Files.createTempFile(realDir, ".syncshare-probe-", ".tmp");
                Files.deleteIfExists(probe);
            } catch (AccessDeniedException e) {
                return ValidationResult.error(
                    label + " is not writable: " + realDir,
                    realDir,
                    false
                );
            } catch (IOException | SecurityException e) {
                return ValidationResult.error(
                    "Cannot write to " + label.toLowerCase() + ": " + e.getMessage(),
                    realDir,
                    false
                );
            }
        }

        return ValidationResult.ok(realDir, label + " is valid and accessible: " + realDir);
    }

    public static ValidationResult validateTheme(String theme) {
        if (theme == null || theme.isBlank()) {
            return ValidationResult.ok("Using default theme.");
        }
        String normalized = theme.trim().toUpperCase(java.util.Locale.ROOT);
        if (!SUPPORTED_THEMES.contains(normalized)) {
            return ValidationResult.error("Theme '" + theme + "' is not supported. Use 'DEFAULT' or 'SYSTEM'.");
        }
        return ValidationResult.ok("Theme is valid.");
    }

    public static ValidationResult validate(AppSettings settings) {
        Objects.requireNonNull(settings, "settings");

        ValidationResult aliasCheck = validateDeviceAlias(settings.deviceAlias());
        if (!aliasCheck.valid()) {
            return aliasCheck;
        }

        ValidationResult portCheck = validatePort(settings.listeningPort());
        if (!portCheck.valid()) {
            return portCheck;
        }

        ValidationResult receiveDirCheck = validateDirectory(
            settings.receiveDirectory(),
            "Receive directory",
            false,
            true
        );
        if (!receiveDirCheck.valid()) {
            return receiveDirCheck;
        }

        ValidationResult downloadDirCheck = validateDirectory(
            settings.downloadDirectory(),
            "Download directory",
            false,
            true
        );
        if (!downloadDirCheck.valid()) {
            return downloadDirCheck;
        }

        ValidationResult themeCheck = validateTheme(settings.theme());
        if (!themeCheck.valid()) {
            return themeCheck;
        }

        return ValidationResult.ok("Application settings are valid.");
    }

    /**
     * Validates settings with detailed list of all issues found rather than stopping at first failure.
     */
    public static List<String> validateAll(AppSettings settings) {
        Objects.requireNonNull(settings, "settings");
        List<String> errors = new ArrayList<>();

        ValidationResult aliasCheck = validateDeviceAlias(settings.deviceAlias());
        if (!aliasCheck.valid()) {
            errors.add(aliasCheck.message());
        }

        ValidationResult portCheck = validatePort(settings.listeningPort());
        if (!portCheck.valid()) {
            errors.add(portCheck.message());
        }

        ValidationResult receiveDirCheck = validateDirectory(
            settings.receiveDirectory(),
            "Receive directory",
            false,
            true
        );
        if (!receiveDirCheck.valid()) {
            errors.add(receiveDirCheck.message());
        }

        ValidationResult downloadDirCheck = validateDirectory(
            settings.downloadDirectory(),
            "Download directory",
            false,
            true
        );
        if (!downloadDirCheck.valid()) {
            errors.add(downloadDirCheck.message());
        }

        ValidationResult themeCheck = validateTheme(settings.theme());
        if (!themeCheck.valid()) {
            errors.add(themeCheck.message());
        }

        return List.copyOf(errors);
    }
}
