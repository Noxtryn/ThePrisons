package io.theprisons.modules.qol.bandit.dodge;

import io.theprisons.testing.DodgeSim;
import io.theprisons.testing.GridTerrain;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Situations that hurt in the live game: tight spaces, walls with a crowd, a lane that collapses, pressure that keeps coming, dead ends. */
class DodgeAdversarialTest {
    private static DodgeSim sim(GridTerrain t, double x, double z) {
        return new DodgeSim(new DodgeConfig(), t, x, z, 0.0D, -5.6D);
    }

    private static int sharpTurns(DodgeSim s, double degrees) {
        int n = 0;
        for (int i = 1; i < s.decisions.size(); i++) {
            double d = Math.abs(((s.decisions.get(i).headingDegrees() - s.decisions.get(i - 1).headingDegrees()) % 360.0D + 540.0D) % 360.0D - 180.0D);
            if (d > degrees) {
                n++;
            }
        }
        return n;
    }

    /** Ticks in which the player did not move (the run must not stop and start). */
    private static int standstill(DodgeSim s, double[] xs, double[] zs) {
        int n = 0;
        for (int i = 1; i < xs.length; i++) {
            if (Math.hypot(xs[i] - xs[i - 1], zs[i] - zs[i - 1]) < 0.1D) {
                n++;
            }
        }
        return n;
    }

    private DodgeSim runRecording(DodgeSim s, int ticks, double[][] out) {
        out[0] = new double[ticks + 1];
        out[1] = new double[ticks + 1];
        out[0][0] = s.x;
        out[1][0] = s.z;
        for (int i = 1; i <= ticks; i++) {
            s.tick();
            out[0][i] = s.x;
            out[1][i] = s.z;
        }
        return s;
    }

    @Test
    void aTightCorridorIsRunWithoutTouchingTheWallsOrFlipping() {
        GridTerrain t = GridTerrain.open(100);
        for (int z = 0; z < 100; z++) {
            for (int x = 0; x < 100; x++) {
                if (x < 48 || x > 52) {
                    t = t.with(x, z, '#');       // a corridor 5 wide (x 48..52)
                }
            }
        }
        double[][] rec = new double[2][];
        DodgeSim s = runRecording(sim(t, 50.5D, 95.5D), 250, rec);
        assertEquals(0, s.contactTicks, "never touched a wall");
        assertTrue(s.z < 40.0D, "ran through, z=" + s.z);
        assertTrue(sharpTurns(s, 40.0D) <= 1, "turns over 40 degrees: " + sharpTurns(s, 40.0D));
        assertEquals(0, standstill(s, rec[0], rec[1]));
    }

    @Test
    void aWallWithACrowdAtOneEndIsLeftTowardsTheOpenEndWithoutTouchingAnything() {
        GridTerrain t = GridTerrain.open(120);
        for (int x = 20; x <= 70; x++) {
            t = t.with(x, 40, '#').with(x, 41, '#');       // a wall from x=20 to 70; free around both ends
        }
        DodgeSim s = sim(t, 45.5D, 60.5D).bandit("a", 22.5D, 46.5D, 0, 0).bandit("b", 25.5D, 48.5D, 0, 0).bandit("c", 28.5D, 46.5D, 0, 0);
        double[][] rec = new double[2][];
        runRecording(s, 300, rec);
        assertEquals(0, s.contactTicks);
        assertTrue(s.minDistanceSeen >= 5.5D, "closest bandit " + s.minDistanceSeen);
        assertTrue(s.z < 41.0D || s.x > 70.0D || s.x < 20.0D, "it got around the wall: " + s.x + "," + s.z);
        assertTrue(s.x > 30.0D, "round the free (east) end, not through the crowd: x=" + s.x);
        assertEquals(0, standstill(s, rec[0], rec[1]));
    }

