package com.syncshare.service;

import com.syncshare.model.TransferItem;

import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;

/**
 * High-level client contract for initiating, executing, and cancelling outgoing transfers
 * across the SyncShare network layer.
 */
public interface TransferClient {

    /**
     * Initiates an asynchronous outgoing file transfer to a peer.
     *
     * @param peerHost Destination hostname or IPv4 address
     * @param port     Destination TCP port
     * @param file     Local file path to transfer
     * @return CompletableFuture completing with the final TransferItem state
     */
    CompletableFuture<TransferItem> sendFile(String peerHost, int port, Path file);

    /**
     * Executes an outgoing file transfer synchronously on the caller's background thread
     * (e.g. inside a JavaFX Task), reporting stage and chunk progress to the given observer.
     *
     * @param transferId        Unique transfer tracking identifier
     * @param peerHost          Destination hostname or IPv4 address
     * @param port              Destination TCP port
     * @param file              Local file path to transfer
     * @param observer          Observer receiving stage transitions and byte progress
     * @param cancelledSupplier Supplier returning true if transfer has been cancelled by caller
     * @return Final TransferItem record
     * @throws Exception If an unrecoverable network or protocol error occurs
     */
    TransferItem sendFileBlocking(
        String transferId,
        String peerHost,
        int port,
        Path file,
        OutgoingTransferGateway.ProgressObserver observer,
        BooleanSupplier cancelledSupplier
    ) throws Exception;

    /**
     * Cancels an active transfer by its transfer identifier.
     *
     * @param transferId Transfer identifier
     * @return true if the transfer was actively running and cancelled
     */
    boolean cancelActiveTransfer(String transferId);

    /**
     * Cancels an active transfer with control over worker thread interruption.
     *
     * @param transferId      Transfer identifier
     * @param interruptWorker Whether to interrupt the worker thread
     * @return true if the transfer was actively running and cancelled
     */
    boolean cancelActiveTransfer(String transferId, boolean interruptWorker);

    /**
     * Checks if there is currently an active outgoing transfer for the given endpoint and file.
     *
     * @param peerHost Target peer host
     * @param port     Target peer port
     * @param file     Local file path
     * @return true if actively transferring or queued
     */
    boolean hasActiveOutgoingTransfer(String peerHost, int port, Path file);
}
