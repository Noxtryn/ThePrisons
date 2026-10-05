package io.theprisons.core.movement;

import io.theprisons.core.movement.SteeringLogic.Status;
import io.theprisons.core.nav.Cell;
import io.theprisons.core.nav.NavigationPath;
import io.theprisons.core.nav.PathSearch;
import io.theprisons.core.nav.Pos;
import io.theprisons.testing.TestMine;
import io.theprisons.core.nav.VoxelView;
import io.theprisons.core.nav.Walkability;
import io.theprisons.core.control.RotationMode;
import io.theprisons.core.control.RotationMath;
import it.unimi.dsi.fastutil.longs.LongList;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Movement acceptance tests M-W, driven through {@link PlayerSim}. */
class SteeringLogicTest {
    private static final SteeringLogic.Settings SETTINGS = new SteeringLogic.Settings(1.6D, 0.5D, true, true, false);
    /** Upper bound for a single tick of a human-like turn. */
    private static final float MAX_YAW = 30.0F;

    private record Run(Status end, int ticks, double maxCross, Set<RotationMode> modes, List<SteeringLogic.Output> outputs,
                       float maxYawStep, PlayerSim sim) {
    }

    private static NavigationPath plan(VoxelView view, int sx, int sy, int sz, int gx, int gy, int gz) {
        PathSearch.Result result = PathSearch.findPath(new Walkability(view, 3), Pos.pack(sx, sy, sz),
                LongList.of(Pos.pack(gx, gy, gz)), 100_000, 64, 0L, () -> false);
        assertTrue(result.found(), result.reason());
        return result.path();
    }

    /** Runs until the path ends or a non-following status; rotation is applied unless {@code fixedYaw}. */
    private static Run run(NavigationPath path, PlayerSim sim, boolean fixedYaw, double obstacleX, int limit) {
        SteeringLogic steering = new SteeringLogic();
        steering.setPath(path);
        double maxCross = 0.0D;
        float maxYawStep = 0.0F;
        Set<RotationMode> modes = EnumSet.noneOf(RotationMode.class);
        List<SteeringLogic.Output> outputs = new ArrayList<>();
        for (int tick = 0; tick < limit; tick++) {
            SteeringLogic.Output out = steering.tick(sim.state(), SETTINGS);
            outputs.add(out);
            modes.add(out.mode());
            if (out.status() == Status.ARRIVED || out.status() == Status.STUCK || out.status() == Status.JUMP_FAILED) {
                return new Run(out.status(), tick, maxCross, modes, outputs, maxYawStep, sim);
            }
            maxCross = Math.max(maxCross, out.crossTrack());
            float before = sim.yaw;
            if (!fixedYaw) {
                sim.rotate(out);
            }
            maxYawStep = Math.max(maxYawStep, Math.abs(RotationMath.wrap(sim.yaw - before)));
            sim.step(out, obstacleX);
        }
        return new Run(Status.FOLLOWING, limit, maxCross, modes, outputs, maxYawStep, sim);
    }

    /** TEST M: left curve. Facing +z (yaw 0), left is +x (yaw -90). */
    @Test
    void leftCurve() {
        TestMine mine = new TestMine(0, 0, 0, 20, 15, 20);
        mine.carve(5, 5, 2, 5, 6, 10).carve(5, 5, 10, 14, 6, 10);
        NavigationPath path = plan(mine, 5, 5, 2, 14, 5, 10);
        PlayerSim sim = new PlayerSim(mine, 5.5, 5, 2.5, 0.0F);
        Run run = run(path, sim, false, Double.NaN, 400);
        assertEquals(Status.ARRIVED, run.end());
        assertTrue(run.modes().contains(RotationMode.TURNING), "head turns into the curve early");
        assertTrue(run.maxCross() < 0.8D, "stays on the path, max cross-track " + run.maxCross());
        assertTrue(Math.abs(RotationMath.wrap(sim.yaw + 90.0F)) < 30.0F, "ends facing +x, yaw " + sim.yaw);
        assertEquals(0, sim.jumps, "no jumps on flat ground");
    }

    /** TEST N: right curve. Facing +z, right is -x (yaw 90). */
    @Test
    void rightCurve() {
        TestMine mine = new TestMine(0, 0, 0, 20, 15, 20);
        mine.carve(14, 5, 2, 14, 6, 10).carve(5, 5, 10, 14, 6, 10);
        NavigationPath path = plan(mine, 14, 5, 2, 5, 5, 10);
        PlayerSim sim = new PlayerSim(mine, 14.5, 5, 2.5, 0.0F);
        Run run = run(path, sim, false, Double.NaN, 400);
        assertEquals(Status.ARRIVED, run.end());
        assertTrue(run.maxCross() < 0.8D, "max cross-track " + run.maxCross());
        assertTrue(Math.abs(RotationMath.wrap(sim.yaw - 90.0F)) < 30.0F, "ends facing -x, yaw " + sim.yaw);
    }

