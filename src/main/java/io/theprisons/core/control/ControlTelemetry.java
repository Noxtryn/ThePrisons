package io.theprisons.core.control;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * What the control layer did in the last ticks, for diagnosing a macro that behaves oddly: the view, the requested view, who
 * owned view and keys, the keys, and the spin guard's window. A ring of the last {@value #RING} ticks (10 s) lives in memory; the
 * log gets one compact {@link #summary()} with the macro's 2-second trace and a line when an owner changes (at most once a second
 * per kind), never a line per tick.
 */
public final class ControlTelemetry {
    public static final int RING = 200;

    /** One tick, after the control layer applied view and keys. */
    public record Sample(long tick, double x, double y, double z, float yaw, float pitch, float requestedYaw, float requestedPitch,
                         RotationMode mode, IntentPriority rotationOwner, String rotationSource, String movementSource, IntentPriority movementOwner,
                         String keys, float remaining) {
    }

    private final ArrayDeque<Sample> ring = new ArrayDeque<>(RING + 1);
    private Sample last;
    private RotationMode lastMode;
    private String lastMovement = "";
    private long lastOwnerLogMs;
    private java.util.function.Consumer<String> sink = line -> {
    };
    private SpinGuard.Metrics spin = SpinGuard.Metrics.EMPTY;

    /** Where owner-change lines go (the core wires the logger; tests collect them). */
    public void sink(java.util.function.Consumer<String> sink) {
        this.sink = sink;
    }

    public void record(Sample sample, SpinGuard.Metrics spinMetrics, long nowMs, boolean logOwnerChanges) {
        ring.addLast(sample);
        while (ring.size() > RING) {
            ring.pollFirst();
        }
        last = sample;
        spin = spinMetrics;
        if (logOwnerChanges && nowMs - lastOwnerLogMs >= 1_000L
                && (sample.mode() != lastMode || !sample.movementSource().equals(lastMovement))) {
            lastOwnerLogMs = nowMs;
            sink.accept(String.format(Locale.ROOT, "[control] owners: view %s/%s, keys %s/%s (%s)", sample.mode(), sample.rotationOwner(),
                    sample.movementSource(), sample.movementOwner(), sample.keys()));
        }
        lastMode = sample.mode();
        lastMovement = sample.movementSource();
    }

    public Sample last() {
        return last;
    }

    public List<Sample> history() {
        return new ArrayList<>(ring);
    }

    /** One line: "view 123.4 -> 130.2 (MINING/TARGET_LOOK) keys WF- (macro/PATHFINDING) spin 210deg/60t moved 6.1 progress 3". */
    public String summary() {
        Sample s = last;
        if (s == null) {
            return "control: no sample";
        }
        return String.format(Locale.ROOT, "view %.0f -> %s (%s/%s) keys %s (%s/%s) spin %.0fdeg/%dt reach %.1f made %d",
                s.yaw(), Float.isNaN(s.requestedYaw()) ? "-" : String.format(Locale.ROOT, "%.0f", s.requestedYaw()), s.mode(),
                s.rotationSource().isEmpty() ? s.rotationOwner().name() : s.rotationOwner() + ":" + s.rotationSource(), s.keys(), s.movementSource().isEmpty() ? "-" : s.movementSource(), s.movementOwner(),
                spin.yawTurnedDegrees(), spin.ticks(), spin.maxDisplacement(), spin.progressInWindow());
    }

    /** "WF-J" style: W forward, B back, L left, R right, J jump, S sprint, N sneak; "-" for none. */
    public static String keysText(InputController.Keys k) {
        StringBuilder sb = new StringBuilder();
        if (k.forward()) {
            sb.append('W');
        }
        if (k.back()) {
            sb.append('B');
        }
        if (k.left()) {
            sb.append('L');
        }
        if (k.right()) {
            sb.append('R');
        }
        if (k.jump()) {
            sb.append('J');
        }
        if (k.sprint()) {
            sb.append('S');
        }
        if (k.sneak()) {
            sb.append('N');
        }
        return sb.isEmpty() ? "-" : sb.toString();
    }
}
