
package com.syncshare.events;

public interface TransferListener {

    void onStarted(String fileName, long totalBytes);

    void onProgress(
        String fileName,
        long transferredBytes,
        long totalBytes
    );

    void onCompleted(String fileName);

    void onError(String fileName, Exception error);
}
