package com.freelocs.theprisons.modules.mining.ore;

import com.freelocs.theprisons.core.control.RotationMath;
import com.freelocs.theprisons.core.nav.Cell;
import com.freelocs.theprisons.core.nav.NavigationPath;
import com.freelocs.theprisons.core.nav.Pos;
import com.freelocs.theprisons.core.nav.Walkability;
import com.freelocs.theprisons.core.world.BlockKeys;
import com.freelocs.theprisons.testing.TestMine;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import org.junit.jupiter.api.Test;

import java.util.Random;
import java.util.function.IntPredicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LanePlannerTest {
    private static final IntPredicate REDSTONE = key -> key == BlockKeys.key(TestMine.REDSTONE_ORE);
    private static final int SIZE = 15;

    /** Floor height per column: terraces 0 / 1 / 2 / 1 with one-block steps (one surface, several levels). */
    private static int floor(int x, int z) {
        int band = Math.floorMod(x + 15, 32) / 8;
        return new int[]{0, 1, 2, 1}[band] + (z > 8 ? 1 : 0);
    }

    /** Uneven cave: stone, air above the terraced floor up to y 7, ore on the floor surface, the walls and the ceiling. */
    private static TestMine cave(long seed, double surfaceOre) {
        TestMine mine = new TestMine(-20, -4, -20, 20, 12, 20);
        Random random = new Random(seed);
        for (int x = -SIZE; x <= SIZE; x++) {
            for (int z = -SIZE; z <= SIZE; z++) {
                int f = floor(x, z);
                mine.carve(x, f + 1, z, x, 7, z);
                if (random.nextDouble() < surfaceOre) {
                    mine.ore(x, f, z, TestMine.REDSTONE_ORE);
                }
                mine.ore(x, 8, z, TestMine.REDSTONE_ORE); // ceiling
            }
        }
        for (int i = -SIZE; i <= SIZE; i++) {
            for (int y = 1; y <= 7; y++) {
                mine.ore(SIZE + 1, y, i, TestMine.REDSTONE_ORE); // walls
                mine.ore(i, y, -SIZE - 1, TestMine.REDSTONE_ORE);
            }
        }
        return mine;
    }

    private static long stand(TestMine mine, int x, int z) {
        return Pos.pack(x, floor(x, z) + 1, z);
    }

    @Test
    void onlyOresWithOpenSpaceAboveAreSurfaceOres() {
        TestMine mine = cave(1, 1.0D);
        assertTrue(LanePlanner.surfaceOre(mine, REDSTONE, 0, floor(0, 0), 0), "floor");
        assertFalse(LanePlanner.surfaceOre(mine, REDSTONE, 0, 8, 0), "ceiling");
        assertFalse(LanePlanner.surfaceOre(mine, REDSTONE, SIZE + 1, 3, 0), "wall");
    }

    @Test
    void choosesTheStraightLaneWithTheOresAndIgnoresTheRest() {
        TestMine mine = cave(1, 0.0D);
        // A strip of ore going east (+x) on the floor; nothing elsewhere.
        for (int x = 2; x <= 12; x++) {
            mine.ore(x, floor(x, 0), 0, TestMine.REDSTONE_ORE);
        }
        LanePlanner.Plan plan = LanePlanner.plan(mine, REDSTONE, stand(mine, 0, 0), 180.0F, LongSet.of(), LanePlanner.Params.DEFAULT, () -> false);
        assertEquals(LanePlanner.Kind.LANE, plan.kind(), plan.reason());
        NavigationPath path = plan.path();
        assertNotNull(path);
        assertEquals(0, Pos.z(path.goal()), "straight along the strip");
        assertTrue(Pos.x(path.goal()) >= 8, "follows the strip east: " + Pos.x(path.goal()));
        for (long ore : plan.ores()) {
            assertEquals(0, Pos.z(ore));
        }
    }

    @Test
    void walksToTheRichestSpotWhenNothingIsClose() {
        TestMine mine = cave(1, 0.0D);
        for (int x = 8; x <= 12; x++) {
            for (int z = -12; z <= -8; z++) {
                mine.ore(x, floor(x, z), z, TestMine.REDSTONE_ORE);
            }
        }
        LanePlanner.Plan plan = LanePlanner.plan(mine, REDSTONE, stand(mine, -10, 10), 0.0F, LongSet.of(), LanePlanner.Params.DEFAULT, () -> false);
        assertEquals(LanePlanner.Kind.TRAVEL, plan.kind(), plan.reason());
        long goal = plan.path().goal();
        assertTrue(Math.abs(Pos.x(goal) - 10) <= 5 && Math.abs(Pos.z(goal) + 10) <= 5, "ends at the ore patch: " + Pos.toString(goal));
    }

    /** Straight tunnel along x: floor y = 0, air y 1..3, z from -half..half, everything else stone. */
    private static TestMine tunnel(int half) {
        return new TestMine(-4, -3, -12, 40, 8, 12).carve(-2, 1, -half, 36, 3, half);
    }

    @Test
    void walksInTheMiddleOfTheTunnel() {
        TestMine mine = tunnel(3);
        for (int x = -2; x <= 36; x++) {
            for (int z = -3; z <= 3; z++) {
                mine.ore(x, 0, z, TestMine.REDSTONE_ORE);
            }
        }
        LanePlanner.Plan plan = LanePlanner.plan(mine, REDSTONE, Pos.pack(0, 1, -3), -90.0F, LongSet.of(), LanePlanner.Params.DEFAULT, () -> false);
        assertEquals(LanePlanner.Kind.LANE, plan.kind(), plan.reason());
        assertEquals(0, Pos.z(plan.path().goal()), "lane on the middle line of the 7-wide tunnel");
        assertTrue(Pos.x(plan.path().goal()) >= 15, "along the tunnel");
    }

    @Test
    void doesNotClimbAStepedWallAndIgnoresOresOnIt() {
        // Tunnel z -2..2 along x; north of it (z 3..6) a stepped wall of ore rising one block per block, like a
        // jagged cave wall. The floor (y 0) has a little ore along the middle.
        TestMine mine = new TestMine(-4, -3, -12, 40, 12, 12).carve(-2, 1, -2, 36, 8, 8);
        for (int x = -2; x <= 36; x++) {
            for (int step = 1; step <= 6; step++) {
                mine.fill(x, 1, 2 + step, x, step, 2 + step);
                mine.ore(x, step, 2 + step, TestMine.REDSTONE_ORE);
            }
            if (x % 2 == 0) {
                mine.ore(x, 0, 0, TestMine.REDSTONE_ORE);
            }
        }
        Walkability walk = new Walkability(mine, 3);
        assertTrue(LanePlanner.steep(walk, Pos.pack(5, 3, 4)), "a node on the wall steps");
        assertFalse(LanePlanner.steep(walk, Pos.pack(5, 1, 0)), "the tunnel floor");
        LanePlanner.Plan plan = LanePlanner.plan(mine, REDSTONE, Pos.pack(0, 1, 0), -90.0F, LongSet.of(), LanePlanner.Params.DEFAULT, () -> false);
        assertEquals(LanePlanner.Kind.LANE, plan.kind(), plan.reason());
        for (long node : plan.path().nodes()) {
            assertTrue(Pos.y(node) <= 2, "never walks up the wall: " + Pos.toString(node));
        }
        for (long ore : plan.ores()) {
            assertTrue(Pos.y(ore) <= 1, "wall ores are not floor: " + Pos.toString(ore));
        }
    }

    @Test
    void takesTheSidePassageAtTheEndInsteadOfTurningBack() {
        // A 3-wide tunnel along +x ends at a wall at x = 21; a side passage opens to the left (+z... here -z) exactly at
        // the last blocks before the wall. Ore everywhere on the floor, also back the way it came.
        TestMine mine = new TestMine(-4, -3, -30, 30, 8, 8).carve(0, 1, -1, 20, 3, 1).carve(18, 1, -25, 20, 3, -2);
        for (int x = 0; x <= 20; x++) {
            for (int z = -25; z <= 1; z++) {
                if (mine.cell(x, 1, z) == 0) {
                    mine.ore(x, 0, z, TestMine.REDSTONE_ORE);
                }
            }
        }
        LanePlanner.Plan first = LanePlanner.plan(mine, REDSTONE, Pos.pack(1, 1, 0), -90.0F, LongSet.of(), LanePlanner.Params.DEFAULT, () -> false);
        long end = first.path().goal();
        assertEquals(20, Pos.x(end), "straight to the last block before the wall");
        LanePlanner.Plan next = LanePlanner.plan(mine, REDSTONE, end, -90.0F, LongSet.of(first.ores()), LanePlanner.Params.DEFAULT, () -> false);
        assertTrue(Pos.z(next.path().goal()) < -10, "turns into the side passage, not back: " + Pos.toString(next.path().goal()));
    }

    @Test
    void walksStraightOnUntilTheWallWhateverTheOre() {
        // Middle line of a 3-wide tunnel: ores at x 1..6, stone 7..19, ores 20..26, then stone up to the wall.
        TestMine mine = tunnel(1);
        for (int x = 1; x <= 26; x++) {
            if (x <= 6 || x >= 20) {
                mine.ore(x, 0, 0, TestMine.REDSTONE_ORE);
            }
        }
        LanePlanner.Plan plan = LanePlanner.plan(mine, REDSTONE, Pos.pack(0, 1, 0), -90.0F, LongSet.of(), LanePlanner.Params.DEFAULT, () -> false);
        assertEquals(36, Pos.x(plan.path().goal()), "no lane change on the way, the next decision is at the wall");
    }

    @Test
    void keepsWalkingTheWayWhenThereIsNoOreAtAll() {
        TestMine mine = tunnel(1);
        LanePlanner.Plan plan = LanePlanner.plan(mine, REDSTONE, Pos.pack(0, 1, 0), -90.0F, LongSet.of(), LanePlanner.Params.DEFAULT, () -> false);
        assertEquals(LanePlanner.Kind.LANE, plan.kind(), "no waiting: " + plan.reason());
        assertEquals(36, Pos.x(plan.path().goal()), "on along the way it was going, up to the wall");
    }

    @Test
    void movesToAParallelLaneOnlyWhenTheMiddleHasFewOres() {
        TestMine mine = tunnel(6);
        // A 3-wide vein 4 blocks right of the middle; the middle line itself is stone.
        for (int x = -2; x <= 36; x++) {
            for (int z = 3; z <= 5; z++) {
                mine.ore(x, 0, z, TestMine.REDSTONE_ORE);
            }
        }
        LanePlanner.Plan plan = LanePlanner.plan(mine, REDSTONE, Pos.pack(0, 1, 0), -90.0F, LongSet.of(), LanePlanner.Params.DEFAULT, () -> false);
        assertEquals(LanePlanner.Kind.LANE, plan.kind(), plan.reason());
        assertEquals(4, Pos.z(plan.path().goal()), 1, "parallel lane through the vein");
        assertTrue(Pos.x(plan.path().goal()) >= 15, "still along the tunnel");

        // The same vein, but the middle line has plenty of ore too: stay in the middle.
        for (int x = -2; x <= 36; x++) {
            for (int z = -1; z <= 1; z++) {
                mine.ore(x, 0, z, TestMine.REDSTONE_ORE);
            }
        }
        LanePlanner.Plan middle = LanePlanner.plan(mine, REDSTONE, Pos.pack(0, 1, 0), -90.0F, LongSet.of(), LanePlanner.Params.DEFAULT, () -> false);
        assertEquals(0, Pos.z(middle.path().goal()), "stays in the middle");
    }

    @Test
    void learnedAreasTipTheBalanceBetweenEqualBranches() {
        RegionMemory region = new RegionMemory();
        long good = LanePlanner.zoneOf(Pos.pack(20, 1, 0));
        long bad = LanePlanner.zoneOf(Pos.pack(-20, 1, 0));
        for (int i = 0; i < 5; i++) {
            region.recordLane(good, 6.0D, 1000L * i);
            region.recordLane(bad, 2.0D, 1000L * i);
        }
        assertTrue(region.factor(good, 5000L) > 1.0D && region.factor(bad, 5000L) < 1.0D);
        // Two identical tunnel branches east and west of the player: the one that paid off before wins.
        TestMine mine = new TestMine(-40, -3, -6, 40, 8, 6).carve(-36, 1, -1, 36, 3, 1);
        for (int x = -36; x <= 36; x++) {
            if (x != 0) {
                mine.ore(x, 0, 0, TestMine.REDSTONE_ORE);
            }
        }
        java.util.function.LongToDoubleFunction factors = region.snapshot(5000L)::get;
        LanePlanner.Plan plan = LanePlanner.plan(mine, REDSTONE, Pos.pack(0, 1, 0), 0.0F, LongSet.of(), LanePlanner.Params.DEFAULT, factors, () -> false);
        assertTrue(Pos.x(plan.path().goal()) > 0, "takes the branch that paid off before: " + Pos.toString(plan.path().goal()));
    }

    @Test
    void regionMemorySurvivesARestartAndFadesWithTime() {
        RegionMemory region = new RegionMemory();
        long zone = LanePlanner.zoneOf(Pos.pack(20, 1, 0));
        long other = LanePlanner.zoneOf(Pos.pack(-20, 1, 0));
        region.recordLane(zone, 8.0D, 0L);
        region.recordLane(other, 2.0D, 0L);
        region.recordDeadEnd(other, 0L);
        region.recordDeadEnd(other, 0L);
        RegionMemory loaded = new RegionMemory();
        loaded.fromJson(region.toJson());
        assertEquals(region.factor(zone, 0L), loaded.factor(zone, 0L), 1.0E-9D);
        assertEquals(region.factor(other, 0L), loaded.factor(other, 0L), 1.0E-9D);
        assertTrue(loaded.factor(other, 0L) < 0.8D, "a poor dead end is avoided: " + loaded.factor(other, 0L));
        double later = loaded.factor(zone, 24L * 3_600_000L);
        assertTrue(later < loaded.factor(zone, 0L) && later > 1.0D, "fades towards neutral after a day: " + later);
    }

    /**
     * Kinematic loop over the uneven cave with the server's behaviour: a mined ore becomes stone and respawns as ore
     * after a delay. The macro must keep producing straight lanes (no livelock), only ever mine surface ores, and
     * mine many ores per walked block.
     */
    @Test
    void keepsMiningRespawningOresWithStraightLanes() {
        TestMine mine = cave(7, 0.55D);
        long at = stand(mine, 0, 0);
        float heading = 0.0F;
        int mined = 0;
        int walked = 0;
        int lanes = 0;
        int travels = 0;
        int straight = 0;
        int sideSteps = 0;
        int step = 0;
        Long2IntOpenHashMap respawnAt = new Long2IntOpenHashMap();
        LanePlanner.Params params = LanePlanner.Params.DEFAULT;
        for (int decision = 0; decision < 300; decision++) {
            LanePlanner.Plan plan = LanePlanner.plan(mine, REDSTONE, at, heading, LongSet.of(), params, () -> false);
            if (plan.kind() == LanePlanner.Kind.NONE) {
                // Everything is stone right now: wait for respawns.
                step += 20;
                respawn(mine, respawnAt, step);
                continue;
            }
            NavigationPath path = plan.path();
            LongOpenHashSet laneOres = new LongOpenHashSet(plan.ores());
            if (plan.kind() == LanePlanner.Kind.LANE) {
                lanes++;
                // Direction of the last step; a lane may begin with one side step (to run through the middle).
                int last = path.size() - 1;
                int dx = Pos.x(path.nodes()[last]) - Pos.x(path.nodes()[last - 1]);
                int dz = Pos.z(path.nodes()[last]) - Pos.z(path.nodes()[last - 1]);
                // Leading side steps (to the middle of the tunnel / a parallel lane) are allowed, then straight.
                int first = 1;
                while (first < last && (Pos.x(path.nodes()[first]) - Pos.x(path.nodes()[first - 1]) != dx
                        || Pos.z(path.nodes()[first]) - Pos.z(path.nodes()[first - 1]) != dz)) {
                    first++;
                }
                boolean line = true;
                sideSteps += first - 1;
                for (int i = first; i < path.size(); i++) {
                    line &= Pos.x(path.nodes()[i]) - Pos.x(path.nodes()[i - 1]) == dx && Pos.z(path.nodes()[i]) - Pos.z(path.nodes()[i - 1]) == dz;
                }
                straight += line ? 1 : 0;
                heading = RotationMath.yawOf(dx, dz);
            } else {
                travels++;
            }
            for (long ore : laneOres) {
                assertTrue(LanePlanner.surfaceOre(mine, REDSTONE, Pos.x(ore), Pos.y(ore), Pos.z(ore)), "only surface ores are planned");
            }
            for (int i = 1; i < path.size(); i++) {
                long node = path.nodes()[i];
                walked++;
                step++;
                double eyeY = path.feet()[i] + Walkability.EYE_HEIGHT;
                for (long ore : laneOres) {
                    double ddx = Pos.x(ore) - Pos.x(node);
                    double ddy = Pos.y(ore) + 1.0D - eyeY;
                    double ddz = Pos.z(ore) - Pos.z(node);
                    if (ddx * ddx + ddy * ddy + ddz * ddz <= params.reach() * params.reach() && mine.ore(Pos.x(ore), Pos.y(ore), Pos.z(ore)) != 0) {
                        mine.set(Pos.x(ore), Pos.y(ore), Pos.z(ore), Cell.SOLID); // the server turns it into stone
                        respawnAt.put(ore, step + 60);
                        mined++;
                    }
                }
                respawn(mine, respawnAt, step);
            }
            at = path.goal();
        }
        double perBlock = mined / (double) Math.max(1, walked);
        System.out.printf("floor: mined %d, walked %d (%.2f ores per block), lanes %d (%d straight, %d side steps), travels %d%n",
                mined, walked, perBlock, lanes, straight, sideSteps, travels);
        assertTrue(mined > 1500, "keeps mining respawning ores: " + mined);
        assertTrue(perBlock > 1.5D, "a lane takes a band of ore: " + perBlock + " ores per walked block");
        assertTrue(straight >= lanes * 9 / 10, "lanes are straight: " + straight + " of " + lanes);
        for (int x = -SIZE; x <= SIZE; x++) {
            assertTrue(mine.ore(x, 8, 0) != 0, "ceiling untouched");
            assertTrue(mine.ore(SIZE + 1, 3, x) != 0, "wall untouched");
        }
    }

    private static void respawn(TestMine mine, Long2IntOpenHashMap respawnAt, int step) {
        respawnAt.long2IntEntrySet().removeIf(entry -> {
            if (entry.getIntValue() <= step) {
                long pos = entry.getLongKey();
                mine.ore(Pos.x(pos), Pos.y(pos), Pos.z(pos), TestMine.REDSTONE_ORE);
                return true;
            }
            return false;
        });
    }
}
