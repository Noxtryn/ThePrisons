package io.theprisons.modules.qol.bandit.dodge;

import io.theprisons.testing.DodgeSim;
import io.theprisons.testing.GridTerrain;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * World coordinates: x east (right on the map), z south (down). The player runs NORTH = -z; left of north is -x (west), right is +x (east).
 */
class BanditDodgePlannerTest {
    private static DodgeSim sim(GridTerrain terrain) {
        return new DodgeSim(new DodgeConfig(), terrain, 50.5D, 50.5D, 0.0D, -5.6D);
    }

    private static DodgeSim open() {
        return sim(GridTerrain.open(100));
    }

    private static DodgeDecision first(DodgeSim s) {
        return s.planner.plan(s.inputs());
    }

    // ── danger ───────────────────────────────────────────────────────────────

    @Test
    void dangerIsHugeInsideTheMinimumFadesOverTheWarningBandAndIsZeroBeyond() {
        assertEquals(1000.0D, BanditDodgePlanner.danger(7.9D, 8.0D, 6.0D));
        assertEquals(1000.0D, BanditDodgePlanner.danger(8.0D, 8.0D, 6.0D));
        assertEquals(100.0D, BanditDodgePlanner.danger(8.0001D, 8.0D, 6.0D), 0.01D);
        assertEquals(25.0D, BanditDodgePlanner.danger(11.0D, 8.0D, 6.0D), 1e-9);
        assertEquals(0.0D, BanditDodgePlanner.danger(14.0D, 8.0D, 6.0D));
        assertEquals(0.0D, BanditDodgePlanner.danger(40.0D, 8.0D, 6.0D));
        // a crowd adds up: four bandits at 11 blocks are four times one
        assertEquals(4 * BanditDodgePlanner.danger(11.0D, 8.0D, 6.0D), 100.0D, 1e-9);
    }

    // ── 1-3: one bandit ahead / left / right ────────────────────────────────

    @Test
    void aSingleBanditAheadIsNotRunInto() {
        DodgeSim s = open().bandit("b", 50.5, 36.5, 0, 3.0);
        DodgeDecision d = first(s);
        assertFalse(d.breach());
        assertTrue(Math.abs(d.dirX()) >= 0.3D, "turned to a side: " + d.dirX() + "," + d.dirZ() + " " + d.reason());
        assertTrue(d.projectedNearestBandit() >= 8.0D, "projected nearest " + d.projectedNearestBandit());
        assertTrue(d.sprint());
    }

    @Test
    void aBanditOnTheLeftPushesToTheRightButTheRunGoesOn() {
        DodgeSim s = open().bandit("b", 42.5, 47.5, 0, 0);
        DodgeDecision d = first(s);
        assertTrue(d.dirX() > 0.2D, "to the right (+x): " + d.dirX() + " " + d.reason());
        assertTrue(d.dirZ() < -0.2D, "still going north: " + d.dirZ());
    }

    @Test
    void aBanditOnTheRightPushesToTheLeft() {
        DodgeSim s = open().bandit("b", 58.5, 47.5, 0, 0);
        DodgeDecision d = first(s);
        assertTrue(d.dirX() < -0.2D, "to the left (-x): " + d.dirX() + " " + d.reason());
        assertTrue(d.dirZ() < -0.2D, "still going north: " + d.dirZ());
    }

    // ── 4, 8: gaps ───────────────────────────────────────────────────────────

    @Test
    void aWideGapBetweenTwoBanditsIsTakenStraightThrough() {
        DodgeSim s = open().bandit("l", 40.5, 40.5, 0, 0).bandit("r", 60.5, 40.5, 0, 0);
        DodgeDecision d = first(s);
        assertTrue(Math.abs(d.dirX()) < 0.35D && d.dirZ() < -0.6D, "through the middle: " + d.dirX() + "," + d.dirZ() + " " + d.reason());
        assertTrue(d.projectedNearestBandit() >= 8.0D, "the minimum distance holds in the gap: " + d.projectedNearestBandit());
    }

