package io.theprisons.items.energy;

import org.jspecify.annotations.Nullable;

import java.util.ArrayDeque;

/**
 * Keeps the readings of the subject in view and derives the {@link EnergyExtractorState}: the operation and the rate come ONLY from how the observed
 * value changed (a rate needs readings spanning at least {@link #MIN_SPAN_MS}). A new subject starts fresh. Bounded (32 readings).
 */
public final class EnergyTracker {
    public static final long MIN_SPAN_MS = 20_000L;
    public static final int MAX_READINGS = 32;
    /** Readings older than this no longer describe what the energy does now. */
    public static final long WINDOW_MS = 5 * 60_000L;

    private record Sample(long timeMs, long value) {
    }

    private final ArrayDeque<Sample> samples = new ArrayDeque<>();
    private String subject = "";
    private long revision;
    private @Nullable EnergyExtractorState state;

    public @Nullable EnergyExtractorState state() {
        return state;
    }

    public void clear() {
        samples.clear();
        subject = "";
        state = null;
    }

    public EnergyExtractorState update(EnergyReading r, long now) {
        if (!r.subject().equals(subject)) {
            samples.clear();
            subject = r.subject();
        }
        while (!samples.isEmpty() && now - samples.peekFirst().timeMs() > WINDOW_MS) {
            samples.pollFirst();
        }
        Sample last = samples.peekLast();
        if (last == null || last.value() != r.current() || now - last.timeMs() >= 1000L) {
            if (last != null && last.value() == r.current()) {
                // same value again: keep the older reading (the rate window must not shrink) - only the update time moves on
            } else {
                samples.addLast(new Sample(now, r.current()));
            }
            while (samples.size() > MAX_READINGS) {
                samples.pollFirst();
            }
        }
        Double rate = null;
        EnergyOperation op = EnergyOperation.UNKNOWN;
        Sample first = samples.peekFirst();
        Sample newest = samples.peekLast();
        if (first != null && newest != null && samples.size() >= 2) {
            long span = now - first.timeMs();
            if (span >= MIN_SPAN_MS) {
                rate = (r.current() - first.value()) * 60_000.0D / span;
                op = r.current() > first.value() ? EnergyOperation.GAINING : r.current() < first.value() ? EnergyOperation.DRAINING : EnergyOperation.IDLE;
            } else {
                // two readings but less than twenty seconds: the direction is known, a rate would be a guess
                op = r.current() > first.value() ? EnergyOperation.GAINING : r.current() < first.value() ? EnergyOperation.DRAINING : EnergyOperation.IDLE;
            }
        }
        EnergyExtractorState old = state;
        boolean changed = old == null || old.current() != r.current() || !java.util.Objects.equals(old.capacity(), r.capacity()) || old.operation() != op
                || !sameRate(old.rateMin(), rate) || !old.subject().equals(r.subject());
        if (changed) {
            revision++;
        }
        state = new EnergyExtractorState(revision, r.subject(), r.current(), r.capacity(), op, rate, now, r.source());
        return state;
    }

    private static boolean sameRate(@Nullable Double a, @Nullable Double b) {
        if (a == null || b == null) {
            return a == b;
        }
        return Math.abs(a - b) <= Math.max(1.0D, Math.abs(a) * 0.02D);
    }
}
