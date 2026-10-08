package io.theprisons.core.control;

import io.theprisons.core.control.InputController.Keys;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpinGuardTest {
    /** Feeds one tick at a time; 50 ms per tick. Returns the first non-NONE verdict. */
    private static SpinGuard.Verdict run(SpinGuard guard, long startMs, int ticks, java.util.function.IntFunction<double[]> sample) {
        for (int i = 0; i < ticks; i++) {
            double[] s = sample.apply(i); // x, z, yaw
            SpinGuard.Verdict v = guard.feed(startMs + i * 50L, s[0], s[1], (float) s[2]);
            if (v != SpinGuard.Verdict.NONE) {
                return v;
            }
        }
        return SpinGuard.Verdict.NONE;
    }

    @Test
    void spinningOnTheSpotWithoutProgressIsDetected() {
        SpinGuard guard = new SpinGuard();
        // 15 degrees per tick = 300 deg/s: 900 degrees in the 60-tick window, standing still.
        assertEquals(SpinGuard.Verdict.SPIN, run(guard, 0, 120, i -> new double[]{10.0, 20.0, i * 15.0}));
        assertEquals(1, guard.spins());
    }

    @Test
    void walkingACircleIsNotASpin() {
        SpinGuard guard = new SpinGuard();
        // The same turning rate while walking a 6-block-radius circle: the position changes a lot.
        assertEquals(SpinGuard.Verdict.NONE, run(guard, 0, 300, i -> {
            double a = Math.toRadians(i * 6.0);
            return new double[]{6 * Math.cos(a), 6 * Math.sin(a), i * 15.0};
        }));
        assertEquals(0, guard.spins());
    }

    @Test
    void turningWhileBlocksBreakIsNotASpin() {
        SpinGuard guard = new SpinGuard();
        for (int i = 0; i < 300; i++) {
            if (i % 20 == 0) {
                guard.progress(); // a block changed
            }
            assertEquals(SpinGuard.Verdict.NONE, guard.feed(i * 50L, 1.0, 1.0, i * 15.0F), "tick " + i);
        }
    }

    @Test
    void slowTurningOnTheSpotIsNotASpin() {
        SpinGuard guard = new SpinGuard();
        assertEquals(SpinGuard.Verdict.NONE, run(guard, 0, 300, i -> new double[]{0, 0, i * 3.0}), "180 deg/s is a normal turn, 9 deg per tick is not 720 in 3 s");
    }

    @Test
    void yawWrapIsShortestAngle() {
        SpinGuard guard = new SpinGuard();
        // Jittering across the +-180 boundary by 2 degrees must not count as 358 degrees each time.
        assertEquals(SpinGuard.Verdict.NONE, run(guard, 0, 300, i -> new double[]{0, 0, i % 2 == 0 ? 179.0 : -179.0}));
        assertEquals(2.0D, SpinGuard.wrap(-179.0 - 179.0 + 360.0 - 0.0) , 1e-9);
        assertEquals(-2.0D, SpinGuard.wrap(358.0), 1e-9);
        assertEquals(180.0D, SpinGuard.wrap(-180.0), 1e-9);
    }

    @Test
    void secondSpinWithinTheWindowEscalates() {
        SpinGuard guard = new SpinGuard();
        assertEquals(SpinGuard.Verdict.SPIN, run(guard, 0, 120, i -> new double[]{0, 0, i * 15.0}));
        assertEquals(SpinGuard.Verdict.SPIN_REPEATED, run(guard, 10_000, 120, i -> new double[]{0, 0, i * 15.0}));
    }

    @Test
    void aSpinMuchLaterIsAFreshFirstSpin() {
        SpinGuard guard = new SpinGuard();
        assertEquals(SpinGuard.Verdict.SPIN, run(guard, 0, 120, i -> new double[]{0, 0, i * 15.0}));
        assertEquals(SpinGuard.Verdict.SPIN, run(guard, 5 * 60_000L, 120, i -> new double[]{0, 0, i * 15.0}));
    }

    @Test
    void theWindowMustBeFullFirst() {
        SpinGuard guard = new SpinGuard();
        for (int i = 0; i < SpinGuard.DEFAULT_WINDOW_TICKS - 1; i++) {
            assertEquals(SpinGuard.Verdict.NONE, guard.feed(i * 50L, 0, 0, i * 30.0F));
        }
    }

    @Test
    void metricsDescribeTheWindow() {
        SpinGuard guard = new SpinGuard(10, 100_000.0, 2.0, 1_000L);
        for (int i = 0; i < 10; i++) {
            guard.feed(i * 50L, i * 0.5, 0, i * 10.0F);
        }
        SpinGuard.Metrics m = guard.metrics();
        assertEquals(10, m.ticks());
        assertEquals(90.0D, m.yawTurnedDegrees(), 1e-6);
        assertEquals(4.5D, m.maxDisplacement(), 1e-6);
        assertEquals(0L, m.progressInWindow());
    }

    // ── arbitration of keys ─────────────────────────────────────────────────

    @Test
    void legacySetKeepsLastWriterWins() {
        InputController input = new InputController();
        input.set(new Keys(true, false, false, false, false, false, false));
        input.set(new Keys(false, true, false, false, false, false, false));
        assertTrue(input.wanted().back());
        assertFalse(input.wanted().forward());
    }

    @Test
    void higherPriorityBeatsTheMacroWithinATick() {
        InputController input = new InputController();
        assertTrue(input.request(IntentPriority.UNSTUCK, "unstuck", new Keys(false, true, false, false, true, false, false)));
        assertFalse(input.request(IntentPriority.PATHFINDING, "macro", new Keys(true, false, false, false, false, false, false)),
                "path following must not override the unstuck move");
        assertTrue(input.wanted().back());
        assertEquals(1, input.rejectedRequests());
        // equal priority: the later one wins
        assertTrue(input.request(IntentPriority.UNSTUCK, "unstuck2", Keys.NONE));
        assertEquals(Keys.NONE, input.wanted());
    }

    @Test
    void emergencyBeatsEverythingButManual() {
        InputController input = new InputController();
        input.request(IntentPriority.EMERGENCY, "safety", Keys.NONE);
        assertFalse(input.request(IntentPriority.COMBAT_EVADE, "evade", new Keys(true, false, false, false, false, false, false)));
        assertTrue(input.request(IntentPriority.MANUAL, "human", new Keys(false, false, true, false, false, false, false)));
        assertTrue(input.wanted().left());
    }

    @Test
    void rotationModesMapToTheSharedPriorities() {
        assertEquals(IntentPriority.UNSTUCK, RotationMode.RECOVERY.intent());
        assertEquals(IntentPriority.TARGET_LOOK, RotationMode.MINING.intent());
        assertEquals(IntentPriority.PATHFINDING, RotationMode.NAVIGATION.intent());
        assertTrue(IntentPriority.MANUAL.rank() > IntentPriority.EMERGENCY.rank());
        assertTrue(IntentPriority.UNSTUCK.rank() > IntentPriority.PATHFINDING.rank());
        assertTrue(IntentPriority.PATHFINDING.rank() > IntentPriority.TARGET_LOOK.rank());
        assertTrue(IntentPriority.TARGET_LOOK.rank() > IntentPriority.IDLE.rank());
    }

    @Test
    void telemetrySummaryIsCompactAndLogsOwnerChangesRarely() {
        ControlTelemetry telemetry = new ControlTelemetry();
        java.util.List<String> lines = new java.util.ArrayList<>();
        telemetry.sink(lines::add);
        for (int i = 0; i < 200; i++) {
            RotationMode mode = i < 100 ? RotationMode.NAVIGATION : RotationMode.MINING;
            telemetry.record(new ControlTelemetry.Sample(i, 0, 0, 0, 10, 0, 20, 0, mode, mode.intent(), "", "macro", IntentPriority.PATHFINDING,
                    "WJ", 5.0F), SpinGuard.Metrics.EMPTY, i * 50L, true);
        }
        assertTrue(lines.size() <= 3, "owner lines are rate limited, got " + lines.size());
        assertTrue(telemetry.summary().contains("MINING"));
        assertTrue(telemetry.summary().length() < 160);
        assertEquals(ControlTelemetry.RING, telemetry.history().size());
        assertEquals("WJ", ControlTelemetry.keysText(new Keys(true, false, false, false, true, false, false)));
        assertEquals("-", ControlTelemetry.keysText(Keys.NONE));
    }
}
