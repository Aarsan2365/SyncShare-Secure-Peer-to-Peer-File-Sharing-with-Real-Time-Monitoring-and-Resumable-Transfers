package com.syncshare.monitoring;

import com.syncshare.model.TransferItem;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.OptionalDouble;

/**
 * Pure calculation and formatting utilities for transfer progress, throughput,
 * estimated remaining time (ETA), and timestamps.
 */
public final class TransferMetricsCalculator {

    public static final long UNKNOWN_SIZE = -1L;
    public static final double INDETERMINATE_PROGRESS = -1.0;
    public static final double UNAVAILABLE_SPEED = -1.0;
    public static final long UNAVAILABLE_ETA = -1L;

    private static final DateTimeFormatter TIME_FORMATTER =
        DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());

    private TransferMetricsCalculator() {
    }

    /**
     * Clamps transferred bytes to {@code [0, totalBytes]} when {@code totalBytes >= 0},
     * or {@code [0, Long.MAX_VALUE]} when total size is unknown ({@code totalBytes < 0}).
     */
    public static long clampTransferredBytes(long transferredBytes, long totalBytes) {
        long nonNegative = Math.max(0L, transferredBytes);
        if (totalBytes >= 0L) {
            return Math.min(nonNegative, totalBytes);
        }
        return nonNegative;
    }

    /**
     * Calculates normalized progress in {@code [0.0, 1.0]}, or {@link #INDETERMINATE_PROGRESS}
     * ({@code -1.0}) when total size is unknown for an active connecting/running transfer.
     *
     * <p>Zero-byte files evaluate to {@code 1.0} only once {@link TransferMonitorState#COMPLETED}
     * is reached, and {@code 0.0} prior to backend verification.
     */
    public static double calculateProgress(
        long transferredBytes,
        long totalBytes,
        TransferMonitorState state
    ) {
        if (state == TransferMonitorState.COMPLETED) {
            return 1.0;
        }
        if (totalBytes < 0L) {
            return (state == TransferMonitorState.RUNNING || state == TransferMonitorState.CONNECTING)
                ? INDETERMINATE_PROGRESS
                : 0.0;
        }
        if (totalBytes == 0L) {
            return 0.0;
        }
        long clamped = clampTransferredBytes(transferredBytes, totalBytes);
        return Math.max(0.0, Math.min(1.0, (double) clamped / (double) totalBytes));
    }

    /**
     * Calculates progress percentage in {@code [0.0, 100.0]}, or empty if indeterminate.
     */
    public static OptionalDouble calculatePercentage(
        long transferredBytes,
        long totalBytes,
        TransferMonitorState state
    ) {
        double progress = calculateProgress(transferredBytes, totalBytes, state);
        if (progress < 0.0) {
            return OptionalDouble.empty();
        }
        return OptionalDouble.of(progress * 100.0);
    }

    /**
     * Calculates estimated remaining seconds using actual remaining bytes and a valid
     * positive speed estimate. Never invents ETA values when speed is zero, unavailable,
     * non-finite, or total size is unknown.
     */
    public static long calculateEtaSeconds(
        long transferredBytes,
        long totalBytes,
        double bytesPerSecond,
        TransferMonitorState state
    ) {
        if (state == TransferMonitorState.COMPLETED) {
            return 0L;
        }
        if (state != TransferMonitorState.RUNNING) {
            return UNAVAILABLE_ETA;
        }
        if (totalBytes <= 0L) {
            return UNAVAILABLE_ETA;
        }
        if (!Double.isFinite(bytesPerSecond) || bytesPerSecond <= 0.0) {
            return UNAVAILABLE_ETA;
        }
        long clamped = clampTransferredBytes(transferredBytes, totalBytes);
        long remaining = Math.max(0L, totalBytes - clamped);
        if (remaining == 0L) {
            return 0L;
        }
        double rawSeconds = remaining / bytesPerSecond;
        if (!Double.isFinite(rawSeconds) || rawSeconds < 0.0) {
            return UNAVAILABLE_ETA;
        }
        if (rawSeconds > Long.MAX_VALUE / 2.0) {
            return UNAVAILABLE_ETA;
        }
        return Math.max(1L, Math.round(rawSeconds));
    }

    public static String formatSize(long totalBytes) {
        if (totalBytes < 0L) {
            return "Unknown";
        }
        return TransferItem.formatByteCount(totalBytes);
    }

    public static String formatTransferred(long transferredBytes, long totalBytes) {
        long clamped = clampTransferredBytes(transferredBytes, totalBytes);
        if (totalBytes < 0L) {
            return TransferItem.formatByteCount(clamped) + " / Unknown";
        }
        return TransferItem.formatByteCount(clamped) + " / " + TransferItem.formatByteCount(totalBytes);
    }

    public static String formatPercentage(double progress) {
        if (progress < 0.0 || !Double.isFinite(progress)) {
            return "--";
        }
        long pct = Math.round(Math.max(0.0, Math.min(1.0, progress)) * 100.0);
        return pct + "%";
    }

    public static String formatSpeed(double bytesPerSecond, TransferMonitorState state) {
        if (state == null || state.isTerminal() || state == TransferMonitorState.QUEUED || state == TransferMonitorState.CONNECTING) {
            return "--";
        }
        if (!Double.isFinite(bytesPerSecond) || bytesPerSecond < 0.0) {
            return "Calculating...";
        }
        if (bytesPerSecond == 0.0) {
            return "0 B/s";
        }
        if (bytesPerSecond < 1024.0) {
            return String.format(Locale.US, "%.0f B/s", bytesPerSecond);
        }
        double kb = bytesPerSecond / 1024.0;
        if (kb < 1024.0) {
            return String.format(Locale.US, "%.1f KB/s", kb);
        }
        double mb = kb / 1024.0;
        if (mb < 1024.0) {
            return String.format(Locale.US, "%.1f MB/s", mb);
        }
        double gb = mb / 1024.0;
        return String.format(Locale.US, "%.2f GB/s", gb);
    }

    public static String formatEta(long etaSeconds, TransferMonitorState state) {
        if (state == TransferMonitorState.COMPLETED) {
            return "Done";
        }
        if (state != TransferMonitorState.RUNNING || etaSeconds < 0L) {
            return "--";
        }
        if (etaSeconds == 0L) {
            return "< 1s";
        }
        long hours = etaSeconds / 3600L;
        long minutes = (etaSeconds % 3600L) / 60L;
        long seconds = etaSeconds % 60L;
        if (hours > 0L) {
            return String.format(Locale.US, "%dh %dm %ds", hours, minutes, seconds);
        }
        if (minutes > 0L) {
            return String.format(Locale.US, "%dm %ds", minutes, seconds);
        }
        return String.format(Locale.US, "%ds", seconds);
    }

    public static String formatTimestamp(Instant timestamp) {
        if (timestamp == null) {
            return "--";
        }
        return TIME_FORMATTER.format(timestamp);
    }
}
