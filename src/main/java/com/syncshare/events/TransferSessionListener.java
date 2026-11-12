package com.syncshare.events;

import com.syncshare.model.TransferDirection;

/**
 * Backend session-level callback contract exposing real-time lifecycle,
 * connection, byte-progress, completion, failure, and cancellation events
 * from {@code FileSender}, {@code FileReceiver}, {@code TcpClient}, and {@code PeerServer}.
 */
public interface TransferSessionListener {

    TransferSessionListener NO_OP = new TransferSessionListener() {
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