    @Test
    void aNarrowGapThatTheMinimumDistanceDoesNotFitIsNotTaken() {
        // 14 apart: the middle is only 7 from each, under the 8 block minimum
        DodgeSim s = open().bandit("l", 43.5, 40.5, 0, 0).bandit("r", 57.5, 40.5, 0, 0);
        DodgeDecision d = first(s);
        assertFalse(Math.abs(d.dirX()) < 0.2D && d.dirZ() < -0.9D, "not straight into the too-narrow gap: " + d.dirX() + "," + d.dirZ() + " " + d.reason());
        // and a gap that fits is taken: 18 apart = 9 from each side
        DodgeSim fits = open().bandit("l", 41.5, 40.5, 0, 0).bandit("r", 59.5, 40.5, 0, 0);
        DodgeDecision g = first(fits);
        assertTrue(Math.abs(g.dirX()) < 0.35D && g.dirZ() < -0.6D, "the fitting gap: " + g.dirX() + "," + g.dirZ() + " " + g.reason());
    }

    // ── 5, 13: masses and crowds ────────────────────────────────────────────

    @Test
    void aMassOfBanditsAheadIsNotEntered() {
        DodgeSim s = open();
        for (int i = 0; i < 5; i++) {
            s.bandit("b" + i, 44.5 + i * 3.0, 37.5, 0, 0);
        }
        DodgeDecision d = first(s);
        assertTrue(Math.abs(d.dirX()) >= 0.4D, "to the edge, not through the middle: " + d.dirX() + "," + d.dirZ() + " " + d.reason());
        DodgeCandidate straight = d.candidates().stream().filter(c -> c.index() == -1).findFirst().orElseThrow();
        assertTrue(straight.threat() > 0.0D);
        assertTrue(d.projectedNearestBandit() > straight.nearest(), "keeps more distance than going straight");
    }

    @Test
    void aLaneThatPassesOneBanditBeatsALaneThatPassesFour() {
        // A room (walls west / east) whose north wall has two openings: beside the left one stands one bandit, beside the right one four (all in the warning band, none inside the minimum distance).
        GridTerrain t = GridTerrain.open(100);
        for (int z = 40; z < 100; z++) {
            t = t.with(36, z, '#').with(64, z, '#');
        }
        for (int x = 36; x <= 64; x++) {
            if (!(x >= 38 && x <= 44) && !(x >= 56 && x <= 62)) {
                t = t.with(x, 46, '#').with(x, 45, '#');
            }
        }
        DodgeSim s = new DodgeSim(new DodgeConfig(), t, 50.5D, 52.5D, 0.0D, -5.6D)
                .bandit("one", 38.5, 38.5, 0, 0)   // one beyond the left opening
                .bandit("f1", 60.5, 38.5, 0, 0).bandit("f2", 63.5, 38.5, 0, 0).bandit("f3", 60.5, 35.5, 0, 0).bandit("f4", 63.5, 35.5, 0, 0); // four beyond the right opening
        DodgeDecision d = first(s);
        DodgeCandidate west = bestTowards(d, -1);
        DodgeCandidate east = bestTowards(d, 1);
        assertTrue(west.threat() <= east.threat(), "the crowd lane carries at least as much threat: " + west.threat() + " vs " + east.threat());
        assertTrue(west.score() > east.score(), "the single-bandit lane scores higher: " + west.score() + " vs " + east.score());
        s.run(120);
        assertTrue(s.minDistanceSeen >= 6.0D, "never walks into the crowd: " + s.minDistanceSeen);
    }

    /** The best-scored non-blocked candidate that heads north-west (-1) or north-east (+1). */
    private static DodgeCandidate bestTowards(DodgeDecision d, int side) {
        return d.candidates().stream().filter(c -> c.index() >= 0 && c.dirZ() < -0.2D && c.dirX() * side > 0.1D && c.free() >= 5.0D)
                .max(java.util.Comparator.comparingDouble(DodgeCandidate::free)).orElseThrow();
    }

    // ── 6: behind ────────────────────────────────────────────────────────────

    @Test
    void aBanditBehindIsOutrunNotTurnedTowards() {
        DodgeSim s = open().bandit("b", 50.5, 59.5, 0, -4.0);
        DodgeDecision d = first(s);
        assertTrue(d.dirZ() < -0.7D, "forward: " + d.dirX() + "," + d.dirZ() + " " + d.reason());
        assertFalse(d.reason().contains("evade") && d.dirZ() > 0.0D);
    }

