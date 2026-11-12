package com.syncshare.events;

import java.nio.file.Path;

/**
 * Backend observer interface exposing real-time incoming connection,
 * offer, approval, progress, verification, and error events from FileReceiver.
 */
public interface IncomingTransferObserver {

    IncomingTransferObserver NO_OP = new IncomingTransferObserver() {
        @Override
        public void onConnectionOpened(String transferId, String senderAddress, String peerId) {
        }

        @Override
        public void onFileOffered(String transferId, String fileName, long fileSize, String senderAddress, String peerId) {
        }

        @Override
        public void onApprovalDecision(String transferId, String fileName, boolean accepted) {
        }

        @Override
        public void onStarted(String transferId, String fileName, long totalBytes, String senderAddress, String peerId) {
        }

        @Override
        public void onProgress(String transferId, String fileName, long transferredBytes, long totalBytes) {
        }

        @Override
        public void onCompleted(String transferId, String fileName, Path savedDestination) {
        }

        @Override
        public void onError(String transferId, String fileName, String senderAddress, Exception error) {
        }
    };

    void onConnectionOpened(String transferId, String senderAddress, String peerId);

    void onFileOffered(
        String transferId,
        String fileName,
        long fileSize,
        String senderAddress,
        String peerId
    );

    void onApprovalDecision(String transferId, String fileName, boolean accepted);

    void onStarted(
        String transferId,
        String fileName,
        long totalBytes,
        String senderAddress,
        String peerId
    );

    void onProgress(String transferId, String fileName, long transferredBytes, long totalBytes);

    void onCompleted(String transferId, String fileName, Path savedDestination);

    void onError(String transferId, String fileName, String senderAddress, Exception error);
}
