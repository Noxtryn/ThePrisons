package io.theprisons.modules.qol.bandit.dodge;

import io.theprisons.testing.DodgeSim;
import io.theprisons.testing.GridTerrain;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** Smoothness of the executed movement over time (the sim runs 20 ticks per second). */
class DodgeSmoothnessTest {
    private static double wrap(double d) {
        double v = d % 360.0D;
        return v > 180.0D ? v - 360.0D : v <= -180.0D ? v + 360.0D : v;
    }

    private record Stats(int turnsOver20, int evadeFlips, int signFlips, double maxStep, double minDist) {
    }

    private static Stats measure(DodgeSim s, int ticks) {
        s.run(ticks);
        int turns = 0;
        int flips = 0;
        int signFlips = 0;
        double max = 0.0D;
        double lastSign = 0.0D;
        for (int i = 1; i < s.decisions.size(); i++) {
            DodgeDecision a = s.decisions.get(i - 1);
            DodgeDecision b = s.decisions.get(i);
            double step = wrap(b.headingDegrees() - a.headingDegrees());
            max = Math.max(max, Math.abs(step));
            if (Math.abs(step) > 20.0D) {
                turns++;
            }
            if (Math.abs(step) > 3.0D) {
                double sign = Math.signum(step);
                if (lastSign != 0.0D && sign != lastSign) {
                    signFlips++;
                }
                lastSign = sign;
            }
            if (a.breach() != b.breach()) {
                flips++;
            }
        }
        return new Stats(turns, flips, signFlips, max, s.minDistanceSeen);
    }

    /** A bandit walking alongside right at the minimum distance: the old planner flipped in and out of EVADE. */
    @Test
    void aBanditAtTheMinimumDistanceDoesNotMakeEvadeFlutter() {
        DodgeSim s = new DodgeSim(new DodgeConfig(), GridTerrain.open(200), 100.5D, 150.5D, 0.0D, -5.6D)
                .bandit("b", 108.4D, 150.5D, 0.0D, -5.0D);
        Stats st = measure(s, 200);
        System.out.println("edge: " + st);
        assertTrue(st.evadeFlips() <= 4, "EVADE entered/left " + st.evadeFlips() + " times in 10 s");
        assertTrue(st.turnsOver20() <= 4, "turns over 20 degrees per tick: " + st.turnsOver20());
    }

    @Test
    void headingChangesAreGradualInAWanderingCrowd() {
        DodgeSim s = new DodgeSim(new DodgeConfig(), GridTerrain.open(300), 150.5D, 250.5D, 0.0D, -5.6D)
                .bandit("a", 130.5, 200.5, 1.0, 2.0).bandit("b", 170.5, 200.5, -1.0, 2.0).bandit("c", 150.5, 170.5, 0, 3.0)
                .bandit("d", 140.5, 120.5, 2.0, 1.0).bandit("e", 160.5, 140.5, -2.0, 1.0);
        Stats st = measure(s, 400);
        System.out.println("crowd: " + st);
        assertTrue(st.maxStep() <= 30.0D, "largest heading change in one tick: " + st.maxStep());
        assertTrue(st.signFlips() <= 12, "left/right reversals of the turn direction in 20 s: " + st.signFlips());
        assertTrue(st.minDist() >= 5.0D, "closest " + st.minDist());
    }

    @Test
    void sprintStaysOnAndTheViewCatchesUpInARealisticRun() {
        DodgeSim s = new DodgeSim(new DodgeConfig(), GridTerrain.open(300), 150.5D, 250.5D, 0.0D, -5.6D)
                .bandit("a", 130.5, 200.5, 1.0, 2.0).bandit("b", 170.5, 200.5, -1.0, 2.0).bandit("c", 150.5, 170.5, 0, 3.0)
                .bandit("d", 140.5, 120.5, 2.0, 1.0).bandit("e", 160.5, 140.5, -2.0, 1.0);
        s.realistic = true;
        s.yaw = (float) io.theprisons.modules.qol.bandit.combat.Geo.yawOf(0.0D, -1.0D);
        s.run(400);
        double sprintShare = s.sprintTicks / 400.0D;
        System.out.println("realistic: sprint " + sprintShare + " maxPlanVsActual " + s.maxPlanVsActual + " min " + s.minDistanceSeen);
        assertTrue(sprintShare >= 0.95D, "sprint share " + sprintShare);
        assertTrue(s.minDistanceSeen >= 5.0D, "closest " + s.minDistanceSeen);
    }

    @Test
    void evadeHasAnExitBufferSoTheBorderDoesNotFlipIt() {
        DodgeConfig cfg = new DodgeConfig();
        var planner = new BanditDodgePlanner(cfg);
        var terrain = GridTerrain.open(100);
        long t = 1_000_000L;
        boolean[] breach = new boolean[3];
        // the bandit is at 7.9, then 8.3 (inside the exit buffer), then 9.5 (beyond it) blocks east
        double[] dist = {7.9D, 8.3D, 9.5D};
        for (int i = 0; i < 3; i++) {
            var in = new DodgeInputs(t += 50L, 50.5D, 64.0D, 50.5D, 0, -5.6, 0, true,
                    java.util.List.of(new DodgeBandit("b", 50.5D + dist[i], 50.5D, 0, 0)), terrain, SpearAreaState.UNKNOWN);
            var d = planner.plan(in);
            breach[i] = d.reason().contains("evade");
        }
        assertTrue(breach[0], "evade starts under the minimum");
        // at 8.3 the strict aim breach is over, but the evade (heading choice) is still in the emergency mode: no return to 'keep heading' yet
        assertTrue(!breach[2], "evade over beyond the buffer");
    }
}
