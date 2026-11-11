package com.syncshare.model;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Objects;

/**
 * Model representing an active or completed peer file transfer.
 */
public record TransferItem(
    String id,
    String fileName,
    long totalBytes,
    long transferredBytes,
    TransferDirection direction,
    TransferStatus status,
    String peerAddress,
    String sha256Preview,
    Instant updatedAt,
    String note
) {
    private static final DateTimeFormatter TIME_FORMAT =
        DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());

    public TransferItem {
        id = Objects.requireNonNull(id, "id");
        fileName = fileName != null && !fileName.isBlank() ? fileName : "unknown";
        totalBytes = Math.max(0L, totalBytes);
        transferredBytes = Math.max(0L, Math.min(transferredBytes, totalBytes > 0 ? totalBytes : transferredBytes));
        direction = Objects.requireNonNullElse(direction, TransferDirection.OUTGOING);
        status = Objects.requireNonNullElse(status, TransferStatus.QUEUED);
        peerAddress = peerAddress != null && !peerAddress.isBlank() ? peerAddress : "local-peer";
        sha256Preview = sha256Preview != null ? sha256Preview : "Pending SHA-256";
        updatedAt = updatedAt != null ? updatedAt : Instant.now();
        note = note != null ? note : "";
    }

    public double progress() {
        if (status == TransferStatus.COMPLETED) {
            return 1.0;
        }
        if (totalBytes <= 0) {
            return status == TransferStatus.TRANSFERRING ? -1.0 : 0.0;
        }
        return Math.max(0.0, Math.min(1.0, (double) transferredBytes / (double) totalBytes));
    }

    public String formattedSize() {
        return formatByteCount(totalBytes);
    }

    public String formattedTransferred() {
        return formatByteCount(transferredBytes);
    }

    public String formattedProgressDetail() {
        if (totalBytes <= 0) {
            return status.displayName();
        }
        int pct = (int) Math.round(Math.max(0.0, progress()) * 100.0);
        return formatByteCount(transferredBytes) + " of " + formatByteCount(totalBytes) + " (" + pct + "%)";
    }

    public String formattedTimestamp() {
        return TIME_FORMAT.format(updatedAt);
    }

    public static String formatByteCount(long bytes) {
        if (bytes < 1024L) {
            return bytes + " B";
        }
        double kb = bytes / 1024.0;
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
}
