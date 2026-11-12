package com.syncshare.monitoring;

import com.syncshare.model.TransferDirection;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TransferMonitoringModelTest {

    @Test
    void progressCalculationHandlesNormalZeroByteUnknownAndOverflowCases() {
        assertEquals(0.5, TransferMetricsCalculator.calculateProgress(512L, 1024L, TransferMonitorState.RUNNING), 1e-9);
        assertEquals(50.0, TransferMetricsCalculator.calculatePercentage(512L, 1024L, TransferMonitorState.RUNNING).orElseThrow(), 1e-9);

        // Zero-byte files: 0% while running, 100% only after completion confirmation
        assertEquals(0.0, TransferMetricsCalculator.calculateProgress(0L, 0L, TransferMonitorState.RUNNING), 1e-9);
        assertEquals(1.0, TransferMetricsCalculator.calculateProgress(0L, 0L, TransferMonitorState.COMPLETED), 1e-9);

        // Unknown total size (< 0): indeterminate while running/connecting, 100% only when completed
        assertEquals(
            TransferMetricsCalculator.INDETERMINATE_PROGRESS,
            TransferMetricsCalculator.calculateProgress(4096L, -1L, TransferMonitorState.RUNNING),
            1e-9
        );
        assertTrue(TransferMetricsCalculator.calculatePercentage(4096L, -1L, TransferMonitorState.RUNNING).isEmpty());
        assertEquals(1.0, TransferMetricsCalculator.calculateProgress(4096L, -1L, TransferMonitorState.COMPLETED), 1e-9);

        // Transferred bytes exceeding expected total and negative bytes are safely clamped
        assertEquals(1000L, TransferMetricsCalculator.clampTransferredBytes(1500L, 1000L));
        assertEquals(0L, TransferMetricsCalculator.clampTransferredBytes(-250L, 1000L));
        assertEquals(1.0, TransferMetricsCalculator.calculateProgress(1500L, 1000L, TransferMonitorState.RUNNING), 1e-9);
        assertEquals(0.0, TransferMetricsCalculator.calculateProgress(-100L, 1000L, TransferMonitorState.RUNNING), 1e-9);
    }

    @Test
    void speedEstimatorHandlesInitialSteadyZeroStallInvalidIntervalsAndUnstableRates() {
        AtomicLong clock = new AtomicLong(1_000_000_000L);
        TransferSpeedEstimator estimator = new TransferSpeedEstimator(
            clock::get,
            100_000_000L,   // 100 ms min window
            1_000_000_000L, // 1 s stall timeout
            0.5             // EMA alpha
        );

        // First sample initializes baseline -> speed unavailable initially
        assertEquals(TransferMetricsCalculator.UNAVAILABLE_SPEED, estimator.recordBytes(0L, clock.get()));

        // Invalid zero/negative time intervals must not produce NaN or Infinity
        assertEquals(TransferMetricsCalculator.UNAVAILABLE_SPEED, estimator.recordBytes(1024L, clock.get()));
        assertEquals(TransferMetricsCalculator.UNAVAILABLE_SPEED, estimator.recordBytes(2048L, clock.get() - 50_000_000L));

        // 1 MB over 1.0 s -> 1 MB/s
        clock.addAndGet(1_000_000_000L);
        double firstRate = estimator.recordBytes(1_048_576L, clock.get());
        assertEquals(1_048_576.0, firstRate, 1.0);
        assertTrue(Double.isFinite(firstRate));

        // Unstable burst: 3 MB in next 1.0 s -> EMA(0.5 * 3MB/s + 0.5 * 1MB/s) = 2 MB/s
        clock.addAndGet(1_000_000_000L);
        double smoothedRate = estimator.recordBytes(4L * 1_048_576L, clock.get());
        assertEquals(2.0 * 1_048_576.0, smoothedRate, 1.0);

        // No byte changes within short interval retains valid speed, then drops to 0.0 after stall timeout
        clock.addAndGet(200_000_000L);
        assertEquals(smoothedRate, estimator.recordBytes(4L * 1_048_576L, clock.get()), 1e-6);

        clock.addAndGet(1_000_000_000L);
        assertEquals(0.0, estimator.recordBytes(4L * 1_048_576L, clock.get()), 1e-6);

        // Interrupted / terminal reset marks speed unavailable
        estimator.markInactive();
        assertEquals(TransferMetricsCalculator.UNAVAILABLE_SPEED, estimator.currentSpeed());
    }

    @Test
    void etaCalculationUsesRealRemainingBytesAndNeverInventsValues() {
        assertEquals(
            10L,
            TransferMetricsCalculator.calculateEtaSeconds(500L, 1500L, 100.0, TransferMonitorState.RUNNING)
        );
        assertEquals(
            0L,
            TransferMetricsCalculator.calculateEtaSeconds(1500L, 1500L, 100.0, TransferMonitorState.COMPLETED)
        );

        // Zero speed, unavailable speed, NaN, unknown total size, zero-byte, and non-running states -> unavailable
        assertEquals(
            TransferMetricsCalculator.UNAVAILABLE_ETA,
            TransferMetricsCalculator.calculateEtaSeconds(500L, 1500L, 0.0, TransferMonitorState.RUNNING)
        );
        assertEquals(
            TransferMetricsCalculator.UNAVAILABLE_ETA,
            TransferMetricsCalculator.calculateEtaSeconds(500L, 1500L, -1.0, TransferMonitorState.RUNNING)
        );
        assertEquals(
            TransferMetricsCalculator.UNAVAILABLE_ETA,
            TransferMetricsCalculator.calculateEtaSeconds(500L, 1500L, Double.NaN, TransferMonitorState.RUNNING)
        );
        assertEquals(
            TransferMetricsCalculator.UNAVAILABLE_ETA,
            TransferMetricsCalculator.calculateEtaSeconds(500L, -1L, 1000.0, TransferMonitorState.RUNNING)
        );
        assertEquals(
            TransferMetricsCalculator.UNAVAILABLE_ETA,
            TransferMetricsCalculator.calculateEtaSeconds(0L, 0L, 1000.0, TransferMonitorState.RUNNING)
        );
        assertEquals(
            TransferMetricsCalculator.UNAVAILABLE_ETA,
            TransferMetricsCalculator.calculateEtaSeconds(500L, 1500L, 1000.0, TransferMonitorState.CONNECTING)
        );
    }

    @Test
    void stateMachineEnforcesValidTransitionsAndOmitsUnsupportedPauseResume() {
        assertFalse(
            Arrays.stream(TransferMonitorState.values())
                .anyMatch(s -> s.name().equals("PAUSED") || s.name().equals("RESUMING")),
            "PAUSED/RESUMING must not be exposed when backend lacks interactive pause/resume"
        );

        assertTrue(TransferMonitorState.QUEUED.canTransitionTo(TransferMonitorState.CONNECTING));
        assertTrue(TransferMonitorState.QUEUED.canTransitionTo(TransferMonitorState.RUNNING));
        assertTrue(TransferMonitorState.QUEUED.canTransitionTo(TransferMonitorState.CANCELLED));
        assertTrue(TransferMonitorState.CONNECTING.canTransitionTo(TransferMonitorState.RUNNING));
        assertTrue(TransferMonitorState.RUNNING.canTransitionTo(TransferMonitorState.COMPLETED));
        assertTrue(TransferMonitorState.RUNNING.canTransitionTo(TransferMonitorState.FAILED));
        assertTrue(TransferMonitorState.RUNNING.canTransitionTo(TransferMonitorState.CANCELLED));

        // Invalid transitions must fail cleanly
        assertFalse(TransferMonitorState.QUEUED.canTransitionTo(TransferMonitorState.COMPLETED));
        assertFalse(TransferMonitorState.COMPLETED.canTransitionTo(TransferMonitorState.RUNNING));
        assertFalse(TransferMonitorState.FAILED.canTransitionTo(TransferMonitorState.COMPLETED));
        assertFalse(TransferMonitorState.CANCELLED.canTransitionTo(TransferMonitorState.RUNNING));

        assertThrows(
            IllegalStateException.class,
            () -> TransferMonitorState.COMPLETED.requireValidTransition(TransferMonitorState.FAILED)
        );
        assertThrows(
            IllegalStateException.class,
            () -> TransferMonitorState.QUEUED.requireValidTransition(TransferMonitorState.COMPLETED)
        );
    }

    @Test
    void monitoredTransferSnapshotFormatsMetricsAndTimestamps() {
        Instant started = Instant.parse("2026-11-12T10:00:00Z");
        MonitoredTransfer active = new MonitoredTransfer(
            "tx-1",
            "archive.tar.gz",
            TransferDirection.OUTGOING,
            "192.168.1.40:5050",
            2048L,
            1024L,
            0.5,
            512.0,
            2L,
            TransferMonitorState.RUNNING,
            started,
            null,
            started.plusSeconds(2),
            "",
            "Streaming"
        );

        assertEquals("2.0 KB", active.formattedTotalSize());
        assertEquals("1.0 KB / 2.0 KB", active.formattedTransferred());
        assertEquals("50%", active.formattedPercentage());
        assertEquals("512 B/s", active.formattedSpeed());
        assertEquals("2s", active.formattedEta());
        assertNull(active.completedAt());
        assertEquals("--", active.formattedCompletedAt());
        assertFalse(active.formattedStartedAt().equals("--"));

        MonitoredTransfer completed = new MonitoredTransfer(
            "tx-1",
            "archive.tar.gz",
            TransferDirection.OUTGOING,
            "192.168.1.40:5050",
            2048L,
            2048L,
            1.0,
            -1.0,
            0L,
            TransferMonitorState.COMPLETED,
            started,
            started.plusSeconds(4),
            started.plusSeconds(4),
            "",
            "Verified"
        );
        assertNotNull(completed.completedAt());
        assertEquals("100%", completed.formattedPercentage());
        assertEquals("Done", completed.formattedEta());
        assertEquals("--", completed.formattedSpeed());
    }
}
