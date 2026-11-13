package com.syncshare.service;

import com.syncshare.model.TransferDirection;
import com.syncshare.monitoring.MonitoredTransfer;
import com.syncshare.monitoring.TransferMonitorListener;
import com.syncshare.monitoring.TransferMonitorState;
import com.syncshare.ui.JavaFxToolkitExtension;
import javafx.application.Platform;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(JavaFxToolkitExtension.class)
class TransferMonitoringServiceTest {

    @Test
    void concurrentUpdatesAcrossMultipleTransfersRemainThreadSafeAndConsistent() throws Throwable {
        AtomicLong clock = new AtomicLong(1_000_000_000L);
        TransferMonitoringService service = new TransferMonitoringService(clock::get, 0L, false);

        int transferCount = 8;
        int stepsPerTransfer = 50;
        long bytesPerStep = 1024L;
        long totalBytes = stepsPerTransfer * bytesPerStep;

        ExecutorService pool = Executors.newFixedThreadPool(transferCount);
        CountDownLatch startGate = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();

        try {
            for (int i = 0; i < transferCount; i++) {
                final String id = "concurrent-tx-" + i;
                final TransferDirection direction = (i % 2 == 0)
                    ? TransferDirection.OUTGOING
                    : TransferDirection.INCOMING;
                futures.add(pool.submit(() -> {
                    startGate.await();
                    service.registerQueued(id, direction, "file-" + id + ".bin", totalBytes, "10.0.0." + id.charAt(id.length() - 1), "Queued");
                    service.onConnecting(id, direction, "file-" + id + ".bin", totalBytes, "10.0.0.2:5050", "Connecting");
                    service.onStarted(id, direction, "file-" + id + ".bin", totalBytes, 0L, "10.0.0.2:5050");
                    for (int step = 1; step <= stepsPerTransfer; step++) {
                        clock.addAndGet(5_000_000L);
                        service.onProgress(id, direction, "file-" + id + ".bin", step * bytesPerStep, totalBytes);
                    }
                    service.onCompleted(id, direction, "file-" + id + ".bin");
                    return null;
                }));
            }

            startGate.countDown();
            for (Future<?> f : futures) {
                f.get(10, TimeUnit.SECONDS);
            }

            // Wait for any pending FX thread update to drain
            JavaFxToolkitExtension.runOnFxThread(() -> {
                assertEquals(0, service.getActiveTransfers().size());
                assertEquals(transferCount, service.getRecentTransfers().size());
                assertEquals(transferCount, service.completedCountProperty().get());
                assertEquals(0, service.failedCountProperty().get());
                assertEquals(transferCount * totalBytes, service.totalTransferredBytesProperty().get());
                for (MonitoredTransfer item : service.getRecentTransfers()) {
                    assertEquals(TransferMonitorState.COMPLETED, item.state());
                    assertEquals(1.0, item.progress(), 1e-9);
                    assertNotNull(item.startedAt());
                    assertNotNull(item.completedAt());
                }
            });
        } finally {
            pool.shutdownNow();
            service.shutdown();
        }
    }