    // ── 7, 14: breach and the aim window ────────────────────────────────────

    @Test
    void aBreachFromTheSideClosesTheAimWindowAtOnceAndEvadeWins() {
        DodgeSim s = open();
        s.run(20);
        assertTrue(s.last().aimWindowOpen(), "open after a calm run: ticks " + s.last().aimWindowTicks());
        assertTrue(s.last().aimWindowTicks() >= 12);
        s.bandit("b", s.x - 5.0D, s.z, 0, 0);
        DodgeDecision d = s.tick();
        assertTrue(d.breach());
        assertFalse(d.aimWindowOpen(), "closed in the very tick of the breach");
        assertEquals(0, d.aimWindowTicks());
        assertTrue(d.dirX() > 0.2D, "away from the bandit (west): " + d.dirX() + " " + d.reason());
        assertTrue(d.action() == DodgeAction.HARD_EVADE || d.action() == DodgeAction.DIAGONAL || d.action() == DodgeAction.STRAFE_RIGHT, "action " + d.action());
        assertTrue(d.nearestBandit() < 8.0D);
    }

    @Test
    void theMinimumDistanceIsCheckedAgainstEveryBanditNotJustTheNearestOrTheTarget() {
        // The first bandit is far away, the second slips under the minimum: that alone is a breach.
        DodgeSim s = open().bandit("far", 50.5, 10.5, 0, 0).bandit("near", 55.5, 50.5, 0, 0);
        DodgeDecision d = first(s);
        assertTrue(d.breach());
        assertEquals(5.0D, d.nearestBandit(), 1e-9);
        assertFalse(d.aimWindowSafe());
        // with everybody beyond the minimum the aim is safe once the window has filled
        DodgeSim calm = open().bandit("a", 20.5, 20.5, 0, 0).bandit("b", 80.5, 20.5, 0, 0);
        calm.run(20);
        assertTrue(calm.last().aimWindowOpen());
    }

    @Test
    void theAimWindowNeedsSafeTicksInARowAndAnyUnsafeTickResetsIt() {
        AimWindow w = new AimWindow(5);
        for (int i = 0; i < 4; i++) {
            w.update(true, false);
            assertFalse(w.open());
        }
        w.update(true, false);
        assertTrue(w.open());
        w.update(false, false);
        assertFalse(w.open());
        assertEquals(0, w.ticks());
        for (int i = 0; i < 5; i++) {
            w.update(true, false);
        }
        assertTrue(w.open());
        w.update(true, true);
        assertFalse(w.open(), "a breach beats a 'safe' tick");
        assertEquals(0, w.ticks());
    }

    // ── 9, 10: jump and drop ────────────────────────────────────────────────

    @Test
    void aOneBlockStepIsJumpedWhenItIsClose() {
        GridTerrain t = GridTerrain.open(100);
        for (int x = 40; x < 60; x++) {
            for (int z = 30; z <= 49; z++) {
                t = t.with(x, z, '^');
            }
        }
        DodgeSim s = sim(t);
        DodgeDecision d = first(s);
        assertTrue(Math.abs(d.dirX()) < 0.5D && d.dirZ() < -0.6D, "still north: " + d.dirX() + "," + d.dirZ());
        assertTrue(d.jump(), "the step is 1.5 blocks away: " + d.reason());
        assertEquals(DodgeAction.JUMP_FORWARD, d.action());
        // no bunny hopping: the very next tick does not jump again
        DodgeDecision next = s.planner.plan(new DodgeInputs(s.now + 50L, s.x, 64.0D, s.z, 0, -5.6, 0.0, true, List.of(), t, SpearAreaState.UNKNOWN));
        assertFalse(next.jump(), "jump cooldown");
    }

    @Test
    void aStepFarAwayIsNotJumpedYet() {
        GridTerrain t = GridTerrain.open(100);
        for (int x = 40; x < 60; x++) {
            t = t.with(x, 44, '^');
        }
        DodgeDecision d = first(sim(t));
        assertFalse(d.jump(), "6 blocks away");
    }

