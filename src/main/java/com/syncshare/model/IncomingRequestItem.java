package com.syncshare.model;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Objects;

/**
 * Represents a pending incoming file offer awaiting local user approval.
 */
public record IncomingRequestItem(
    int requestId,
    String fileName,
    long fileSize,
    String senderAddress,
    Instant receivedAt
) {
    private static final DateTimeFormatter TIME_FORMAT =
        DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());

    public IncomingRequestItem {
        fileName = fileName != null && !fileName.isBlank() ? fileName : "unknown";
        fileSize = Math.max(0L, fileSize);
        senderAddress = senderAddress != null ? senderAddress : "unknown";
        receivedAt = Objects.requireNonNullElseGet(receivedAt, Instant::now);
    }

    public String formattedSize() {
        return TransferItem.formatByteCount(fileSize);
    }

    public String formattedTime() {
        return TIME_FORMAT.format(receivedAt);
    }
}
