package com.syncshare.service;

import com.syncshare.model.AppSettings;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Safe JSON persistence implementation for {@link AppSettings}.
 *
 * <p>Guarantees:
 * <ul>
 *   <li>Missing files return defaults safely without modifying disk state.</li>
 *   <li>Corrupted files never wipe disk data silently during load.</li>
 *   <li>Writes use temp files, explicit channel flush, and atomic replacement,
 *       ensuring failed writes preserve previous valid settings intact.</li>
 *   <li>Thread-safe reads and writes protected by {@link ReentrantReadWriteLock}.</li>
 * </ul>
 */
public class JsonSettingsRepository implements SettingsRepository {

    public static final String SETTINGS_FILE_NAME = "settings.json";
    public static final String PROP_SETTINGS_FILE = "syncshare.settingsFile";
    public static final String PROP_DATA_DIR = "syncshare.dataDir";

    private final Path settingsFile;
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    public JsonSettingsRepository() {
        this(resolveDefaultSettingsFile());
    }

    public JsonSettingsRepository(Path settingsFile) {
        this.settingsFile = Objects.requireNonNull(settingsFile, "settingsFile").toAbsolutePath().normalize();
    }

    public static Path resolveDefaultSettingsFile() {
        String explicitFile = System.getProperty(PROP_SETTINGS_FILE);
        if (explicitFile != null && !explicitFile.isBlank()) {
            return Path.of(explicitFile.trim()).toAbsolutePath().normalize();
        }
        String explicitDir = System.getProperty(PROP_DATA_DIR);
        if (explicitDir != null && !explicitDir.isBlank()) {
            return Path.of(explicitDir.trim()).resolve(SETTINGS_FILE_NAME).toAbsolutePath().normalize();
        }
        return resolveAppDataDirectory().resolve(SETTINGS_FILE_NAME).toAbsolutePath().normalize();
    }

    public static Path resolveAppDataDirectory() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String userHome = System.getProperty("user.home", ".");
        if (os.contains("win")) {
            String localAppData = System.getenv("LOCALAPPDATA");
            if (localAppData != null && !localAppData.isBlank()) {
                return Path.of(localAppData, "SyncShare").toAbsolutePath().normalize();
            }
            String appData = System.getenv("APPDATA");
            if (appData != null && !appData.isBlank()) {
                return Path.of(appData, "SyncShare").toAbsolutePath().normalize();
            }
            return Path.of(userHome, "AppData", "Local", "SyncShare").toAbsolutePath().normalize();
        }
        if (os.contains("mac")) {
            return Path.of(userHome, "Library", "Application Support", "SyncShare").toAbsolutePath().normalize();
        }
        String xdgData = System.getenv("XDG_DATA_HOME");
        if (xdgData != null && !xdgData.isBlank()) {
            return Path.of(xdgData, "syncshare").toAbsolutePath().normalize();
        }
        return Path.of(userHome, ".local", "share", "syncshare").toAbsolutePath().normalize();
    }

    @Override
    public Path storageLocation() {
        return settingsFile;
    }

    @Override
    public LoadResult load() throws IOException {
        lock.readLock().lock();
        try {
            if (Files.notExists(settingsFile)) {
                return LoadResult.fallback(AppSettings.defaults(), "Settings file does not exist; using defaults.");
            }
            if (!Files.isRegularFile(settingsFile)) {
                return LoadResult.corrupted(
                    AppSettings.defaults(),
                    "Settings path is not a regular file: " + settingsFile
                );
            }
            String content = Files.readString(settingsFile, StandardCharsets.UTF_8);
            return SettingsJsonCodec.decode(content, AppSettings.defaults());
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public void save(AppSettings settings) throws IOException {
        Objects.requireNonNull(settings, "settings");
        String payload = SettingsJsonCodec.encode(settings);

        lock.writeLock().lock();
        try {
            Path parent = settingsFile.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Path dir = parent != null ? parent : Path.of(".").toAbsolutePath().normalize();
            String baseName = settingsFile.getFileName() != null
                ? settingsFile.getFileName().toString()
                : SETTINGS_FILE_NAME;

            Path tempFile = Files.createTempFile(dir, baseName + ".", ".tmp");
            try {
                writeAndSyncTempFile(tempFile, payload.getBytes(StandardCharsets.UTF_8));
                moveTempToTarget(tempFile, settingsFile);
            } finally {
                Files.deleteIfExists(tempFile);
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    protected void writeAndSyncTempFile(Path tempFile, byte[] bytes) throws IOException {
        try (FileChannel channel = FileChannel.open(
            tempFile,
            StandardOpenOption.WRITE,
            StandardOpenOption.TRUNCATE_EXISTING
        )) {
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) {
                channel.write(buffer);
            }
            channel.force(true);
        }
    }

    protected void moveTempToTarget(Path tempFile, Path targetFile) throws IOException {
        try {
            Files.move(
                tempFile,
                targetFile,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING
            );
        } catch (AtomicMoveNotSupportedException ex) {
            Files.move(tempFile, targetFile, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
