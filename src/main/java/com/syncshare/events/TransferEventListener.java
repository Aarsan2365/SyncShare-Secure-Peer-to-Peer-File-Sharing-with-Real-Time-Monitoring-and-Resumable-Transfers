package com.syncshare.events;

import com.syncshare.model.TransferDirection;

/**
 * Primary event listener contract capturing transfer lifecycle milestones,
 * byte progress, and terminal outcomes across all directions.
 */
public interface TransferEventListener {

    TransferEventListener NO_OP = new TransferEventListener() {
    };

    default void onConnecting(
        String transferId,
        TransferDirection direction,
        String fileName,
        long totalBytes,
        String peerAddress,
        String statusNote
    ) {
    }

    default void onStarted(
        String transferId,
        TransferDirection direction,
        String fileName,
        long totalBytes,
        long initialBytes,
        String peerAddress
    ) {
    }

    default void onProgress(
        String transferId,
        TransferDirection direction,
        String fileName,
        long transferredBytes,
        long totalBytes
    ) {
    }

    default void onCompleted(
        String transferId,
        TransferDirection direction,
        String fileName
    ) {
    }

    default void onFailed(
        String transferId,
        TransferDirection direction,
        String fileName,
        String reason,
        Exception error
    ) {
    }

    default void onCancelled(
        String transferId,
        TransferDirection direction,
        String fileName,
        String reason
    ) {
    }
}