    @Test
    void aDropAheadIsNotRunOff() {
        GridTerrain t = GridTerrain.open(100);
        for (int x = 30; x < 70; x++) {
            for (int z = 20; z <= 48; z++) {
                t = t.with(x, z, ' ');
            }
        }
        DodgeSim s = sim(t);
        DodgeDecision d = first(s);
        assertTrue(d.dirZ() > -0.5D, "not into the pit: " + d.dirX() + "," + d.dirZ() + " " + d.reason());
        assertTrue(d.freeDistance() >= 3.0D, "a usable way: " + d.freeDistance());
        s.run(100);
        assertTrue(s.z >= 49.0D, "never over the edge, z=" + s.z);
    }

    // ── 11: wall left, bandit right: no oscillation ─────────────────────────

    @Test
    void aWallOnOneSideAndABanditOnTheOtherDoNotMakeItFlutter() {
        GridTerrain t = GridTerrain.open(100);
        for (int x = 0; x < 47; x++) {
            for (int z = 0; z < 100; z++) {
                t = t.with(x, z, '#');
            }
        }
        DodgeSim s = sim(t).bandit("b", 58.5, 42.5, 0, 0);
        s.run(200);
        int bigTurns = 0;
        for (int i = 1; i < s.decisions.size(); i++) {
            double a = Math.abs(wrap(s.decisions.get(i).headingDegrees() - s.decisions.get(i - 1).headingDegrees()));
            if (a > 35.0D) {
                bigTurns++;
            }
        }
        assertTrue(bigTurns <= 8, "turns over 35 degrees in 10 s: " + bigTurns);
        assertTrue(s.minDistanceSeen >= 6.0D, "min distance seen " + s.minDistanceSeen);
        assertTrue(Math.hypot(s.x - 50.5, s.z - 50.5) > 10.0D, "it kept moving");
    }

    private static double wrap(double d) {
        double v = d % 360.0D;
        return v > 180.0D ? v - 360.0D : v <= -180.0D ? v + 360.0D : v;
    }

    // ── 12: the moving bandit that will cross the lane ──────────────────────

    @Test
    void aLaneThatIsEmptyNowButWillBeCrossedIsRejected() {
        // A bandit 9 blocks to the side: the straight lane is acceptable while it stands still, but not when it walks across the lane.
        DodgeCandidate stillLane = first(open().bandit("b", 59.5, 42.5, 0, 0)).candidates().stream().filter(c -> c.index() == -1).findFirst().orElseThrow();
        DodgeDecision walking = first(open().bandit("b", 59.5, 42.5, -6.0, 0));   // walking west across the lane
        DodgeCandidate crossedLane = walking.candidates().stream().filter(c -> c.index() == -1).findFirst().orElseThrow();
        assertTrue(stillLane.safe(), "standing still: the lane keeps the minimum distance (" + stillLane.nearest() + ")");
        assertFalse(crossedLane.safe(), "walking across: the same lane is rejected (" + crossedLane.nearest() + ")");
        assertTrue(crossedLane.nearest() < stillLane.nearest() - 2.0D);
        assertTrue(walking.projectedNearestBandit() >= 8.0D, "the chosen way keeps the minimum distance: " + walking.projectedNearestBandit() + " " + walking.reason());
    }

    @Test
    void aGlitchVelocityIsNotTrusted() {
        DodgeSim glitch = open().bandit("b", 59.5, 42.5, -80.0, 0); // a teleport shows as an absurd speed: treated as standing
        DodgeSim still = open().bandit("b", 59.5, 42.5, 0, 0);
        DodgeDecision g = first(glitch);
        DodgeDecision s = first(still);
        assertEquals(s.dirX(), g.dirX(), 1e-9);
        assertEquals(s.dirZ(), g.dirZ(), 1e-9);
    }

    // ── 15: nothing around ───────────────────────────────────────────────────

    @Test
    void withoutBanditsItStillRunsForwardAndNeverStandsStill() {
        DodgeSim s = open();
        DodgeDecision d = first(s);
        assertTrue(d.sprint());
        assertEquals(1.0D, Math.hypot(d.dirX(), d.dirZ()), 1e-9);
        assertTrue(d.dirZ() < -0.9D, "forward: " + d.dirZ());
        assertTrue(Double.isNaN(d.nearestBandit()));
        s.run(100);
        assertTrue(Math.hypot(s.x - 50.5D, s.z - 50.5D) > 15.0D, "it ran");
        for (DodgeDecision each : s.decisions) {
            assertTrue(each.sprint() && Math.hypot(each.dirX(), each.dirZ()) > 0.99D);
        }
    }

