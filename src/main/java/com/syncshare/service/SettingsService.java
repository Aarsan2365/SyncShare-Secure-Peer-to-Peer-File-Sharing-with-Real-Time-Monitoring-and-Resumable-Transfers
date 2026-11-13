package com.syncshare.service;

import com.syncshare.model.AppSettings;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyBooleanProperty;
import javafx.beans.property.ReadOnlyBooleanWrapper;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.beans.property.ReadOnlyStringProperty;
import javafx.beans.property.ReadOnlyStringWrapper;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Manages application configuration state, persistent storage, directory validation,
 * and asynchronous preference updates across SyncShare views.
 */
public class SettingsService {

    public record ValidationResult(boolean valid, String message) {
        public static ValidationResult ok(String message) {
            return new ValidationResult(true, message != null ? message : "Settings valid.");
        }

        public static ValidationResult error(String message) {
            return new ValidationResult(false, message != null ? message : "Settings invalid.");
        }
    }

    private final SettingsRepository repository;
    private final ExecutorService ioExecutor;
    private final AtomicBoolean shutDown = new AtomicBoolean(false);

    private final ReadOnlyObjectWrapper<AppSettings> settings =
        new ReadOnlyObjectWrapper<>(this, "settings", AppSettings.defaults());

    private final ReadOnlyBooleanWrapper saving =
        new ReadOnlyBooleanWrapper(this, "saving", false);

    private final ReadOnlyStringWrapper lastPersistenceError =
        new ReadOnlyStringWrapper(this, "lastPersistenceError", "");

    public SettingsService() {
        this(new JsonSettingsRepository());
    }

    public SettingsService(SettingsRepository repository) {
        this(repository, true);
    }

