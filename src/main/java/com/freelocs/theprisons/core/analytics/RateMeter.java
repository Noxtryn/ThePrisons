package com.freelocs.theprisons.core.analytics;

import java.util.function.LongSupplier;

/**
 * Sliding-window rate: {@value #WINDOW_SECONDS} one-second buckets in a ring. Adding and reading are O(1) amortised,
 * with no allocation. Before a full window has passed the rate is taken over the time elapsed so far (at least 10 s,
 * so the first seconds do not show absurd numbers).
 */
public final class RateMeter {
    public static final int WINDOW_SECONDS = 60;

    private final LongSupplier clock;
    private final long[] buckets = new long[WINDOW_SECONDS];
    private final long startedAtMs;
    private long currentSecond;

    public RateMeter(LongSupplier clockMs) {
        this.clock = clockMs;
        this.startedAtMs = clockMs.getAsLong();
        this.currentSecond = startedAtMs / 1000L;
    }

    public void add(long amount) {
        advance();
        buckets[(int) (currentSecond % WINDOW_SECONDS)] += amount;
    }

    public long windowTotal() {
        advance();
        long total = 0L;
        for (long bucket : buckets) {
            total += bucket;
        }
        return total;
    }

    public double perHour() {
        long total = windowTotal();
        double elapsedSeconds = Math.min(WINDOW_SECONDS, Math.max(10.0D, (clock.getAsLong() - startedAtMs) / 1000.0D));
        return total * 3600.0D / elapsedSeconds;
    }

    private void advance() {
        long second = clock.getAsLong() / 1000L;
        if (second <= currentSecond) {
            return;
        }
        long steps = Math.min(WINDOW_SECONDS, second - currentSecond);
        for (long i = 1; i <= steps; i++) {
            buckets[(int) ((currentSecond + i) % WINDOW_SECONDS)] = 0L;
        }
        currentSecond = second;
    }
}
