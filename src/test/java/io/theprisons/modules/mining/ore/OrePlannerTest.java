package io.theprisons.modules.mining.ore;

import io.theprisons.core.nav.Pos;
import io.theprisons.core.nav.Walkability;
import io.theprisons.core.world.BlockKeys;
import io.theprisons.testing.TestMine;
import org.junit.jupiter.api.Test;

import java.util.function.IntPredicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OrePlannerTest {
    private static final IntPredicate REDSTONE = key -> key == BlockKeys.key(TestMine.REDSTONE_ORE);
    private static final long START = Pos.pack(0, 1, 0);
    /** Minecraft yaw: -90 = towards +x, 90 = towards -x. */
    private static final float EAST = -90.0F;
    private static final float WEST = 90.0F;

    /** A 3 wide tunnel along x from -55 to 55, floor y 0, air y 1-3. */
    private static TestMine tunnel() {
        return new TestMine(-60, -3, -6, 60, 8, 6).carve(-55, 1, -1, 55, 3, 1);
    }

    private static void floorOre(TestMine mine, int fromX, int toX) {
        for (int x = fromX; x <= toX; x++) {
            for (int z = -1; z <= 1; z++) {
                mine.ore(x, 0, z, TestMine.REDSTONE_ORE);
            }
        }
    }

    private static OrePlanner.Plan plan(TestMine mine, float heading, long[] keep, OrePlanner.Avoid avoid) {
        return OrePlanner.plan(mine, REDSTONE, new Walkability(mine, 3), START, heading, keep, avoid, zone -> 1.0D,
                OrePlanner.Params.DEFAULT, () -> false);
    }

    private static int endX(OrePlanner.Plan plan) {
        int[][] wp = plan.waypoints();
        return wp[wp.length - 1][0];
    }

    @Test
    void goesWhereTheOreIs() {
        TestMine mine = tunnel();
        floorOre(mine, -45, -35);
        OrePlanner.Plan plan = plan(mine, EAST, null, null);
        assertTrue(plan.found(), plan.reason());
        assertTrue(endX(plan) <= -30, "towards the ore in the west, ends at " + endX(plan));
        assertTrue(plan.ores() >= 20, "mines the patch on the way: " + plan.ores());
    }

    @Test
    void straightOnFirstWhenBothSidesAreEqual() {
        TestMine mine = tunnel();
        floorOre(mine, -30, -20);
        floorOre(mine, 20, 30);
        assertTrue(endX(plan(mine, EAST, null, null)) > 0, "facing east: east");
        assertTrue(endX(plan(mine, WEST, null, null)) < 0, "facing west: west");
    }

    @Test
    void tooMuchStoneTurnsToTheOtherDirection() {
        TestMine mine = tunnel();
        floorOre(mine, -30, -20);
        floorOre(mine, 20, 30);
        OrePlanner.Plan plan = plan(mine, EAST, null, new OrePlanner.Avoid(0.5D, 0.5D, EAST));
        assertTrue(plan.found(), plan.reason());
        assertTrue(endX(plan) < 0, "another direction than east, ends at " + endX(plan));
    }

    @Test
    void longRouteGoesOnFromPatchToPatch() {
        TestMine mine = tunnel();
        floorOre(mine, 8, 14);
        floorOre(mine, 26, 32);
        floorOre(mine, 44, 50);
        OrePlanner.Plan plan = plan(mine, EAST, null, null);
        assertTrue(plan.found(), plan.reason());
        assertTrue(endX(plan) >= 40, "3 legs reach the last patch, ends at " + endX(plan));
        assertTrue(plan.ores() >= 50, "ores " + plan.ores());
    }

    @Test
    void oreRightAheadIsNoShortRoute() {
        TestMine mine = tunnel();
        // Ore all along the tunnel: the route may not end a few blocks away (that made the player turn every second).
        floorOre(mine, -50, 50);
        OrePlanner.Plan plan = plan(mine, EAST, null, null);
        assertTrue(plan.found(), plan.reason());
        assertTrue(plan.cost() >= OrePlanner.MIN_GOAL, "route of " + plan.cost() + " blocks");
        assertTrue(endX(plan) > 0, "straight on east, ends at " + endX(plan));
    }

    @Test
    void stoneOnlyIsNoGoal() {
        TestMine mine = tunnel();
        mine.ore(30, 0, 0, TestMine.REDSTONE_ORE);
        OrePlanner.Plan plan = plan(mine, EAST, null, null);
        assertFalse(plan.found(), "a lone ore in a stone tunnel is not worth a route");
    }

    @Test
    void keepsTheRouteWhenNothingIsClearlyBetter() {
        TestMine mine = tunnel();
        floorOre(mine, 20, 30);
        OrePlanner.Plan first = plan(mine, EAST, null, null);
        assertTrue(first.found());
        OrePlanner.Plan again = plan(mine, EAST, first.nodes(), null);
        assertTrue(again.kept(), "same world: the route stays");
        floorOre(mine, -30, -12);
        assertTrue(plan(mine, EAST, first.nodes(), null).kept(), "more ore behind: straight on first, the route stays");
        OrePlanner.Plan better = plan(mine, WEST, first.nodes(), null);
        assertFalse(better.kept(), "facing the richer side: a new route");
        assertTrue(endX(better) < 0);
    }

    @Test
    void waysRunInTheMiddleOfTheTunnel() {
        TestMine mine = new TestMine(-10, -3, -8, 60, 8, 8).carve(-5, 1, -2, 55, 3, 2);
        for (int x = 40; x <= 50; x++) {
            for (int z = -2; z <= 2; z++) {
                mine.ore(x, 0, z, TestMine.REDSTONE_ORE);
            }
        }
        long start = Pos.pack(0, 1, 2);
        OrePlanner.Plan plan = OrePlanner.plan(mine, REDSTONE, new Walkability(mine, 3, true,
                        OrePlanner.centred(mine, it.unimi.dsi.fastutil.longs.Long2DoubleMaps.EMPTY_MAP)), start, EAST, null, null,
                zone -> 1.0D, OrePlanner.Params.DEFAULT, () -> false);
        assertTrue(plan.found(), plan.reason());
        int middle = 0;
        for (long node : plan.nodes()) {
            if (Pos.x(node) > 5 && Pos.x(node) < 35 && Pos.z(node) == 0) {
                middle++;
            }
        }
        assertTrue(middle >= 25, "the long stretch runs in the middle (z 0), not along the wall: " + middle);
    }

    @Test
    void waypointsAreTheCorners() {
        long[] nodes = new long[21];
        for (int i = 0; i <= 10; i++) {
            nodes[i] = Pos.pack(i, 1, 0);
            nodes[10 + i] = Pos.pack(10, 1, i);
        }
        int[][] wp = OrePlanner.waypoints(nodes);
        assertEquals(3, wp.length);
        assertEquals(10, wp[1][0]);
        assertEquals(0, wp[1][2]);
    }

    @Test
    void poorMeansThreeQuartersStone() {
        assertTrue(OrePlanner.poor(2, 10));
        assertFalse(OrePlanner.poor(3, 10));
        assertFalse(OrePlanner.poor(1, 4), "too little floor known to judge");
    }

    @Test
    void fromACaveTheRichTunnelIsTheGoal() {
        // A 30x30 cave without ore; a 5 wide tunnel leaves it east with ore on its floor, a poorer one north.
        TestMine mine = new TestMine(-40, -3, -60, 90, 8, 40).carve(-15, 1, -15, 15, 4, 15)
                .carve(15, 1, -2, 70, 3, 2).carve(-2, 1, -50, 2, 3, -15);
        for (int x = 30; x <= 60; x++) {
            for (int z = -2; z <= 2; z++) {
                mine.ore(x, 0, z, TestMine.REDSTONE_ORE);
            }
        }
        mine.ore(0, 0, -40, TestMine.REDSTONE_ORE);
        mine.ore(1, 0, -40, TestMine.REDSTONE_ORE);
        OrePlanner.Plan plan = OrePlanner.tunnel(mine, REDSTONE, new Walkability(mine, 3).climbCost(OrePlanner.CLIMB_COST),
                Pos.pack(0, 1, 0), -90.0F, zone -> 1.0D, OrePlanner.Params.DEFAULT, () -> false);
        assertTrue(plan.found(), plan.reason());
        int[] goal = plan.waypoints()[plan.waypoints().length - 1];
        assertTrue(goal[0] > 15, "into the east tunnel: " + java.util.Arrays.toString(goal));
        assertTrue(OrePlanner.tunnelWidth(new Walkability(mine, 3), goal[0], goal[1], goal[2]) <= OrePlanner.TUNNEL_MAX_WIDTH);
    }

    @Test
    void aFlatCurveBeatsAStairUpAndDownAgain() {
        // From (0, 0) to (20, 0): straight on over a 3 high hump (stairs up and down), or round it flat (6 blocks aside).
        TestMine mine = new TestMine(-5, -3, -10, 30, 10, 10).carve(-2, 1, -1, 22, 3, 1).carve(-2, 1, -8, 22, 3, -6)
                .carve(-2, 1, -5, 0, 3, -2).carve(20, 1, -5, 22, 3, -2);
        for (int x = 8; x <= 12; x++) {
            int h = 3 - Math.abs(x - 10);
            mine.fill(x, 1, -1, x, h, 1);
            mine.carve(x, h + 1, -1, x, h + 3, 1);
        }
        Walkability flat = new Walkability(mine, 3).climbCost(OrePlanner.CLIMB_COST);
        io.theprisons.core.nav.PathSearch.Result r = io.theprisons.core.nav.PathSearch.findPath(flat, Pos.pack(0, 1, 0),
                it.unimi.dsi.fastutil.longs.LongList.of(Pos.pack(20, 1, 0)), 50_000, 64, 0L, () -> false);
        assertTrue(r.found());
        int maxY = 0;
        for (long n : r.path().nodes()) {
            maxY = Math.max(maxY, Pos.y(n));
        }
        assertEquals(1, maxY, "stays on the same height (the flat curve round the hump)");
    }

    @Test
    void ofTwoEqualWaysTheLearnedOneIsTaken() {
        // From (0, 0) to (20, 0): round a block either north (z -4) or south (z 4) - the same length; the south way is a
        // learned route's middle line.
        TestMine mine = new TestMine(-5, -3, -10, 30, 8, 10).carve(-2, 1, -4, 22, 3, 4).fill(2, 1, -2, 18, 3, 2);
        it.unimi.dsi.fastutil.longs.LongOpenHashSet south = new it.unimi.dsi.fastutil.longs.LongOpenHashSet();
        for (int x = -2; x <= 22; x++) {
            for (int z = 3; z <= 4; z++) {
                south.add(Pos.pack(x, 0, z));
            }
        }
        Walkability walk = new Walkability(mine, 3, true,
                OrePlanner.preferring(new it.unimi.dsi.fastutil.longs.Long2DoubleOpenHashMap(), south));
        io.theprisons.core.nav.PathSearch.Flood flood = io.theprisons.core.nav.PathSearch.flood(walk,
                Pos.pack(0, 1, 0), 50_000, 64, Double.POSITIVE_INFINITY, 0L, () -> false);
        long[] nodes = flood.pathTo(Pos.pack(20, 1, 0)).nodes();
        int maxZ = Integer.MIN_VALUE;
        for (long n : nodes) {
            maxZ = Math.max(maxZ, Pos.z(n));
        }
        assertTrue(maxZ >= 3, "over the learned (south) way");
    }
}
