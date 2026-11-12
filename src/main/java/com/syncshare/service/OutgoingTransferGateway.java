package com.syncshare.service;

import com.syncshare.model.TransferItem;
import com.syncshare.model.TransferStatus;

import java.nio.file.Path;
import java.util.function.BooleanSupplier;

/**
 * Clean integration boundary between JavaFX UI tasks/controllers and the
 * SyncShare networking layer, decoupling UI code from socket and TLS APIs.
 */
public interface OutgoingTransferGateway {

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

    /**
     * Executes an outgoing file transfer synchronously on the caller's background thread
     * (for example, inside a JavaFX {@link javafx.concurrent.Task}), updating active and
     * historical transfer state safely across threads.
     */
    TransferItem sendFileBlocking(
        String transferId,
        String peerHost,
        int port,
        Path file,
        ProgressObserver observer,
        BooleanSupplier cancelledSupplier
    ) throws Exception;

    boolean cancelActiveTransfer(String transferId, boolean interruptWorker);

    default boolean cancelActiveTransfer(String transferId) {
        return cancelActiveTransfer(transferId, true);
    }

    boolean hasActiveOutgoingTransfer(String peerHost, int port, Path file);
}