    /** TEST O: the head looks sideways (e.g. at an ore) while the path goes straight: A / D carry the player. */
    @Test
    void strafesWhileLookingSideways() {
        TestMine mine = new TestMine(0, 0, 0, 20, 15, 20);
        mine.carve(2, 5, 2, 10, 6, 14);
        NavigationPath path = plan(mine, 5, 5, 2, 5, 5, 12);
        PlayerSim sim = new PlayerSim(mine, 5.5, 5, 2.5, -90.0F); // looking at +x, walking +z
        Run run = run(path, sim, true, Double.NaN, 300);
        assertEquals(Status.ARRIVED, run.end());
        long strafeTicks = run.outputs().stream().filter(o -> o.right() && !o.forward()).count();
        assertTrue(strafeTicks > run.outputs().size() / 2, "moves with D, not W: " + strafeTicks + "/" + run.outputs().size());
        assertTrue(run.outputs().stream().noneMatch(SteeringLogic.Output::left));
    }

    /** TEST P: a one block ledge on the path is jumped about 0.5 blocks before it, landing on top. */
    @Test
    void jumpsLedgeAtTheRightDistance() {
        TestMine mine = new TestMine(0, 0, 0, 20, 15, 10);
        mine.carve(2, 5, 2, 12, 7, 2).fill(8, 5, 2, 12, 5, 2);
        NavigationPath path = plan(mine, 2, 5, 2, 12, 6, 2);
        PlayerSim sim = new PlayerSim(mine, 2.5, 5, 2.5, -90.0F);
        Run run = run(path, sim, false, 8.0D, 300);
        assertEquals(Status.ARRIVED, run.end());
        assertEquals(1, sim.jumps, "exactly one jump");
        assertTrue(sim.lastJumpGap > 0.2D && sim.lastJumpGap < 0.9D, "jump gap " + sim.lastJumpGap);
        assertEquals(6.0D, sim.y, 1.0E-6D);
        assertTrue(run.modes().contains(RotationMode.OBSTACLE_CHECK));
        assertTrue(run.modes().contains(RotationMode.JUMPING), "rotation coordinated with the jump (TEST U)");
    }

    /** TEST U (failure side): a jump that does not reach the ledge is reported so the route gets re-validated. */
    @Test
    void failedJumpIsReported() {
        TestMine mine = new TestMine(0, 0, 0, 20, 15, 10);
        mine.carve(2, 5, 2, 12, 7, 2).fill(8, 5, 2, 12, 5, 2);
        NavigationPath path = plan(mine, 2, 5, 2, 12, 6, 2);
        PlayerSim sim = new PlayerSim(mine, 2.5, 5, 2.5, -90.0F);
        sim.jumpVelocity = 0.2D; // e.g. slowed / blocked jump
        Run run = run(path, sim, false, 8.0D, 300);
        assertEquals(Status.JUMP_FAILED, run.end());
    }

    /** TEST T: several curves in a row, rotation stays within the per-tick limit and WASD keeps following. */
    @Test
    void zigzagTunnelWithBoundedRotation() {
        TestMine mine = new TestMine(0, 0, 0, 30, 15, 30);
        mine.carve(2, 5, 2, 8, 6, 2).carve(8, 5, 2, 8, 6, 8).carve(8, 5, 8, 14, 6, 8).carve(14, 5, 8, 14, 6, 14);
        NavigationPath path = plan(mine, 2, 5, 2, 14, 5, 14);
        assertEquals(3, path.turns());
        PlayerSim sim = new PlayerSim(mine, 2.5, 5, 2.5, -90.0F);
        Run run = run(path, sim, false, Double.NaN, 600);
        assertEquals(Status.ARRIVED, run.end());
        assertTrue(run.maxYawStep() <= MAX_YAW + 1.0E-3F, "yaw step " + run.maxYawStep());
        assertTrue(run.maxCross() < 0.8D, "max cross-track " + run.maxCross());
        assertTrue(run.outputs().stream().noneMatch(o -> o.forward() && o.back()));
        assertEquals(0, sim.jumps);
    }

