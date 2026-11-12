package com.syncshare.history;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Immutable validated record of a terminal peer-to-peer file transfer.
 *
 * <p>Stores only non-sensitive metadata suitable for persistent local history
 * (never passwords, private keys, authentication tokens, file contents, or
 * unnecessary full local filesystem paths).
 */
public record TransferRecord(
    String transferId,
    String fileName,
    long fileSize,
    TransferHistoryDirection direction,
    String peerInfo,
    Instant transferredAt,
    Duration duration,
    TransferHistoryStatus status,
    String destination,
    String summaryNote
) {

    public static final long UNKNOWN_SIZE = -1L;
    private static final int MAX_NOTE_LENGTH = 240;
    private static final int MAX_ID_LENGTH = 128;
    private static final int MAX_FIELD_LENGTH = 256;

    private static final DateTimeFormatter DATE_TIME_FORMATTER =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

    private static final Pattern WINDOWS_DRIVE_PREFIX = Pattern.compile("^[A-Za-z]:[\\\\/].*");
    private static final Pattern UNC_PREFIX = Pattern.compile("^\\\\\\\\[^\\\\]+\\\\.*");
    private static final Pattern SENSITIVE_KEY_PATTERN = Pattern.compile(
        "(?i)(password|passphrase|secret|private\\s*key|auth\\s*token|bearer\\s+[a-z0-9._-]+)"
    );

    public TransferRecord {
        transferId = requireNonBlank(transferId, "transferId");
        if (transferId.length() > MAX_ID_LENGTH || containsControlChars(transferId)) {
            throw new IllegalArgumentException("transferId contains invalid characters or exceeds max length");
        }

        fileName = sanitizeFileName(fileName);

        if (fileSize < UNKNOWN_SIZE) {
            throw new IllegalArgumentException("fileSize must be >= -1 (was " + fileSize + ")");
        }

        direction = Objects.requireNonNull(direction, "direction must not be null");

        peerInfo = sanitizeText(requireNonBlank(peerInfo, "peerInfo"), MAX_FIELD_LENGTH);
        if (peerInfo.isEmpty()) {
            throw new IllegalArgumentException("peerInfo must not be blank");
        }

        transferredAt = Objects.requireNonNull(transferredAt, "transferredAt must not be null");

        duration = Objects.requireNonNull(duration, "duration must not be null");
        if (duration.isNegative()) {
            throw new IllegalArgumentException("duration must not be negative");
        }

        status = Objects.requireNonNull(status, "status must not be null");

        destination = sanitizeDestination(destination);
        summaryNote = sanitizeNote(summaryNote);
    }

    public TransferRecord(
        String transferId,
        String fileName,
        long fileSize,
        TransferHistoryDirection direction,
        String peerInfo,
        Instant transferredAt,
        Duration duration,
        TransferHistoryStatus status,
        String destination
    ) {
        this(
            transferId,
            fileName,
            fileSize,
            direction,
            peerInfo,
            transferredAt,
            duration,
            status,
            destination,
            ""
        );
    }

    public boolean hasDestination() {
        return !destination.isEmpty();
    }

    public String formattedSize() {
        if (fileSize == UNKNOWN_SIZE) {
            return "Unknown";
        }
        if (fileSize < 1024L) {
            return fileSize + " B";
        }
        double kb = fileSize / 1024.0;
        if (kb < 1024.0) {
            return String.format(Locale.US, "%.1f KB", kb);
        }
        double mb = kb / 1024.0;
        if (mb < 1024.0) {
            return String.format(Locale.US, "%.1f MB", mb);
        }
        double gb = mb / 1024.0;
        return String.format(Locale.US, "%.2f GB", gb);
    }

    public String formattedDateTime() {
        return DATE_TIME_FORMATTER.format(transferredAt);
    }

    public String formattedDuration() {
        long millis = duration.toMillis();
        if (millis < 1000L) {
            return millis == 0L ? "< 1s" : millis + " ms";
        }
        long totalSeconds = duration.getSeconds();
        long hours = totalSeconds / 3600L;
        long minutes = (totalSeconds % 3600L) / 60L;
        long seconds = totalSeconds % 60L;
        if (hours > 0L) {
            return String.format(Locale.US, "%dh %dm %ds", hours, minutes, seconds);
        }
        if (minutes > 0L) {
            return String.format(Locale.US, "%dm %ds", minutes, seconds);
        }
        return String.format(Locale.US, "%ds", seconds);
    }

    public String formattedDestination() {
        return destination.isEmpty() ? "—" : destination;
    }

    public static String sanitizeFileName(String rawName) {
        String checked = requireNonBlank(rawName, "fileName");
        if (containsControlChars(checked)) {
            throw new IllegalArgumentException("fileName must not contain control characters");
        }
        String normalized = checked.replace('\\', '/');
        int lastSlash = normalized.lastIndexOf('/');
        String base = lastSlash >= 0 ? normalized.substring(lastSlash + 1) : normalized;
        base = base.trim();
        if (base.isEmpty() || ".".equals(base) || "..".equals(base)) {
            throw new IllegalArgumentException("fileName must resolve to a valid file name");
        }
        if (base.length() > MAX_FIELD_LENGTH) {
            base = base.substring(0, MAX_FIELD_LENGTH);
        }
        return base;
    }

    /**
     * Converts raw destination strings into a safe display label without exposing
     * full local filesystem paths (such as user home directories).
     */
    public static String sanitizeDestination(String rawDestination) {
        if (rawDestination == null || rawDestination.isBlank()) {
            return "";
        }
        String trimmed = stripControlChars(rawDestination).trim();
        if (trimmed.isEmpty()) {
            return "";
        }
        boolean looksLikeAbsolutePath = trimmed.startsWith("/")
            || trimmed.startsWith("\\")
            || WINDOWS_DRIVE_PREFIX.matcher(trimmed).matches()
            || UNC_PREFIX.matcher(trimmed).matches();

        if (looksLikeAbsolutePath) {
            try {
                Path path = Path.of(trimmed);
                Path fileName = path.getFileName();
                if (fileName != null && !fileName.toString().isBlank()) {
                    trimmed = fileName.toString().trim();
                } else {
                    String normalized = trimmed.replace('\\', '/').replaceAll("/+$", "");
                    int idx = normalized.lastIndexOf('/');
                    trimmed = idx >= 0 && idx + 1 < normalized.length()
                        ? normalized.substring(idx + 1)
                        : "Receive folder";
                }
            } catch (Exception ignored) {
                String normalized = trimmed.replace('\\', '/').replaceAll("/+$", "");
                int idx = normalized.lastIndexOf('/');
                trimmed = idx >= 0 && idx + 1 < normalized.length()
                    ? normalized.substring(idx + 1)
                    : "Receive folder";
            }
        }
        if (trimmed.length() > MAX_FIELD_LENGTH) {
            trimmed = trimmed.substring(0, MAX_FIELD_LENGTH);
        }
        return trimmed;
    }

    public static String sanitizeNote(String rawNote) {
        if (rawNote == null || rawNote.isBlank()) {
            return "";
        }
        String cleaned = stripControlChars(rawNote).trim();
        if (SENSITIVE_KEY_PATTERN.matcher(cleaned).find()) {
            cleaned = "Sensitive details redacted";
        }
        if (cleaned.length() > MAX_NOTE_LENGTH) {
            cleaned = cleaned.substring(0, MAX_NOTE_LENGTH - 3) + "...";
        }
        return cleaned;
    }

    private static String sanitizeText(String raw, int maxLen) {
        String cleaned = stripControlChars(raw).trim();
        if (SENSITIVE_KEY_PATTERN.matcher(cleaned).find()) {
            throw new IllegalArgumentException("Field must not contain sensitive credentials");
        }
        if (cleaned.length() > maxLen) {
            return cleaned.substring(0, maxLen);
        }
        return cleaned;
    }

    private static String requireNonBlank(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be null or blank");
        }
        return value.trim();
    }

    private static boolean containsControlChars(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < 0x20 || c == 0x7F) {
                return true;
            }
        }
        return false;
    }

    private static String stripControlChars(String value) {
        StringBuilder sb = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c >= 0x20 && c != 0x7F) {
                sb.append(c);
            } else if (c == '\n' || c == '\r' || c == '\t') {
                sb.append(' ');
            }
        }
        return sb.toString();
    }
}
