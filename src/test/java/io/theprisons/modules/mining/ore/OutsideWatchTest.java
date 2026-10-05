package io.theprisons.modules.mining.ore;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class OutsideWatchTest {
    private static OutsideWatch.Step run(OutsideWatch watch, long from, long to, double toGoal, boolean near) {
        OutsideWatch.Step last = OutsideWatch.Step.NONE;
        for (long t = from; t <= to; t += 50L) {
            OutsideWatch.Step step = watch.update(t, toGoal, near);
            if (step != OutsideWatch.Step.NONE) {
                last = step;
            }
        }
        return last;
    }

    @Test
    void stuckOnTheSameGoalAskForANewWayEveryThreeSeconds() {
        OutsideWatch watch = new OutsideWatch();
        watch.start(0L);
        assertEquals(OutsideWatch.Step.NONE, run(watch, 0L, 2_900L, 20.0D, false));
        assertEquals(OutsideWatch.Step.NEW_WAY, run(watch, 2_950L, 3_100L, 20.0D, false));
    }

    @Test
    void gettingCloserIsNoReasonToStep() {
        OutsideWatch watch = new OutsideWatch();
        watch.start(0L);
        double d = 40.0D;
        for (long t = 0L; t < 9_000L; t += 50L) {
            // Sprinting back in: 5 blocks a second.
            assertEquals(OutsideWatch.Step.NONE, watch.update(t, d, false), "at " + t);
            d -= 0.25D;
        }
    }

    @Test
    void stagesFollowOneAnotherWithoutProgress() {
        OutsideWatch watch = new OutsideWatch();
        watch.start(0L);
        assertEquals(OutsideWatch.Step.WIDE, run(watch, 9_000L, 10_000L, Double.POSITIVE_INFINITY, false));
        assertEquals(OutsideWatch.Step.ESCAPE, run(watch, 19_000L, 20_000L, Double.POSITIVE_INFINITY, false));
        assertEquals(OutsideWatch.Step.GIVE_UP, run(watch, 44_000L, 45_000L, Double.POSITIVE_INFINITY, false));
    }

    @Test
    void aPlayerNearMakesItEscapeAfterFiveSeconds() {
        OutsideWatch watch = new OutsideWatch();
        watch.start(0L);
        assertEquals(OutsideWatch.Step.NEW_WAY, run(watch, 0L, 4_900L, 30.0D, true));
        assertEquals(OutsideWatch.Step.ESCAPE, run(watch, 4_950L, 5_100L, 30.0D, true));
    }

    @Test
    void newGoalsAloneAreNoProgressForTheEscape() {
        OutsideWatch watch = new OutsideWatch();
        watch.start(0L);
        OutsideWatch.Step escape = OutsideWatch.Step.NONE;
        for (long t = 0L; t <= 6_000L; t += 50L) {
            if (t % 1_000L == 0L) {
                // A new way every second, never walked closer.
                watch.target(t);
            }
            if (watch.update(t, 25.0D, true) == OutsideWatch.Step.ESCAPE) {
                escape = OutsideWatch.Step.ESCAPE;
            }
        }
        assertEquals(OutsideWatch.Step.ESCAPE, escape);
    }
}
