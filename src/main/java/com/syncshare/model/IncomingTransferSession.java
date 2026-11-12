package com.syncshare.model;

import com.syncshare.ui.design.SemanticTone;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Objects;

/**
 * Immutable snapshot of the current or most recent incoming transfer session
 * displayed on the Receive Files screen.
 */
public record IncomingTransferSession(
    String transferId,
    Stage stage,
    String fileName,
    long totalBytes,
    long transferredBytes,
    String senderAddress,
    String peerIdentity,
    Path savedPath,
    String statusTitle,
    String statusDetail,
    Instant updatedAt
) {

    public enum Stage {
        IDLE("Waiting", SemanticTone.NEUTRAL),
        CONNECTED("Incoming Connection", SemanticTone.INFO),
        AWAITING_APPROVAL("Pending Approval", SemanticTone.WARNING),
        RECEIVING("Receiving", SemanticTone.TEAL),
        COMPLETED("Verified & Saved", SemanticTone.SUCCESS),
        REJECTED("Rejected", SemanticTone.WARNING),
        FAILED("Failed", SemanticTone.ERROR);

        private final String displayName;
        private final SemanticTone tone;

        Stage(String displayName, SemanticTone tone) {
            this.displayName = displayName;
            this.tone = tone;
        }

        public String displayName() {
            return displayName;
        }

        public SemanticTone tone() {
            return tone;
        }
    }

    public IncomingTransferSession {
        transferId = transferId != null ? transferId : "";
        stage = Objects.requireNonNullElse(stage, Stage.IDLE);
        fileName = fileName != null && !fileName.isBlank() ? fileName : "Waiting for offer";
        totalBytes = Math.max(0L, totalBytes);
        transferredBytes = Math.max(0L, Math.min(transferredBytes, totalBytes > 0 ? totalBytes : transferredBytes));
        senderAddress = senderAddress != null && !senderAddress.isBlank() ? senderAddress : "Unknown sender";
        peerIdentity = peerIdentity != null && !peerIdentity.isBlank() ? peerIdentity : "Mutual TLS 1.3 peer";
        statusTitle = statusTitle != null ? statusTitle : stage.displayName();
        statusDetail = statusDetail != null ? statusDetail : "";
        updatedAt = updatedAt != null ? updatedAt : Instant.now();
    }

    public static IncomingTransferSession idle() {
        return new IncomingTransferSession(
            "",
            Stage.IDLE,
            "No incoming file",
            0L,
            0L,
            "No active peer",
            "",
            null,
            "No incoming connection",
            "Start the receiver to listen for Mutual TLS 1.3 file offers.",
            Instant.now()
        );
    }

    public double progress() {
        if (stage == Stage.COMPLETED) {
            return 1.0;
        }
        if (stage == Stage.CONNECTED || stage == Stage.AWAITING_APPROVAL) {
            return -1.0;
        }
        if (totalBytes <= 0L) {
            return stage == Stage.RECEIVING ? -1.0 : 0.0;
        }
        return Math.max(0.0, Math.min(1.0, (double) transferredBytes / (double) totalBytes));
    }

    public String formattedSize() {
        return TransferItem.formatByteCount(totalBytes);
    }

    public String formattedTransferred() {
        return TransferItem.formatByteCount(transferredBytes);
    }

    public String formattedProgressDetail() {
        if (stage == Stage.CONNECTED || stage == Stage.AWAITING_APPROVAL) {
            return statusDetail.isBlank() ? stage.displayName() : statusDetail;
        }
        if (stage == Stage.COMPLETED) {
            return TransferItem.formatByteCount(totalBytes) + " of " + TransferItem.formatByteCount(totalBytes) + " (100%)";
        }
        if (totalBytes <= 0L) {
            return stage.displayName();
        }
        int pct = (int) Math.round(Math.max(0.0, progress()) * 100.0);
        return TransferItem.formatByteCount(transferredBytes)
            + " of "
            + TransferItem.formatByteCount(totalBytes)
            + " (" + pct + "%)";
    }
}
