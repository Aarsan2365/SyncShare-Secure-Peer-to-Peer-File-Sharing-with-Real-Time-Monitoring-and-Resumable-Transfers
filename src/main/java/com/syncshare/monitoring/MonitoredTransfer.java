package com.syncshare.monitoring;

import com.syncshare.model.TransferDirection;

import java.time.Instant;
import java.util.Objects;

/**
 * Immutable snapshot of a monitored transfer containing real byte progress,
 * speed, ETA, validated lifecycle state, timestamps, and error metadata.
 */
public record MonitoredTransfer(
    String transferId,
    String fileName,
    TransferDirection direction,
    String peerAddress,
    long totalBytes,
    long transferredBytes,
    double progress,
    double speedBytesPerSecond,
    long estimatedRemainingSeconds,
    TransferMonitorState state,
    Instant startedAt,
    Instant completedAt,
    Instant updatedAt,
    String errorMessage,
    String statusNote
) {

    public MonitoredTransfer {
        transferId = Objects.requireNonNull(transferId, "transferId");
        if (transferId.isBlank()) {
            throw new IllegalArgumentException("transferId must not be blank");
        }
        fileName = fileName != null && !fileName.isBlank() ? fileName : "unknown";
        direction = Objects.requireNonNullElse(direction, TransferDirection.OUTGOING);
        peerAddress = peerAddress != null && !peerAddress.isBlank() ? peerAddress : "unknown-peer";
        totalBytes = totalBytes < 0L ? TransferMetricsCalculator.UNKNOWN_SIZE : totalBytes;
        state = Objects.requireNonNullElse(state, TransferMonitorState.QUEUED);
        transferredBytes = TransferMetricsCalculator.clampTransferredBytes(transferredBytes, totalBytes);
        progress = TransferMetricsCalculator.calculateProgress(transferredBytes, totalBytes, state);
        if (!Double.isFinite(speedBytesPerSecond) || speedBytesPerSecond < 0.0) {
            speedBytesPerSecond = TransferMetricsCalculator.UNAVAILABLE_SPEED;
        }
        estimatedRemainingSeconds = TransferMetricsCalculator.calculateEtaSeconds(
            transferredBytes,
            totalBytes,
            speedBytesPerSecond,
            state
        );
        Instant now = Instant.now();
        startedAt = startedAt != null ? startedAt : now;
        updatedAt = updatedAt != null ? updatedAt : now;
        if (state.isTerminal() && completedAt == null) {
            completedAt = updatedAt;
        }
        errorMessage = errorMessage != null ? errorMessage : "";
        statusNote = statusNote != null ? statusNote : "";
    }

    public String formattedTotalSize() {
        return TransferMetricsCalculator.formatSize(totalBytes);
    }

    public String formattedTransferred() {
        return TransferMetricsCalculator.formatTransferred(transferredBytes, totalBytes);
    }

    public String formattedPercentage() {
        return TransferMetricsCalculator.formatPercentage(progress);
    }

    public String formattedSpeed() {
        return TransferMetricsCalculator.formatSpeed(speedBytesPerSecond, state);
    }

    public String formattedEta() {
        return TransferMetricsCalculator.formatEta(estimatedRemainingSeconds, state);
    }

    public String formattedStartedAt() {
        return TransferMetricsCalculator.formatTimestamp(startedAt);
    }

    public String formattedCompletedAt() {
        return TransferMetricsCalculator.formatTimestamp(completedAt);
    }
}