    @Test
    void preventsDuplicateSubscriptionsAndCleansUpListenersAndCancelHandlers() throws Throwable {
        TransferMonitoringService service = new TransferMonitoringService(System::nanoTime, 0L, false);
        AtomicInteger callCount = new AtomicInteger(0);
        AtomicBoolean fxThreadVerified = new AtomicBoolean(true);

        TransferMonitorListener listener = (active, recent) -> {
            callCount.incrementAndGet();
            if (!Platform.isFxApplicationThread()) {
                fxThreadVerified.set(false);
            }
        };

        try {
            assertTrue(service.addListener(listener));
            assertFalse(service.addListener(listener), "Duplicate listener subscription must be rejected");
            assertEquals(1, service.listenerCount());

            service.registerQueued("tx-sub", TransferDirection.OUTGOING, "notes.txt", 100L, "127.0.0.1:5050", "Queued");
            JavaFxToolkitExtension.runOnFxThread(() -> {});
            int afterFirst = callCount.get();
            assertTrue(afterFirst >= 1);
            assertTrue(fxThreadVerified.get(), "Listener notifications tied to FX state must run on FX thread");

            assertTrue(service.removeListener(listener));
            assertFalse(service.removeListener(listener));
            assertEquals(0, service.listenerCount());

            service.onCompleted("tx-sub", TransferDirection.OUTGOING, "notes.txt");
            JavaFxToolkitExtension.runOnFxThread(() -> {});
            assertEquals(afterFirst, callCount.get(), "Removed listener must not receive further updates");

            // Test AutoCloseable subscription & cancel handler cleanup
            AtomicInteger subEvents = new AtomicInteger(0);
            try (AutoCloseable sub = service.subscribe((a, r) -> subEvents.incrementAndGet())) {
                assertNotNull(sub, "Subscription handle must not be null");
                assertEquals(1, service.listenerCount());
                service.registerQueued("tx-cancel", TransferDirection.INCOMING, "clip.mp4", 4096L, "10.0.0.5", "Queued");
                JavaFxToolkitExtension.runOnFxThread(() -> {});
                assertTrue(subEvents.get() >= 1);
            }
            assertEquals(0, service.listenerCount());

            AtomicBoolean cancelInvokedOnCompleted = new AtomicBoolean(false);
            service.registerQueued("tx-done-handler", TransferDirection.OUTGOING, "a.bin", 128L, "10.0.0.8:5050", "Queued");
            service.registerCancelHandler("tx-done-handler", () -> cancelInvokedOnCompleted.set(true));
            service.onCompleted("tx-done-handler", TransferDirection.OUTGOING, "a.bin");
            assertFalse(service.cancelTransfer("tx-done-handler"));
            assertFalse(cancelInvokedOnCompleted.get(), "Cancel handler must be released once transfer completes");

            AtomicBoolean cancelInvokedOnActive = new AtomicBoolean(false);
            service.registerQueued("tx-active-cancel", TransferDirection.OUTGOING, "b.bin", 1024L, "10.0.0.8:5050", "Queued");
            service.registerCancelHandler("tx-active-cancel", () -> cancelInvokedOnActive.set(true));
            assertTrue(service.cancelTransfer("tx-active-cancel"));
            assertTrue(cancelInvokedOnActive.get());
            assertEquals(TransferMonitorState.CANCELLED, service.getTransfer("tx-active-cancel").orElseThrow().state());
        } finally {
            service.shutdown();
        }
    }

    @Test
    void enforcesStateTransitionsAndIgnoresLateEventsAfterTerminalState() throws Throwable {
        TransferMonitoringService service = new TransferMonitoringService(System::nanoTime, 0L, false);
        try {
            service.registerQueued("tx-term", TransferDirection.OUTGOING, "pkg.zip", 1000L, "192.168.1.9:5050", "Queued");
            service.onConnecting("tx-term", TransferDirection.OUTGOING, "pkg.zip", 1000L, "192.168.1.9:5050", "Handshaking");
            service.onStarted("tx-term", TransferDirection.OUTGOING, "pkg.zip", 1000L, 0L, "192.168.1.9:5050");
            service.onProgress("tx-term", TransferDirection.OUTGOING, "pkg.zip", 400L, 1000L);
            service.onFailed("tx-term", TransferDirection.OUTGOING, "pkg.zip", "Socket reset", new IOException("Socket reset"));

            // Late progress or completion after FAILED must be ignored
            service.onProgress("tx-term", TransferDirection.OUTGOING, "pkg.zip", 900L, 1000L);
            service.onCompleted("tx-term", TransferDirection.OUTGOING, "pkg.zip");

            MonitoredTransfer snapshot = service.getTransfer("tx-term").orElseThrow();
            assertEquals(TransferMonitorState.FAILED, snapshot.state());
            assertEquals(400L, snapshot.transferredBytes());
            assertEquals("Socket reset", snapshot.errorMessage());
            assertNotNull(snapshot.completedAt());

            JavaFxToolkitExtension.runOnFxThread(() -> {
                assertEquals(0, service.activeCountProperty().get());
                assertEquals(1, service.failedCountProperty().get());
            });
        } finally {
            service.shutdown();
        }
    }

    @Test
    void shutdownCancelsActiveTransfersClearsListenersAndInvokesCancelHandlers() {
        TransferMonitoringService service = new TransferMonitoringService(System::nanoTime, 0L, false);
        AtomicBoolean handlerCalled = new AtomicBoolean(false);

        service.addListener((a, r) -> {});
        service.registerQueued("tx-shutdown", TransferDirection.OUTGOING, "iso.img", 5000L, "192.168.1.5:5050", "Queued");
        service.onStarted("tx-shutdown", TransferDirection.OUTGOING, "iso.img", 5000L, 1000L, "192.168.1.5:5050");
        service.registerCancelHandler("tx-shutdown", () -> handlerCalled.set(true));

        service.shutdown();

        assertTrue(handlerCalled.get());
        assertEquals(0, service.listenerCount());
        MonitoredTransfer after = service.getTransfer("tx-shutdown").orElseThrow();
        assertEquals(TransferMonitorState.CANCELLED, after.state());
        assertNotNull(after.completedAt());
    }
}