    @Test
    void aLaneThatCollapsesSuddenlyIsReroutedWithoutStopping() {
        DodgeSim s = sim(GridTerrain.open(150), 70.5D, 120.5D);
        double[][] rec = new double[2][];
        rec[0] = new double[161];
        rec[1] = new double[161];
        rec[0][0] = s.x;
        rec[1][0] = s.z;
        for (int i = 1; i <= 160; i++) {
            if (i == 30) {
                GridTerrain t = s.terrain;
                for (int x = 60; x <= 82; x++) {
                    t = t.with(x, (int) Math.floor(s.z) - 5, '#');     // a wall appears 5 blocks ahead
                }
                s.terrain = t;
            }
            s.tick();
            rec[0][i] = s.x;
            rec[1][i] = s.z;
        }
        assertEquals(0, s.contactTicks, "never ran into the new wall");
        assertEquals(0, standstill(s, rec[0], rec[1]), "no stop-start");
        assertTrue(s.decisions.stream().allMatch(DodgeDecision::sprint));
        assertTrue(s.decisions.stream().noneMatch(DodgeDecision::stuck));
    }

    @Test
    void pressureThatKeepsComingFromAlternatingSidesDoesNotMakeItFlutter() {
        // Two bandits patrol across the lane, one each side, out of phase: the old planner flipped every time one of them turned round.
        DodgeSim s = sim(GridTerrain.open(300), 150.5D, 280.5D)
                .bandit("l", 140.5D, 230.5D, 2.5D, 0).bandit("r", 162.5D, 210.5D, -2.5D, 0).bandit("l2", 138.5D, 180.5D, 2.0D, 0)
                .bandit("r2", 165.5D, 160.5D, -2.0D, 0);
        for (int i = 0; i < 400; i++) {
            s.tick();
            for (double[] b : s.bandits.values()) {
                if (b[0] < 128.0D || b[0] > 172.0D) {
                    b[2] = -b[2];                  // they walk back and forth across the lane
                }
            }
        }
        assertTrue(s.minDistanceSeen >= 5.0D, "closest " + s.minDistanceSeen);
        assertTrue(sharpTurns(s, 40.0D) <= 6, "sharp turns in 20 s: " + sharpTurns(s, 40.0D));
        assertTrue(s.decisions.stream().filter(d -> d.action() == DodgeAction.RECOVER).count() == 0, "no recover");
    }

    @Test
    void aDeadEndPocketIsLeftWithoutTouchingTheWallsOrStopping() {
        // A U shaped pocket open to the south: the player runs in from the south at full speed and has to turn round inside.
        GridTerrain t = GridTerrain.open(100);
        for (int z = 30; z <= 70; z++) {
            t = t.with(41, z, '#').with(59, z, '#');
        }
        for (int x = 41; x <= 59; x++) {
            t = t.with(x, 30, '#');
        }
        double[][] rec = new double[2][];
        DodgeSim s = runRecording(sim(t, 50.5D, 68.5D), 300, rec);
        assertEquals(0, s.contactTicks, "never touched a wall");
        assertTrue(s.decisions.stream().noneMatch(DodgeDecision::stuck), "the pocket is left by planning, not by the stuck detector");
        assertEquals(0, standstill(s, rec[0], rec[1]), "it never stood still");
        assertTrue(s.z > 71.0D || s.x < 40.0D || s.x > 60.0D, "out of the pocket: " + s.x + "," + s.z);
    }

    @Test
    void bothSidesWithBanditsAndAGapAheadIsTakenCleanlyAtFullSpeed() {
        DodgeSim s = sim(GridTerrain.open(200), 100.5D, 180.5D)
                .bandit("l1", 88.5D, 150.5D, 0, 0).bandit("l2", 90.5D, 130.5D, 0, 0)
                .bandit("r1", 112.5D, 150.5D, 0, 0).bandit("r2", 110.5D, 130.5D, 0, 0);
        double[][] rec = new double[2][];
        runRecording(s, 200, rec);
        assertTrue(s.minDistanceSeen >= 6.5D, "closest " + s.minDistanceSeen);
        assertTrue(s.z < 130.0D, "through the lane between them (56 blocks in 200 ticks), z=" + s.z);
        assertEquals(0, standstill(s, rec[0], rec[1]));
        assertTrue(sharpTurns(s, 40.0D) <= 2);
    }
}