    public SettingsService(SettingsRepository repository, boolean loadOnInit) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.ioExecutor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "syncshare-settings-io");
            thread.setDaemon(true);
            return thread;
        });

        if (loadOnInit) {
            loadInitialSettings();
        } else {
            applySystemProperties(settings.get());
        }
    }

    private void loadInitialSettings() {
        try {
            SettingsRepository.LoadResult result = repository.load();
            AppSettings loaded = result.settings();
            applySystemProperties(loaded);
            settings.set(loaded);
            if (result.corrupted()) {
                lastPersistenceError.set("Settings file was corrupted; defaults loaded.");
            }
        } catch (IOException e) {
            AppSettings fallback = AppSettings.defaults();
            applySystemProperties(fallback);
            settings.set(fallback);
            lastPersistenceError.set("Failed to load settings from disk: " + e.getMessage());
        }
    }

    public AppSettings getSettings() {
        return settings.get();
    }

    public ReadOnlyObjectProperty<AppSettings> settingsProperty() {
        return settings.getReadOnlyProperty();
    }

    public boolean isSaving() {
        return saving.get();
    }

    public ReadOnlyBooleanProperty savingProperty() {
        return saving.getReadOnlyProperty();
    }

    public String getLastPersistenceError() {
        return lastPersistenceError.get();
    }

    public ReadOnlyStringProperty lastPersistenceErrorProperty() {
        return lastPersistenceError.getReadOnlyProperty();
    }

    public Path getStorageLocation() {
        return repository.storageLocation();
    }

    /**
     * Backward-compatible 3-arg validation method.
     */
    public ValidationResult validate(
        String deviceAlias,
        String portText,
        String receiveDirectory
    ) {
        return validate(deviceAlias, portText, receiveDirectory, null);
    }

    /**
     * Full directory and network validation method.
     */
    public ValidationResult validate(
        String deviceAlias,
        String portText,
        String receiveDirectory,
        String downloadDirectory
    ) {
        SettingsValidator.ValidationResult aliasCheck = SettingsValidator.validateDeviceAlias(deviceAlias);
        if (!aliasCheck.valid()) {
            return ValidationResult.error(aliasCheck.message());
        }

        SettingsValidator.ValidationResult portCheck = SettingsValidator.validatePort(portText);
        if (!portCheck.valid()) {
            return ValidationResult.error(portCheck.message());
        }

        if (receiveDirectory == null || receiveDirectory.isBlank()) {
            return ValidationResult.error("Receive directory cannot be empty.");
        }

        if (downloadDirectory != null && downloadDirectory.isBlank()) {
            return ValidationResult.error("Download directory cannot be empty.");
        }

        return ValidationResult.ok("Settings input valid.");
    }

    public SettingsValidator.ValidationResult validateDirectory(
        String path,
        String fieldLabel,
        boolean requireWritable
    ) {
        return validateDirectory(path, fieldLabel, true, requireWritable);
    }

    public SettingsValidator.ValidationResult validateDirectory(
        String path,
        String fieldLabel,
        boolean createIfMissing,
        boolean requireWritable
    ) {
        return SettingsValidator.validateDirectory(path, fieldLabel, createIfMissing, requireWritable);
    }

    /**
     * Synchronously validates, applies, and persists updated settings.
     */
    public ValidationResult updateSettings(AppSettings updated) {
        Objects.requireNonNull(updated, "updated");
        ValidationResult check = validate(
            updated.deviceAlias(),
            Integer.toString(updated.listeningPort()),
            updated.receiveDirectory(),
            updated.downloadDirectory()
        );
        if (!check.valid()) {
            return check;
        }

        AppSettings normalized = updated.toBuilder()
            .deviceAlias(updated.deviceAlias().trim())
            .receiveDirectory(updated.receiveDirectory().trim())
            .downloadDirectory(updated.downloadDirectory().trim())
            .build();

        try {
            repository.save(normalized);
            applySystemProperties(normalized);
            runOnFxThread(() -> {
                settings.set(normalized);
                lastPersistenceError.set("");
            });
            return ValidationResult.ok("Settings saved and applied.");
        } catch (IOException e) {
            String errorMsg = "Failed to save settings to disk: " + e.getMessage();
            runOnFxThread(() -> lastPersistenceError.set(errorMsg));
            return ValidationResult.error(errorMsg);
        }
    }

    /**
     * Asynchronously validates and persists settings, never blocking the JavaFX Application Thread.
     */
    public CompletableFuture<ValidationResult> updateSettingsAsync(AppSettings updated) {
        Objects.requireNonNull(updated, "updated");
        runOnFxThread(() -> saving.set(true));

        return CompletableFuture.supplyAsync(() -> updateSettings(updated), ioExecutor)
            .whenComplete((result, error) -> runOnFxThread(() -> saving.set(false)));
    }

    public ValidationResult updateDeviceAlias(String deviceAlias) {
        return updateSettings(getSettings().withDeviceAlias(deviceAlias));
    }

    public ValidationResult updateListeningPort(int port) {
        return updateSettings(getSettings().withListeningPort(port));
    }

    public ValidationResult updateReceiveDirectory(String receiveDirectory) {
        return updateSettings(getSettings().withReceiveDirectory(receiveDirectory));
    }

    public ValidationResult updateDownloadDirectory(String downloadDirectory) {
        return updateSettings(getSettings().withDownloadDirectory(downloadDirectory));
    }

    public ValidationResult updateStartupPreferences(boolean autoStartReceiver, boolean startMinimized) {
        AppSettings updated = getSettings().toBuilder()
            .autoStartReceiver(autoStartReceiver)
            .startMinimized(startMinimized)
            .build();
        return updateSettings(updated);
    }

    public ValidationResult updateNotificationPreferences(
        boolean enabled,
        boolean onTransferComplete,
        boolean onIncomingRequest,
        boolean soundEnabled
    ) {
        AppSettings updated = getSettings().toBuilder()
            .notificationsEnabled(enabled)
            .notifyOnTransferComplete(onTransferComplete)
            .notifyOnIncomingRequest(onIncomingRequest)
            .soundEnabled(soundEnabled)
            .build();
        return updateSettings(updated);
    }

    public ValidationResult updateSecurityPreferences(boolean requireApproval, boolean verifySha256) {
        AppSettings updated = getSettings().toBuilder()
            .requireApproval(requireApproval)
            .verifySha256(verifySha256)
            .build();
        return updateSettings(updated);
    }

    public ValidationResult updateTheme(String theme) {
        return updateSettings(getSettings().withTheme(theme));
    }

    /**
     * Reverts settings to SyncShare defaults and persists the changes.
     */
    public void resetDefaults() {
        AppSettings defaults = AppSettings.cleanDefaults();
        try {
            repository.save(defaults);
        } catch (IOException e) {
            runOnFxThread(() -> lastPersistenceError.set("Failed to persist default settings: " + e.getMessage()));
        }
        applySystemProperties(defaults);
        runOnFxThread(() -> {
            settings.set(defaults);
            lastPersistenceError.set("");
        });
    }

    public CompletableFuture<Void> resetDefaultsAsync() {
        runOnFxThread(() -> saving.set(true));
        return CompletableFuture.runAsync(this::resetDefaults, ioExecutor)
            .whenComplete((res, err) -> runOnFxThread(() -> saving.set(false)));
    }

    public boolean awaitPendingIo(long timeout, TimeUnit unit) throws InterruptedException {
        if (shutDown.get()) {
            return true;
        }
        CompletableFuture<Void> probe = new CompletableFuture<>();
        ioExecutor.submit(() -> probe.complete(null));
        try {
            probe.get(timeout, unit);
            return true;
        } catch (TimeoutException | ExecutionException e) {
            return false;
        }
    }

    public void shutdown() {
        if (!shutDown.compareAndSet(false, true)) {
            return;
        }
        ioExecutor.shutdown();
        try {
            if (!ioExecutor.awaitTermination(2, TimeUnit.SECONDS)) {
                ioExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            ioExecutor.shutdownNow();
        }
    }

    private static void applySystemProperties(AppSettings value) {
        if (value != null) {
            System.setProperty("syncshare.receiveDir", value.receiveDirectory());
            System.setProperty("syncshare.downloadDir", value.downloadDirectory());
        }
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
