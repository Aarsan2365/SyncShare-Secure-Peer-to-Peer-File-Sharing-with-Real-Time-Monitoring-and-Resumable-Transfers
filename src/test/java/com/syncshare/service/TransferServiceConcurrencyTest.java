package com.syncshare.service;

import com.syncshare.model.TransferItem;
import com.syncshare.model.TransferStatus;
import com.syncshare.ui.JavaFxToolkitExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(JavaFxToolkitExtension.class)
class TransferServiceConcurrencyTest {

    @TempDir
    Path tempDir;

    @Test
    void limitsOutgoingWorkToThreeRunningAndEightQueuedTransfers() throws Exception {
        CountDownLatch threeWorkersStarted = new CountDownLatch(3);
        CountDownLatch releaseWorkers = new CountDownLatch(1);
        AtomicInteger running = new AtomicInteger();
        AtomicInteger maximumRunning = new AtomicInteger();

        TransferService service = new TransferService((host, port, file, listener) -> {
            int current = running.incrementAndGet();
            maximumRunning.accumulateAndGet(current, Math::max);
            threeWorkersStarted.countDown();
            try {
                if (!releaseWorkers.await(10, TimeUnit.SECONDS)) {
                    throw new IOException("Test workers were not released");
                }
                String name = file.getFileName().toString();
                listener.onStarted(name, Files.size(file));
                listener.onCompleted(name);
            } finally {
                running.decrementAndGet();
            }
        });

        try {
            List<CompletableFuture<TransferItem>> accepted = new ArrayList<>();

            // All three worker threads become occupied.
            for (int i = 0; i < 3; i++) {
                accepted.add(sendTestFile(service, i));
            }
            assertTrue(threeWorkersStarted.await(5, TimeUnit.SECONDS),
                "Three workers should start");

            // These eight submissions must wait in the bounded queue.
            for (int i = 3; i < 11; i++) {
                accepted.add(sendTestFile(service, i));
            }
            assertEquals(11L, accepted.stream().filter(f -> !f.isDone()).count());
            assertEquals(3, running.get());
            assertEquals(3, maximumRunning.get());

            // The twelfth submission must fail promptly, not queue forever.
            TransferItem rejected = sendTestFile(service, 11).get(5, TimeUnit.SECONDS);
            assertEquals(TransferStatus.FAILED, rejected.status());
            assertTrue(rejected.note().contains("outgoing transfer queue is full"),
                "The user-visible failure must explain why the transfer was rejected");
            assertFalse(service.hasActiveOutgoingTransfer("127.0.0.1", 5050,
                tempDir.resolve("send-11.txt")), "Rejected work must not remain active");

            // Releasing the workers lets all previously accepted tasks finish.
            releaseWorkers.countDown();
            for (CompletableFuture<TransferItem> future : accepted) {
                assertEquals(TransferStatus.COMPLETED,
                    future.get(5, TimeUnit.SECONDS).status());
            }
            assertEquals(3, maximumRunning.get(),
                "The service must never run more than three sends concurrently");
        } finally {
            releaseWorkers.countDown();
            service.shutdown();
        }
    }

    @Test
    void shutdownCancelsRunningAndQueuedTransfersAndCompletesEveryFuture() throws Exception {
        CountDownLatch workersStarted = new CountDownLatch(3);
        CountDownLatch keepWorkersBusy = new CountDownLatch(1);
        AtomicInteger transportStarts = new AtomicInteger();
        TransferService service = new TransferService((host, port, file, listener) -> {
            transportStarts.incrementAndGet();
            workersStarted.countDown();
            keepWorkersBusy.await();
        });

        try {
            List<CompletableFuture<TransferItem>> submissions = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                submissions.add(sendTestFile(service, i));
            }
            assertTrue(workersStarted.await(5, TimeUnit.SECONDS), "Three workers should start");

            service.shutdown();
            service.shutdown(); // Idempotent shutdown is part of the public lifecycle contract.

            for (CompletableFuture<TransferItem> submission : submissions) {
                assertEquals(TransferStatus.CANCELLED, submission.get(5, TimeUnit.SECONDS).status());
            }
            assertEquals(3, transportStarts.get(), "Queued transfer must never start after shutdown");
            assertTrue(service.getMonitoringService().getActiveSnapshot().isEmpty(),
                "Cancelled transfers must not remain active in monitoring");

            TransferItem afterShutdown = sendTestFile(service, 99).get(5, TimeUnit.SECONDS);
            assertEquals(TransferStatus.FAILED, afterShutdown.status());
            assertTrue(afterShutdown.note().contains("transfer service is shutting down"));
        } finally {
            keepWorkersBusy.countDown();
            service.shutdown();
        }
    }

    private CompletableFuture<TransferItem> sendTestFile(TransferService service, int index)
        throws IOException {
        Path file = tempDir.resolve("send-" + index + ".txt");
        Files.writeString(file, "concurrency test " + index);
        return service.sendFile("127.0.0.1", 5050, file);
    }
}
