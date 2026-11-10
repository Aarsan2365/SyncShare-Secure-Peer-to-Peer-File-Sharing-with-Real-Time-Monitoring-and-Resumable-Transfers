
package com.syncshare.events;

public class ConsoleTransferListener
        implements TransferListener {

    @Override
    public void onStarted(
        String fileName,
        long totalBytes
    ) {
        System.out.println(
            "\n[EVENT] Started: " + fileName
        );
        System.out.println(
            "[EVENT] Total size: " + totalBytes + " bytes"
        );
    }

    @Override
    public void onProgress(
        String fileName,
        long transferredBytes,
        long totalBytes
    ) {
        double percentage = totalBytes == 0
            ? 100.0
            : transferredBytes * 100.0 / totalBytes;

        System.out.printf(
            "\r[EVENT] %s: %.2f%%",
            fileName,
            percentage
        );
    }

    @Override
    public void onCompleted(String fileName) {
        System.out.println(
            "\n[EVENT] Completed: " + fileName
        );
    }

    @Override
    public void onError(
        String fileName,
        Exception error
    ) {
        System.out.println(
            "\n[EVENT] Failed: " + fileName
            + " | " + error.getMessage()
        );
    }
}