    /** TEST W: an obstacle the plan does not know about blocks the player; stuck is detected instead of pushing forever. */
    @Test
    void detectsBeingStuck() {
        TestMine planned = new TestMine(0, 0, 0, 20, 15, 10);
        planned.carve(2, 5, 2, 15, 6, 2);
        NavigationPath path = plan(planned, 2, 5, 2, 15, 5, 2);
        TestMine actual = new TestMine(0, 0, 0, 20, 15, 10);
        actual.carve(2, 5, 2, 15, 6, 2).set(7, 5, 2, Cell.SOLID).set(7, 6, 2, Cell.SOLID);
        PlayerSim sim = new PlayerSim(actual, 2.5, 5, 2.5, -90.0F);
        Run run = run(path, sim, false, Double.NaN, 200);
        assertEquals(Status.STUCK, run.end());
        assertTrue(run.ticks() < 80, "detected after " + run.ticks() + " ticks");
        assertFalse(sim.x > 6.8D);
    }

    /** Straight flat tunnel (TEST E, movement side): forward only, no jumps, no turning mode. */
    @Test
    void straightTunnelWalksForward() {
        TestMine mine = new TestMine(0, 0, 0, 40, 15, 10);
        mine.carve(2, 5, 2, 30, 6, 2);
        NavigationPath path = plan(mine, 2, 5, 2, 30, 5, 2);
        PlayerSim sim = new PlayerSim(mine, 2.5, 5, 2.5, -90.0F);
        Run run = run(path, sim, false, Double.NaN, 400);
        assertEquals(Status.ARRIVED, run.end());
        assertEquals(0, sim.jumps);
        assertFalse(run.modes().contains(RotationMode.TURNING));
        long sideways = run.outputs().stream().filter(o -> o.left() || o.right()).count();
        assertEquals(0, sideways);
    }

    /** Sum of |change of walking direction| over a run, in degrees. */
    private static double headingChanges(Run run) {
        double total = 0.0D;
        for (int i = 1; i < run.outputs().size(); i++) {
            total += Math.abs(RotationMath.wrap((float) (run.outputs().get(i).moveYaw() - run.outputs().get(i - 1).moveYaw())));
        }
        return total;
    }

    /**
     * Straight ahead: a target at ~18° in an open hall is a staircase of straight and diagonal grid steps. With
     * straight runs the player walks one straight line (little heading change); without, it wiggles along the grid.
     */
    @Test
    void straightRunsWalkAStraightLine() {
        TestMine mine = new TestMine(0, 0, 0, 40, 15, 30);
        mine.carve(2, 5, 2, 38, 7, 28);
        NavigationPath grid = plan(mine, 4, 5, 6, 34, 5, 16);
        NavigationPath straight = io.theprisons.core.nav.PathStraightener.apply(grid, new Walkability(mine, 3));
        assertTrue(straight.straight()[0] > 10, "open hall: one run covers most of the path, got " + straight.straight()[0]);

        Run wiggly = run(grid, new PlayerSim(mine, 4.5, 5, 6.5, -70.0F), false, Double.NaN, 600);
        Run direct = run(straight, new PlayerSim(mine, 4.5, 5, 6.5, -70.0F), false, Double.NaN, 600);
        assertEquals(Status.ARRIVED, wiggly.end());
        assertEquals(Status.ARRIVED, direct.end());
        double gridTurns = headingChanges(wiggly);
        double straightTurns = headingChanges(direct);
        assertTrue(straightTurns < gridTurns * 0.5D, "straight " + straightTurns + "° vs grid " + gridTurns + "°");
        assertTrue(direct.ticks() <= wiggly.ticks(), "not slower: " + direct.ticks() + " vs " + wiggly.ticks());
    }

    /** A pillar in the way: the straight run ends before it and the path still goes around (no walking into it). */
    @Test
    void straightRunsRespectObstacles() {
        TestMine mine = new TestMine(0, 0, 0, 40, 15, 30);
        mine.carve(2, 5, 2, 38, 7, 28);
        mine.fill(18, 5, 9, 20, 7, 13);
        NavigationPath grid = plan(mine, 4, 5, 11, 34, 5, 11);
        NavigationPath straight = io.theprisons.core.nav.PathStraightener.apply(grid, new Walkability(mine, 3));
        PlayerSim sim = new PlayerSim(mine, 4.5, 5, 11.5, -90.0F);
        Run direct = run(straight, sim, false, Double.NaN, 800);
        assertEquals(Status.ARRIVED, direct.end());
        assertTrue(direct.outputs().stream().noneMatch(out -> out.status() == Status.OFF_PATH), "never reported off path");
        long bumps = direct.outputs().stream().filter(out -> out.status() == Status.STUCK).count();
        assertEquals(0, bumps);
        for (int x = 18; x <= 20; x++) {
            assertFalse(sim.x > x && sim.x < x + 1 && sim.z > 9 && sim.z < 14, "not inside the pillar");
        }
    }
}
