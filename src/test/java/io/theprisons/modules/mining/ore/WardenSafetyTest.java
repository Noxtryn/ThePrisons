package io.theprisons.modules.mining.ore;

import io.theprisons.core.nav.Pos;
import io.theprisons.core.nav.PathSearch;
import io.theprisons.core.nav.PathStraightener;
import io.theprisons.core.nav.Walkability;
import io.theprisons.core.world.BlockKeys;
import io.theprisons.testing.TestMine;
import it.unimi.dsi.fastutil.longs.Long2DoubleOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongSet;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class WardenSafetyTest {
    private static final double[][] WARDENS = {{0.0D, 1.0D, 0.0D}};

    @Test void pathfinderAndStraightenerGoAroundTheCircle() {
        TestMine mine = new TestMine(-40, -3, -40, 40, 6, 40).carve(-36, 1, -36, 36, 3, 36);
        var safe = WardenSafety.penalties(new Long2DoubleOpenHashMap(), WARDENS, -25.5, 1, 0.5);
        var walk = new Walkability(mine, 3, true, safe);
        var result = PathSearch.findPath(walk, Pos.pack(-26, 1, 0), LongSet.of(Pos.pack(25, 1, 0)),
                60000, 70, 0L, () -> false);
        assertNotNull(result.path(), result.reason());
        var path = PathStraightener.apply(result.path(), walk);
        for (int i = 0; i < path.size(); i++) {
            assertTrue(Math.hypot(path.x(i), path.z(i)) >= WardenSafety.DISTANCE);
            if (i > 0) assertFalse(WardenSafety.blocks(WARDENS, path.x(i - 1), 1, path.z(i - 1), path.x(i), path.z(i)));
            if (path.straight() != null && path.straight()[i] > i) {
                int to = path.straight()[i];
                assertFalse(WardenSafety.blocks(WARDENS, path.x(i), 1, path.z(i), path.x(to), path.z(to)));
            }
        }
    }

    @Test void rejectsCircleEntryAndChordsButAllowsTangentsAndEscape() {
        assertTrue(WardenSafety.blocks(WARDENS, -20, 1, 0, 20, 0));
        assertTrue(WardenSafety.blocks(WARDENS, -16, 1, 0, -14, 0));
        assertFalse(WardenSafety.blocks(WARDENS, -20, 1, 15, 20, 15));
        assertFalse(WardenSafety.blocks(WARDENS, -14, 1, 0, -16, 0));
        assertTrue(WardenSafety.blocks(WARDENS, -14, 1, 0, 16, 0), "cannot cross the centre to escape");
        assertFalse(WardenSafety.blocks(WARDENS, -20, 66, 0, 20, 0));
        assertTrue(WardenSafety.blocks(WARDENS, -20, 65, 0, 20, 0));
        assertFalse(WardenSafety.blocks(new double[0][], -20, 1, 0, 20, 0));
    }

    @Test void plannerProtectsCellCornersAndPreservesOtherObstaclesAndSnapshot() {
        var base = new Long2DoubleOpenHashMap();
        base.put(Pos.pack(-30, 1, 0), Double.POSITIVE_INFINITY);
        double[][] live = {{0, 1, 0}};
        var safe = WardenSafety.penalties(base, live, -30, 1, 0);
        live[0][0] = 100;
        assertEquals(Double.POSITIVE_INFINITY, safe.get(Pos.pack(0, 1, 0)));
        assertEquals(Double.POSITIVE_INFINITY, safe.get(Pos.pack(15, 1, 0)), "centre safe, corner unsafe");
        assertEquals(0.0D, safe.get(Pos.pack(16, 1, 0)));
        assertEquals(Double.POSITIVE_INFINITY, safe.get(Pos.pack(-30, 1, 0)));
        assertEquals(0.0D, safe.get(Pos.pack(0, 66, 0)));
    }

    @Test void guardExcursionsCannotOverrideWardenSafety() {
        GuardArea guards = new GuardArea();
        guards.update(WARDENS, -25, 1, 0, 0L);
        guards.outsideBudget(8);
        guards.roam(true);
        guards.corridor(new long[]{Pos.pack(0, 1, 0)});
        var safe = WardenSafety.penalties(new Long2DoubleOpenHashMap(), WARDENS, -25, 1, 0);
        var combined = guards.penalties(safe, -25, 1, 0);
        assertEquals(Double.POSITIVE_INFINITY, combined.get(Pos.pack(0, 1, 0)),
                "a permitted guard corridor is still forbidden inside the warden circle");
        assertTrue(WardenSafety.blocks(new double[][]{{0, 1, 0}, {-30, 1, 0}}, -14, 1, 0, -20, 0),
                "escaping one warden must not approach another");
    }

    @Test void bothSteeringEnginesRejectOreRichWardenLaneInFreeAndGuidedModes() {
        TestMine mine = new TestMine(-40, -3, -40, 40, 6, 40).carve(-36, 1, -36, 36, 3, 36);
        for (int x = -36; x <= 36; x++) for (int z = -36; z <= 36; z++) {
            mine.ore(x, 0, z, TestMine.REDSTONE_ORE);
        }
        for (boolean guided : new boolean[]{false, true}) {
            ClassicSteer classic = new ClassicSteer();
            TunnelSteer tunnel = new TunnelSteer();
            if (guided) {
                classic.guide(new int[]{-25, 1, 0}, new int[]{25, 1, 0});
                tunnel.guide(new int[]{-25, 1, 0}, new int[]{25, 1, 0});
            }
            for (var d : new TunnelSteer.Decision[]{
                    classic.decide(mine, k -> k == BlockKeys.key(TestMine.REDSTONE_ORE), k -> false,
                            -16, 1, 0, -90, true, LongSet.of(), zone -> 1, WARDENS, 0),
                    tunnel.decide(mine, k -> k == BlockKeys.key(TestMine.REDSTONE_ORE), k -> false,
                            -16, 1, 0, -90, true, LongSet.of(), zone -> 1, WARDENS, 0)}) {
                if (!d.forward()) continue;
                double angle = Math.toRadians(d.heading());
                assertFalse(WardenSafety.blocks(WARDENS, -16, 1, 0,
                        -16 - Math.sin(angle) * d.free(), Math.cos(angle) * d.free()),
                        "guided=" + guided + ", heading=" + d.heading() + ", free=" + d.free());
            }
        }
    }
}
