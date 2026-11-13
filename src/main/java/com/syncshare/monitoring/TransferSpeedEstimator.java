package com.syncshare.monitoring;

import java.util.Objects;
import java.util.function.LongSupplier;

/**
 * Thread-safe throughput estimator that measures real transferred byte deltas
 * over monotonic elapsed nanoseconds using a minimum sampling window and an
 * exponential moving average (EMA) to smooth unstable speeds.
 */
public final class TransferSpeedEstimator {

    public static final long DEFAULT_MIN_WINDOW_NANOS = 150_000_000L; // 150 ms
    public static final long DEFAULT_STALL_TIMEOUT_NANOS = 2_500_000_000L; // 2.5 s
    public static final double DEFAULT_EMA_ALPHA = 0.35;

    private final LongSupplier nanoClock;
    private final long minWindowNanos;
    private final long stallTimeoutNanos;
    private final double emaAlpha;

    private boolean initialized;
    private long anchorBytes;
    private long anchorNanos;
    private long lastObservedBytes;
    private long lastProgressNanos;
    private double smoothedBytesPerSecond = TransferMetricsCalculator.UNAVAILABLE_SPEED;

    public TransferSpeedEstimator() {
        this(System::nanoTime, DEFAULT_MIN_WINDOW_NANOS, DEFAULT_STALL_TIMEOUT_NANOS, DEFAULT_EMA_ALPHA);
    }

    public TransferSpeedEstimator(
        LongSupplier nanoClock,
        long minWindowNanos,
        long stallTimeoutNanos,
        double emaAlpha
    ) {
        this.nanoClock = Objects.requireNonNull(nanoClock, "nanoClock");
        if (minWindowNanos <= 0L) {
            throw new IllegalArgumentException("minWindowNanos must be positive");
        }
        if (stallTimeoutNanos < minWindowNanos) {
            throw new IllegalArgumentException("stallTimeoutNanos must be >= minWindowNanos");
        }
        if (!Double.isFinite(emaAlpha) || emaAlpha <= 0.0 || emaAlpha > 1.0) {
            throw new IllegalArgumentException("emaAlpha must be in (0, 1]");
        }
        this.minWindowNanos = minWindowNanos;
        this.stallTimeoutNanos = stallTimeoutNanos;
        this.emaAlpha = emaAlpha;
    }

    public synchronized void reset(long initialBytes) {
        reset(initialBytes, nanoClock.getAsLong());
    }

    public synchronized void reset(long initialBytes, long nowNanos) {
        long safeBytes = Math.max(0L, initialBytes);
        this.initialized = true;
        this.anchorBytes = safeBytes;
        this.anchorNanos = nowNanos;
        this.lastObservedBytes = safeBytes;
        this.lastProgressNanos = nowNanos;
        this.smoothedBytesPerSecond = TransferMetricsCalculator.UNAVAILABLE_SPEED;
    }

    public synchronized double recordBytes(long totalTransferredBytes) {
        return recordBytes(totalTransferredBytes, nanoClock.getAsLong());
    }

    public synchronized double recordBytes(long totalTransferredBytes, long nowNanos) {
        long safeBytes = Math.max(0L, totalTransferredBytes);
        if (!initialized) {
            reset(safeBytes, nowNanos);
            return smoothedBytesPerSecond;
        }

        // Ignore invalid non-monotonic timestamps without emitting NaN/Infinity.
        if (nowNanos <= anchorNanos || nowNanos < lastProgressNanos) {
            if (safeBytes > lastObservedBytes) {
                lastObservedBytes = safeBytes;
            }
            return smoothedBytesPerSecond;
        }

        // Handle byte counter regression safely by re-anchoring.
        if (safeBytes < lastObservedBytes) {
            anchorBytes = safeBytes;
            anchorNanos = nowNanos;
            lastObservedBytes = safeBytes;
            lastProgressNanos = nowNanos;
            return smoothedBytesPerSecond;
        }

        if (safeBytes > lastObservedBytes) {
            lastObservedBytes = safeBytes;
            lastProgressNanos = nowNanos;
        }

        long elapsedNanos = nowNanos - anchorNanos;
        long deltaBytes = safeBytes - anchorBytes;

        if (deltaBytes > 0L && elapsedNanos >= minWindowNanos) {
            double instantRate = (deltaBytes * 1_000_000_000.0) / elapsedNanos;
            if (Double.isFinite(instantRate) && instantRate >= 0.0) {
                smoothedBytesPerSecond = smoothedBytesPerSecond < 0.0
                    ? instantRate
                    : (emaAlpha * instantRate) + ((1.0 - emaAlpha) * smoothedBytesPerSecond);
            }
            anchorBytes = safeBytes;
            anchorNanos = nowNanos;
        } else if (deltaBytes == 0L && (nowNanos - lastProgressNanos) >= stallTimeoutNanos) {
            smoothedBytesPerSecond = 0.0;
            anchorBytes = safeBytes;
            anchorNanos = nowNanos;
        }

        return smoothedBytesPerSecond;
    }

    public synchronized double currentSpeed() {
        return refreshAt(nanoClock.getAsLong());
    }

    
public synchronized double refreshAt(long nowNanos) {
    if (!initialized) {
        return TransferMetricsCalculator.UNAVAILABLE_SPEED;
    }

    if (nowNanos > lastProgressNanos
            && (nowNanos - lastProgressNanos) >= stallTimeoutNanos
            && smoothedBytesPerSecond != 0.0) {

        smoothedBytesPerSecond = 0.0;
        anchorBytes = lastObservedBytes;
        anchorNanos = nowNanos;
    }

    return smoothedBytesPerSecond;
}


    public synchronized void markInactive() {
        this.initialized = false;
        this.smoothedBytesPerSecond = TransferMetricsCalculator.UNAVAILABLE_SPEED;
    }
}
