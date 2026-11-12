package com.syncshare.history;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Safe JSON persistence implementation for {@link TransferRecord} history.
 *
 * <p>Guarantees:
 * <ul>
 *   <li>Missing files return an empty result without modifying disk state.</li>
 *   <li>Corrupted JSON files never overwrite existing disk data silently.</li>
 *   <li>Partially invalid records are skipped with warnings while valid records load.</li>
 *   <li>Writes use temp files, explicit channel force/flush, and atomic replacement
 *       with fallback so failed writes preserve the previous valid file.</li>
 * </ul>
 */
public class JsonTransferHistoryRepository implements TransferHistoryRepository {

    public static final String HISTORY_FILE_NAME = "transfer-history.json";
    public static final String PROP_HISTORY_FILE = "syncshare.historyFile";
    public static final String PROP_DATA_DIR = "syncshare.dataDir";

    private final Path historyFile;
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    public JsonTransferHistoryRepository() {
        this(resolveDefaultHistoryFile());
    }

    public JsonTransferHistoryRepository(Path historyFile) {
        this.historyFile = Objects.requireNonNull(historyFile, "historyFile").toAbsolutePath().normalize();
    }

    public static Path resolveDefaultHistoryFile() {
        String explicitFile = System.getProperty(PROP_HISTORY_FILE);
        if (explicitFile != null && !explicitFile.isBlank()) {
            return Path.of(explicitFile.trim()).toAbsolutePath().normalize();
        }
        String explicitDir = System.getProperty(PROP_DATA_DIR);
        if (explicitDir != null && !explicitDir.isBlank()) {
            return Path.of(explicitDir.trim()).resolve(HISTORY_FILE_NAME).toAbsolutePath().normalize();
        }
        return resolveAppDataDirectory().resolve(HISTORY_FILE_NAME).toAbsolutePath().normalize();
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
        return historyFile;
    }

    @Override
    public LoadResult load() throws IOException {
        lock.readLock().lock();
        try {
            if (Files.notExists(historyFile)) {
                return LoadResult.empty();
            }
            if (!Files.isRegularFile(historyFile)) {
                return LoadResult.corrupted("History path is not a regular file: " + historyFile.getFileName());
            }
            String content = Files.readString(historyFile, StandardCharsets.UTF_8);
            return HistoryJsonCodec.decode(content);
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public void save(List<TransferRecord> records) throws IOException {
        Objects.requireNonNull(records, "records");
        String payload = HistoryJsonCodec.encode(records, Instant.now());

        lock.writeLock().lock();
        try {
            Path parent = historyFile.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Path dir = parent != null ? parent : Path.of(".").toAbsolutePath().normalize();
            String baseName = historyFile.getFileName() != null
                ? historyFile.getFileName().toString()
                : HISTORY_FILE_NAME;

            Path tempFile = Files.createTempFile(dir, baseName + ".", ".tmp");
            try {
                writeAndSyncTempFile(tempFile, payload.getBytes(StandardCharsets.UTF_8));
                moveTempToTarget(tempFile, historyFile);
            } finally {
                Files.deleteIfExists(tempFile);
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public void clear() throws IOException {
        save(List.of());
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