    @Test
    void itTurnsAwayFromAWallInsteadOfStopping() {
        GridTerrain t = GridTerrain.open(100);
        for (int x = 0; x < 100; x++) {
            t = t.with(x, 30, '#');
        }
        DodgeSim s = sim(t);
        s.run(200);
        assertTrue(s.z > 30.0D, "never through the wall");
        assertTrue(s.decisions.stream().skip(60).anyMatch(d -> Math.abs(d.dirX()) > 0.5D), "it went along the wall");
    }

    // ── memory: oscillation, stuck, area ─────────────────────────────────────

    @Test
    void stuckPlayerTriesAnotherLane() {
        DodgeSim s = open();
        // The player does not move although it is told to (an obstacle the probes do not see): after the window it recovers.
        DodgeDecision d = null;
        for (int i = 0; i < 60; i++) {
            d = s.planner.plan(s.inputs());
            s.now += 50L;
        }
        boolean recovered = false;
        s = open();
        double[] firstDir = null;
        for (int i = 0; i < 60; i++) {
            d = s.planner.plan(s.inputs());
            if (firstDir == null) {
                firstDir = new double[]{d.dirX(), d.dirZ()};
            }
            recovered |= d.action() == DodgeAction.RECOVER;
            s.now += 50L;
        }
        assertTrue(recovered, "a RECOVER decision after 1.5 s without progress");
        assertTrue(d.stuck() || recovered);
    }

    @Test
    void invalidSpearAreaPullsTowardsTheLastValidSpotAndUnknownInventsNothing() {
        DodgeSim s = open();
        s.area = SpearAreaState.VALID;
        s.planner.plan(s.inputs()); // remembers (50.5, 50.5)
        DodgeSim away = new DodgeSim(new DodgeConfig(), GridTerrain.open(100), 50.5D, 20.5D, 0.0D, 0.0D);
        away.planner.plan(valid(away, 50.5D, 50.5D)); // learn the spot first
        away.area = SpearAreaState.INVALID;
        DodgeDecision invalid = away.planner.plan(away.inputs());
        assertTrue(invalid.reason().contains("INVALID"));
        assertEquals(SpearAreaState.INVALID, invalid.area());
        DodgeSim unknown = new DodgeSim(new DodgeConfig(), GridTerrain.open(100), 50.5D, 20.5D, 0.0D, 0.0D);
        unknown.area = SpearAreaState.UNKNOWN;
        DodgeDecision u = unknown.planner.plan(unknown.inputs());
        assertFalse(u.reason().contains("INVALID"));
        assertEquals(SpearAreaState.UNKNOWN, u.area());
    }

    private static DodgeInputs valid(DodgeSim s, double atX, double atZ) {
        return new DodgeInputs(s.now, atX, 64.0D, atZ, 0, 0, 0.0D, true, List.of(), s.terrain, SpearAreaState.VALID);
    }

    @Test
    void thirtyTwoOpenHeadingsPlusTheCurrentOneAreScoredAndTheListIsBounded() {
        DodgeSim s = open().bandit("b", 50.5, 40.5, 0, 0);
        DodgeDecision d = first(s);
        assertEquals(17, d.candidates().size());
        assertTrue(d.candidates().stream().anyMatch(c -> c.index() == -1));
        assertEquals(1, d.nearbyCount());
    }

    @Test
    void anOpenCrowdFieldNeverGetsTheRunnerCloserThanTheMinimumWhenThereIsRoom() {
        DodgeSim s = open();
        s.bandit("a", 30.5, 30.5, 2.0, 2.0).bandit("b", 70.5, 30.5, -2.0, 2.0).bandit("c", 50.5, 20.5, 0, 3.0).bandit("d", 40.5, 70.5, 1.0, -2.0)
                .bandit("e", 60.5, 72.5, -1.0, -2.0);
        s.run(400);
        assertTrue(s.minDistanceSeen >= 5.5D, "closest approach in 20 s with five wandering bandits: " + s.minDistanceSeen);
        assertTrue(s.decisions.stream().anyMatch(DodgeDecision::aimWindowOpen), "it creates aim windows");
    }
}
