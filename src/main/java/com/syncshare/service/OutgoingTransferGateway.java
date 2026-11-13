package com.syncshare.service;

import com.syncshare.model.TransferItem;
import com.syncshare.model.TransferStatus;

import java.nio.file.Path;
import java.util.function.BooleanSupplier;

/**
 * Clean integration boundary between JavaFX UI tasks/controllers and the
 * SyncShare networking layer, decoupling UI code from socket and TLS APIs.
 * Extends the high-level {@link TransferClient} contract.
 */
public interface OutgoingTransferGateway extends TransferClient {

    /**
     * Observer notified about real backend lifecycle stages and chunk byte progress.
     */
    interface ProgressObserver {
        void onStageChanged(TransferStatus status, String headline, String detail);

        void onProgress(String fileName, long transferredBytes, long totalBytes);
    }

    TransferService.ValidationResult validateSendInput(
        String peerHost,
        String portText,
        String filePath
    );

    TransferService.FileSummary inspectSelectedFile(String filePath);

    @Override
    TransferItem sendFileBlocking(
        String transferId,
        String peerHost,
        int port,
        Path file,
        ProgressObserver observer,
        BooleanSupplier cancelledSupplier
    ) throws Exception;

    @Override
    boolean cancelActiveTransfer(String transferId, boolean interruptWorker);

    @Override
    default boolean cancelActiveTransfer(String transferId) {
        return cancelActiveTransfer(transferId, true);
    }

    @Override
    boolean hasActiveOutgoingTransfer(String peerHost, int port, Path file);
}
