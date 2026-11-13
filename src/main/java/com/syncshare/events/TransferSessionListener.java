package com.syncshare.events;

/**
 * Backend session-level callback contract exposing real-time lifecycle,
 * connection, byte-progress, completion, failure, and cancellation events
 * from {@code FileSender}, {@code FileReceiver}, {@code TcpClient}, and {@code PeerServer}.
 * Extends {@link TransferEventListener}.
 */
public interface TransferSessionListener extends TransferEventListener {

    TransferSessionListener NO_OP = new TransferSessionListener() {
    };
}
