package io.theprisons.modules.mining.ore;

import io.theprisons.core.control.RotationMath;
import io.theprisons.core.nav.Pos;
import io.theprisons.core.world.BlockKeys;
import io.theprisons.testing.TestMine;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import org.junit.jupiter.api.Test;

import java.util.function.IntPredicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TunnelSteerTest {
    private static final IntPredicate REDSTONE = key -> key == BlockKeys.key(TestMine.REDSTONE_ORE);
    private static final float EAST = -90.0F;
    private static final float NORTH = 180.0F;
    private static final float SOUTH = 0.0F;

    private static TunnelSteer.Decision decide(TunnelSteer steer, TestMine mine, double x, double z, float yaw, LongSet walked) {
        return steer.decide(mine, REDSTONE, x, 1.0D, z, yaw, true, walked, zone -> 1.0D, 0L);
    }

    private static void oreFloor(TestMine mine, int x1, int z1, int x2, int z2) {
        for (int x = x1; x <= x2; x++) {
            for (int z = z1; z <= z2; z++) {
                mine.ore(x, 0, z, TestMine.REDSTONE_ORE);
            }
        }
    }

    @Test
    void keepsToTheMiddleOfTheTunnel() {
        // 7-wide tunnel along +x (z -3..3); the player walks east close to the south wall (z = 2.5).
        TestMine mine = new TestMine(-4, -3, -8, 40, 6, 8).carve(-2, 1, -3, 36, 3, 3);
        oreFloor(mine, -2, -3, 36, 3);
        TunnelSteer.Decision d = decide(new TunnelSteer(), mine, 0.5D, 2.5D, EAST, LongSet.of());
        assertEquals(EAST, d.heading(), 20.0F, "along the tunnel (possibly slanting towards the middle)");
        // Facing east, north (-z, the middle) is to the left: a smaller yaw.
        assertTrue(d.yaw() < EAST - 5.0F, "steers towards the middle: " + d.yaw());
        TunnelSteer.Decision middle = decide(new TunnelSteer(), mine, 0.5D, 0.5D, EAST, LongSet.of());
        assertEquals(EAST, middle.yaw(), 3.0F, "in the middle: straight on");
    }

    @Test
    void takesTheBranchWithOreAtAJunction() {
        // Tunnel east ends at a wall (x 12); branches north and south, only the north one has ore.
        TestMine mine = new TestMine(-4, -3, -30, 30, 6, 30).carve(0, 1, -1, 12, 3, 1).carve(10, 1, -20, 12, 3, 20);
        oreFloor(mine, 10, -20, 12, -3);
        TunnelSteer.Decision d = decide(new TunnelSteer(), mine, 11.5D, 0.5D, EAST, LongSet.of());
        assertTrue(Math.abs(RotationMath.wrap(d.heading() - NORTH)) < 20.0F, "north branch: " + d.heading());
    }

    @Test
    void doesNotWalkBackOverTheBlocksJustWalked() {
        // A straight tunnel with the same ore both ways; the west half was just walked.
        TestMine mine = new TestMine(-40, -3, -4, 40, 6, 4).carve(-36, 1, -1, 36, 3, 1);
        oreFloor(mine, -36, -1, 36, 1);
        LongOpenHashSet walked = new LongOpenHashSet();
        for (int x = -20; x <= 0; x++) {
            walked.add(Pos.pack(x, 1, 0));
        }
        // Standing at a crossing facing south (a side way ends here): west and east are the same turn away.
        mine.carve(0, 1, -1, 0, 3, 1);
        TunnelSteer.Decision d = decide(new TunnelSteer(), mine, 0.5D, 0.5D, SOUTH, walked);
        assertEquals(EAST, d.heading(), 1.0F, "east, not back west over the way it just walked");
    }

    @Test
    void walksUpAnyStairWhereTheOreIs() {
        // East: a 3-step stair (no flat top needed) with ore on it and above. South: an empty tunnel.
        TestMine mine = new TestMine(-4, -3, -4, 30, 10, 30).carve(0, 1, -1, 12, 6, 1).carve(0, 1, 2, 1, 3, 20);
        for (int s = 1; s <= 3; s++) {
            mine.fill(2 + s, 1, -1, 12, s, 1);
        }
        for (int x = 3; x <= 12; x++) {
            for (int z = -1; z <= 1; z++) {
                mine.ore(x, Math.min(x - 2, 3), z, TestMine.REDSTONE_ORE);
            }
        }
        TunnelSteer.Decision d = decide(new TunnelSteer(), mine, 0.5D, 0.5D, EAST, LongSet.of());
        assertEquals(EAST, d.heading(), 1.0F, "up the stair to the ore");
        assertTrue(d.free() >= 6.0D, "and it is a real way: " + d.free());
    }

    @Test
    void keepsWalkingStraightInsteadOfTurningRoundForMoreOreBehind() {
        // Straight tunnel: some ore ahead (east), much more behind (west).
        TestMine mine = new TestMine(-40, -3, -4, 40, 6, 4).carve(-36, 1, -1, 36, 3, 1);
        oreFloor(mine, -36, -1, -1, 1);
        for (int x = 2; x <= 36; x += 3) {
            mine.ore(x, 0, 0, TestMine.REDSTONE_ORE);
        }
        TunnelSteer steer = new TunnelSteer();
        for (int i = 0; i < 20; i++) {
            TunnelSteer.Decision d = decide(steer, mine, 0.5D + i * 0.2D, 0.5D, EAST, LongSet.of());
            assertEquals(EAST, d.heading(), 1.0F, "straight on while there is still ore ahead");
        }
    }

    @Test
    void curvesTowardsTheDenserOreNotOnlyTheOreOnItsStrip() {
        // Open flat plane. Straight on: a thin line of ore from 7 blocks on. 30° to the left: a wide, dense field.
        TestMine mine = new TestMine(-40, -3, -40, 40, 6, 40).carve(-36, 1, -36, 36, 3, 36);
        oreFloor(mine, 7, 0, 30, 0);
        float toField = RotationMath.wrap(EAST - 30.0F);
        double rad = Math.toRadians(toField);
        for (int d = 6; d <= 28; d++) {
            for (int w = -4; w <= 4; w++) {
                int bx = (int) Math.floor(0.5D - Math.sin(rad) * d + Math.cos(rad) * w);
                int bz = (int) Math.floor(0.5D + Math.cos(rad) * d + Math.sin(rad) * w);
                mine.ore(bx, 0, bz, TestMine.REDSTONE_ORE);
            }
        }
        TunnelSteer.Decision d = decide(new TunnelSteer(), mine, 0.5D, 0.5D, EAST, LongSet.of());
        assertTrue(Math.abs(RotationMath.wrap(d.heading() - toField)) <= 16.0F,
                "towards the dense field (" + toField + "): " + d.heading());
    }

    @Test
    void onAFlatPlanePrefersTheRichSideOverAPoorWayAheadAndNotTheMinedArea() {
        // Open flat plane. Straight on (east): a few ores, none in the next 5 blocks. North-east: a rich patch.
        // Behind (west): mined out and walked.
        TestMine mine = new TestMine(-40, -3, -40, 40, 6, 40).carve(-36, 1, -36, 36, 3, 36);
        for (int x = 8; x <= 30; x += 6) {
            mine.ore(x, 0, 0, TestMine.REDSTONE_ORE);
        }
        oreFloor(mine, 4, -14, 12, -6);
        LongOpenHashSet walked = new LongOpenHashSet();
        for (int x = -30; x <= 0; x++) {
            for (int z = -1; z <= 1; z++) {
                walked.add(Pos.pack(x, 1, z));
            }
        }
        TunnelSteer.Decision d = decide(new TunnelSteer(), mine, 0.5D, 0.5D, EAST, walked);
        float toPatch = RotationMath.yawOf(8.0D, -10.0D);
        assertTrue(Math.abs(RotationMath.wrap(d.heading() - toPatch)) < 30.0F,
                "towards the rich patch (" + toPatch + "), not the poor way ahead / the mined area: " + d.heading());
    }

    private static final IntPredicate LAPIS = key -> key == BlockKeys.key("minecraft:lapis_ore");

    private static TunnelSteer.Decision decide(TunnelSteer steer, TestMine mine, double x, double z, float yaw, IntPredicate foreign,
                                               double[][] wardens) {
        return steer.decide(mine, REDSTONE, foreign, x, 1.0D, z, yaw, true, LongSet.of(), zone -> 1.0D, wardens, 0L);
    }

    @Test
    void turnsBackWhereAnotherOreStarts() {
        // Redstone mine to the west, a lapis mine begins 10 blocks east: do not walk into it.
        TestMine mine = new TestMine(-40, -3, -4, 40, 6, 4).carve(-36, 1, -1, 36, 3, 1);
        oreFloor(mine, -36, -1, 9, 1);
        for (int x = 10; x <= 36; x++) {
            for (int z = -1; z <= 1; z++) {
                mine.ore(x, 0, z, "minecraft:lapis_ore");
            }
        }
        TunnelSteer.Decision d = decide(new TunnelSteer(), mine, 0.5D, 0.5D, EAST, LAPIS, new double[0][]);
        if (d.heading() == EAST) {
            assertTrue(d.free() <= 8.0D, "ends before the lapis: free " + d.free());
        }
        TunnelSteer.Decision atBorder = decide(new TunnelSteer(), mine, 7.5D, 0.5D, EAST, LAPIS, new double[0][]);
        assertEquals(90.0F, atBorder.heading(), 1.0F, "at the border it turns back into its own mine");
    }

    @Test
    void doesNotSwingBetweenTwoEqualBranches() {
        // A fork: two identical tunnels at ±45° from the way it came. Decide 40 ticks in a row.
        TestMine mine = new TestMine(-40, -3, -40, 40, 6, 40).carve(-1, 1, -30, 1, 3, 1);
        for (int k = 0; k <= 25; k++) {
            mine.carve(k - 1, 1, -k - 1, k + 1, 3, -k + 1);
            mine.carve(-k - 1, 1, -k - 1, -k + 1, 3, -k + 1);
            mine.ore(k, 0, -k, TestMine.REDSTONE_ORE);
            mine.ore(-k, 0, -k, TestMine.REDSTONE_ORE);
        }
        TunnelSteer steer = new TunnelSteer();
        float first = decide(steer, mine, 0.5D, 0.5D, NORTH, LongSet.of()).heading();
        for (int i = 0; i < 40; i++) {
            float heading = decide(steer, mine, 0.5D, 0.5D, first, LongSet.of()).heading();
            assertEquals(first, heading, 1.0F, "keeps the branch it chose");
        }
    }

    @Test
    void routeGuideKeepsStraightOnTheLineAndOnlyGoesAsideForOreWithinFiveBlocks() {
        // Open flat plane, route line along +x (z = 0) from (0,0) to (40,0). Ore everywhere on the line.
        TestMine mine = new TestMine(-40, -3, -40, 60, 6, 40).carve(-36, 1, -36, 56, 3, 36);
        oreFloor(mine, 0, -1, 40, 1);
        TunnelSteer guided = new TunnelSteer();
        guided.guide(new int[]{0, 1, 0}, new int[]{40, 1, 0});
        TunnelSteer.Decision d = decide(guided, mine, 2.5D, 0.5D, EAST, LongSet.of());
        assertEquals(EAST, d.heading(), 1.0F, "straight along the route line");
        assertTrue(d.free() > 20.0D, "a long straight way: " + d.free());

        // A rich patch 15+ blocks aside (outside the corridor) must not pull it away ...
        TestMine far = new TestMine(-40, -3, -40, 60, 6, 40).carve(-36, 1, -36, 56, 3, 36);
        oreFloor(far, 4, -22, 14, -15);
        TunnelSteer.Decision stays = decide(guided, far, 2.5D, 0.5D, EAST, LongSet.of());
        assertEquals(EAST, stays.heading(), 20.0F, "stays on the route: " + stays.heading());

        // ... but a patch 3-4 blocks aside with nothing on the line is fetched (freedom within the corridor).
        TestMine near = new TestMine(-40, -3, -40, 60, 6, 40).carve(-36, 1, -36, 56, 3, 36);
        oreFloor(near, 5, -4, 20, -3);
        TunnelSteer nearSteer = new TunnelSteer();
        nearSteer.guide(new int[]{0, 1, 0}, new int[]{40, 1, 0});
        TunnelSteer.Decision aside = decide(nearSteer, near, 2.5D, 0.5D, EAST, LongSet.of());
        assertTrue(RotationMath.wrap(aside.heading() - EAST) < -5.0F, "slants north towards the ore beside the line: " + aside.heading());
        assertTrue(Math.abs(RotationMath.wrap(aside.heading() - EAST)) < 60.0F, "but keeps going forward: " + aside.heading());
    }

    @Test
    void routeGoesUpToTenBlocksAsideOnlyWhenTheWayHasLittleOre() {
        // Route line along +x (z = 0); a rich patch 8-9 blocks north (z = -9..-7).
        TestMine empty = new TestMine(-40, -3, -40, 60, 6, 40).carve(-36, 1, -36, 56, 3, 36);
        oreFloor(empty, 3, -9, 20, -7);
        TunnelSteer wide = new TunnelSteer();
        wide.guide(new int[]{0, 1, 0}, new int[]{40, 1, 0});
        TunnelSteer.Decision aside = decide(wide, empty, 2.5D, 0.5D, EAST, LongSet.of());
        assertTrue(RotationMath.wrap(aside.heading() - EAST) < -5.0F, "no ore on the line: over to the patch: " + aside.heading());

        // The same patch, but ore on the line: it stays (only 5 blocks aside count then).
        TestMine rich = new TestMine(-40, -3, -40, 60, 6, 40).carve(-36, 1, -36, 56, 3, 36);
        oreFloor(rich, 3, -9, 20, -7);
        oreFloor(rich, 0, -1, 40, 1);
        TunnelSteer keep = new TunnelSteer();
        keep.guide(new int[]{0, 1, 0}, new int[]{40, 1, 0});
        TunnelSteer.Decision stays = decide(keep, rich, 2.5D, 0.5D, EAST, LongSet.of());
        assertEquals(EAST, stays.heading(), 10.0F, "ore on the line: straight on: " + stays.heading());
    }

    @Test
    void routeLaneIsKeptWhenAnotherLaneIsNotClearlyRicher() {
        // Route line along +x (z = 0): the 5 wide strip ahead is ore except 4 stone blocks; 3-5 blocks north lies a
        // strip full of ore. After the way over it gives hardly more ore per second: no lane change, straight on.
        TestMine mine = new TestMine(-40, -3, -40, 60, 6, 40).carve(-36, 1, -36, 56, 3, 36);
        oreFloor(mine, 1, -2, 45, 2);
        oreFloor(mine, 1, -7, 45, -3);
        for (int x = 3; x <= 6; x++) {
            mine.set(x, 0, 0, io.theprisons.core.nav.Cell.SOLID);
        }
        TunnelSteer steer = new TunnelSteer();
        steer.guide(new int[]{0, 1, 0}, new int[]{50, 1, 0});
        TunnelSteer.Decision d = decide(steer, mine, 0.5D, 0.5D, EAST, LongSet.of());
        assertEquals(EAST, d.heading(), 8.0F, "keeps its lane: " + d.heading());
    }

    @Test
    void routeLaneChangesAtOnceToTheStripWithMoreOrePerSecondAndStaysThere() {
        // Route line along +x: the middle is mined out (stone); 2-6 blocks north (z = -6..-2) runs an ore strip.
        TestMine mine = new TestMine(-40, -3, -40, 60, 6, 40).carve(-36, 1, -36, 56, 3, 36);
        oreFloor(mine, 1, -6, 45, -2);
        TunnelSteer steer = new TunnelSteer();
        steer.guide(new int[]{0, 1, 0}, new int[]{50, 1, 0});
        TunnelSteer.Decision start = decide(steer, mine, 0.5D, 0.5D, EAST, LongSet.of());
        assertTrue(RotationMath.wrap(start.heading() - EAST) < -5.0F, "more ore per second over there: moves over: " + start.heading());
        assertTrue(Math.abs(RotationMath.wrap(start.heading() - EAST)) < 60.0F, "but keeps going forward: " + start.heading());

        // Over there (4 blocks north): no stone ahead, it walks straight on and does not weave back.
        TunnelSteer.Decision onLane = decide(steer, mine, 6.5D, -3.5D, EAST, LongSet.of());
        assertEquals(EAST, onLane.heading(), 8.0F, "stays on its lane: " + onLane.heading());
        TunnelSteer.Decision later = decide(steer, mine, 12.5D, -3.5D, EAST, LongSet.of());
        assertEquals(EAST, later.heading(), 8.0F, "still on its lane: " + later.heading());
    }

    @Test
    void routeLaneKeepsTwoBlocksOfFloorToTheWall() {
        // Tunnel z = -5..3 along +x, the ore lies at the north wall (z = -5..-4). A lane whose 5 wide strip would
        // reach into the wall is not taken; the one 2 blocks of floor away from the wall is.
        TestMine mine = new TestMine(-40, -3, -40, 60, 6, 40).carve(-36, 1, -5, 56, 3, 3);
        oreFloor(mine, 1, -5, 45, -4);
        int[] from = {0, 1, 0};
        int[] to = {50, 1, 0};
        io.theprisons.core.nav.Walkability walk = new io.theprisons.core.nav.Walkability(mine, 3);
        assertTrue(!TunnelSteer.strip(mine, walk, REDSTONE, from, to, 0.0D, 4, 1.0D).walkable(), "lane 4 touches the wall");
        TunnelSteer.Strip three = TunnelSteer.strip(mine, walk, REDSTONE, from, to, 0.0D, 3, 1.0D);
        assertTrue(three.walkable(), "lane 3 has 2 blocks of floor to the wall");
        assertEquals(20, three.ore(), "5 wide x 10 ahead, 2 ore rows");
        assertEquals(30, three.stone());
    }

    @Test
    void routeGuideLeavesTheLineSlightlyForAClearlyRicherWay() {
        // Route line along +x with one row of ore on it; 3-5 blocks north lies a 3 blocks wide ore patch.
        TestMine mine = new TestMine(-40, -3, -40, 60, 6, 40).carve(-36, 1, -36, 56, 3, 36);
        oreFloor(mine, 1, 0, 40, 0);
        oreFloor(mine, 3, -5, 40, -3);
        TunnelSteer steer = new TunnelSteer();
        steer.guide(new int[]{0, 1, 0}, new int[]{40, 1, 0});
        TunnelSteer.Decision d = decide(steer, mine, 0.5D, 0.5D, EAST, LongSet.of());
        assertTrue(RotationMath.wrap(d.heading() - EAST) < -5.0F, "slants over to the richer way: " + d.heading());
        assertTrue(Math.abs(RotationMath.wrap(d.heading() - EAST)) < 60.0F, "but keeps going forward: " + d.heading());
    }

    @Test
    void routeGuideNeverStepsBackEvenWhenAllOreIsBehind() {
        // Route line along +x. The line ahead is mined out (no ore); all ore lies behind the player (west).
        TestMine mine = new TestMine(-40, -3, -40, 60, 6, 40).carve(-36, 1, -36, 56, 3, 36);
        oreFloor(mine, -30, -3, -2, 3);
        TunnelSteer steer = new TunnelSteer();
        steer.guide(new int[]{-30, 1, 0}, new int[]{40, 1, 0});
        TunnelSteer.Decision d = decide(steer, mine, 0.5D, 0.5D, EAST, LongSet.of());
        assertTrue(d.forward(), "keeps walking");
        assertEquals(EAST, d.heading(), 10.0F, "straight on to the next waypoint, not back to the ore: " + d.heading());
        // Free tunnel mode does not turn round either while a way aside is open (it takes the way aside that reaches
        // the ore, never the one back).
        TunnelSteer.Decision free = decide(new TunnelSteer(), mine, 0.5D, 0.5D, EAST, LongSet.of());
        assertTrue(Math.abs(RotationMath.wrap(free.heading() - EAST)) <= 90.0F, "no turning round: " + free.heading());
    }

    @Test
    void routeGuideWalksTheLineStraightWhenAllOreIsStillThere() {
        // Everything still full of ore: it walks the line between the waypoints straight, not towards richer corners.
        TestMine mine = new TestMine(-40, -3, -40, 60, 6, 40).carve(-36, 1, -36, 56, 3, 36);
        oreFloor(mine, -36, -36, 56, 36);
        TunnelSteer steer = new TunnelSteer();
        steer.guide(new int[]{0, 1, 0}, new int[]{40, 1, 0});
        for (double z : new double[]{0.5D, 1.5D, -0.5D}) {
            TunnelSteer.Decision d = decide(steer, mine, 5.5D, z, EAST, LongSet.of());
            assertEquals(EAST, d.heading(), 1.0F, "straight along the line (z " + z + "): " + d.heading());
        }
    }

    @Test
    void routeGuideWalksStraightUpAStairWithoutAFlatTop() {
        // A 3-wide stair going up one block per block for 10 steps (like a spiral stair, never flat on top).
        TestMine mine = new TestMine(-4, -3, -4, 40, 20, 4).carve(0, 1, -1, 36, 16, 1);
        for (int step = 1; step <= 10; step++) {
            mine.fill(2 + step, 1, -1, 36, step, 1);
        }
        TunnelSteer steer = new TunnelSteer();
        steer.guide(new int[]{0, 1, 0}, new int[]{14, 11, 0});
        TunnelSteer.Decision d = decide(steer, mine, 0.5D, 0.5D, EAST, LongSet.of());
        assertTrue(d.forward(), "walks on");
        assertEquals(EAST, d.heading(), 1.0F, "straight up the stair: " + d.heading());
        assertTrue(d.free() > 5.0D, "up the steps, not stopping at the first one: " + d.free());
        // Standing right in front of the first step: jump.
        TunnelSteer.Decision atStep = decide(steer, mine, 2.3D, 0.5D, EAST, LongSet.of());
        assertTrue(atStep.jump(), "jumps onto the step");
    }

    @Test
    void walksTheTunnelToItsEndWhileThereIsOreAhead() {
        // Tunnel east (z -1..1) with every 2nd floor block ore; a branch north right here is full of ore.
        TestMine mine = new TestMine(-4, -3, -30, 40, 6, 4).carve(-2, 1, -1, 36, 3, 1).carve(-1, 1, -25, 1, 3, -1);
        for (int x = -2; x <= 36; x += 2) {
            oreFloor(mine, x, -1, x, 1);
        }
        oreFloor(mine, -1, -25, 1, -2);
        TunnelSteer steer = new TunnelSteer();
        decide(steer, mine, 0.5D, 0.5D, EAST, LongSet.of());
        TunnelSteer.Decision d = decide(steer, mine, 0.5D, 0.5D, EAST, LongSet.of());
        assertEquals(EAST, d.heading(), 1.0F, "straight on to the end of the tunnel, not into the rich branch");
    }

    @Test
    void leavesTheTunnelWhenAheadIsEightyPercentMoreStone() {
        // Same, but only every 4th floor block ahead is ore (3 stone per ore) and none in the next 5 blocks: the rich
        // branch north is taken.
        TestMine mine = new TestMine(-4, -3, -30, 40, 6, 4).carve(-2, 1, -1, 36, 3, 1).carve(-1, 1, -25, 1, 3, -1);
        for (int x = 8; x <= 36; x += 4) {
            oreFloor(mine, x, -1, x, 1);
        }
        oreFloor(mine, -1, -25, 1, -2);
        TunnelSteer steer = new TunnelSteer();
        decide(steer, mine, 0.5D, 0.5D, EAST, LongSet.of());
        TunnelSteer.Decision d = decide(steer, mine, 0.5D, 0.5D, EAST, LongSet.of());
        assertEquals(NORTH, Math.abs(RotationMath.wrap(d.heading())), 20.0F, "into the branch: " + d.heading());
    }

    @Test
    void oreWithinFiveBlocksAheadIsMinedFirstWhateverIsAside() {
        // The tunnel east has ore 3 blocks ahead (then little); the branch north is full of ore: it walks on east.
        TestMine mine = new TestMine(-4, -3, -30, 40, 6, 4).carve(-2, 1, -1, 36, 3, 1).carve(-1, 1, -25, 1, 3, -1);
        oreFloor(mine, 3, -1, 3, 1);
        for (int x = 12; x <= 36; x += 6) {
            oreFloor(mine, x, 0, x, 0);
        }
        oreFloor(mine, -1, -25, 1, -2);
        TunnelSteer steer = new TunnelSteer();
        decide(steer, mine, 0.5D, 0.5D, EAST, LongSet.of());
        TunnelSteer.Decision d = decide(steer, mine, 0.5D, 0.5D, EAST, LongSet.of());
        assertEquals(EAST, d.heading(), 10.0F, "ore ahead: straight on, no turning: " + d.heading());
        assertTrue(d.oreAhead());
    }

    // ── Free tunnel mode: way + lane, no back and forth ──────────────────────

    /** Walks the decisions in a flat mine (feet y 1): 0.22 blocks a tick, floor ore within 2 blocks ahead is mined. */
    private static final class Walk {
        final TestMine mine;
        final TunnelSteer steer = new TunnelSteer();
        double x;
        double z;
        float yaw;
        double backwards;
        int reversals;
        int steerFlips;
        /** Ticks the player walked into a wall (the step was not taken). */
        int bumps;
        TunnelSteer.Decision last;

        Walk(TestMine mine, double x, double z, float yaw) {
            this.mine = mine;
            this.x = x;
            this.z = z;
            this.yaw = yaw;
        }

        void ticks(int n, float mainYaw) {
            float lastHeading = Float.NaN;
            float lastSide = 0.0F;
            for (int i = 0; i < n; i++) {
                TunnelSteer.Decision d = decide(steer, mine, x, z, yaw, LongSet.of());
                last = d;
                if (!d.forward()) {
                    return;
                }
                if (!Float.isNaN(lastHeading) && Math.abs(RotationMath.wrap(d.heading() - lastHeading)) > 90.0F) {
                    reversals++;
                }
                lastHeading = d.heading();
                float side = RotationMath.wrap(d.yaw() - d.heading());
                if (Math.abs(side) > 5.0F && Math.signum(side) != Math.signum(lastSide) && Math.abs(lastSide) > 5.0F) {
                    steerFlips++;
                }
                if (Math.abs(side) > 5.0F) {
                    lastSide = side;
                }
                yaw = d.yaw();
                double rad = Math.toRadians(yaw);
                double nx = x - Math.sin(rad) * 0.22D;
                double nz = z + Math.cos(rad) * 0.22D;
                int bx = (int) Math.floor(nx);
                int bz = (int) Math.floor(nz);
                if (mine.cell(bx, 1, bz) != io.theprisons.core.nav.Cell.AIR) {
                    bumps++;
                    continue;
                }
                double main = Math.toRadians(mainYaw);
                backwards += Math.max(0.0D, -((nx - x) * -Math.sin(main) + (nz - z) * Math.cos(main)));
                x = nx;
                z = nz;
                // The pickaxe: the floor under the crosshair (1-2 blocks ahead) and the block left and right of it.
                for (double ahead = 1.0D; ahead <= 2.0D; ahead += 0.5D) {
                    for (int w = -1; w <= 1; w++) {
                        int ox = (int) Math.floor(x - Math.sin(rad) * ahead + Math.cos(rad) * w);
                        int oz = (int) Math.floor(z + Math.cos(rad) * ahead + Math.sin(rad) * w);
                        if (mine.ores().containsKey(Pos.pack(ox, 0, oz))) {
                            mine.set(ox, 0, oz, io.theprisons.core.nav.Cell.SOLID);
                        }
                    }
                }
            }
        }
    }

    @Test
    void walksAWideTunnelWithScatteredOreWithoutBackAndForth() {
        // 9 wide tunnel east (z -4..4), 140 long, a third of the floor ore (fixed seed).
        TestMine mine = new TestMine(-4, -3, -8, 150, 6, 8).carve(-2, 1, -4, 145, 3, 4);
        java.util.Random random = new java.util.Random(7L);
        for (int x = -2; x <= 145; x++) {
            for (int z = -4; z <= 4; z++) {
                if (random.nextInt(3) == 0) {
                    mine.ore(x, 0, z, TestMine.REDSTONE_ORE);
                }
            }
        }
        Walk walk = new Walk(mine, 0.5D, 0.5D, EAST);
        walk.ticks(500, EAST);
        assertEquals(0, walk.reversals, "never turns round");
        assertTrue(walk.backwards < 0.5D, "never walks back: " + walk.backwards);
        assertTrue(walk.x > 90.0D, "walks on: " + walk.x);
        assertTrue(walk.steerFlips <= 16, "no left-right weaving: " + walk.steerFlips + " flips over " + Math.round(walk.x) + " blocks");
    }

    @Test
    void keepsItsLaneWhileThereIsOreWithinThreeBlocks() {
        // 9 wide tunnel: the middle lane has ore 2 blocks ahead, then nothing; 3 blocks north lies a full ore strip.
        TestMine mine = new TestMine(-4, -3, -8, 60, 6, 8).carve(-2, 1, -4, 55, 3, 4);
        oreFloor(mine, 2, -1, 2, 1);
        oreFloor(mine, 1, -4, 50, -3);
        TunnelSteer steer = new TunnelSteer();
        TunnelSteer.Decision d = decide(steer, mine, 0.5D, 0.5D, EAST, LongSet.of());
        assertEquals(EAST, d.yaw(), 3.0F, "ore on its lane right ahead: straight on: " + d.yaw());
    }

    @Test
    void movesOverToALaneOnlyForAtLeastThreeMoreOres() {
        // The way east has no ore on its lane. 3 blocks north: only 2 ores - not worth it.
        TestMine poor = new TestMine(-4, -3, -8, 60, 6, 8).carve(-2, 1, -4, 55, 3, 4);
        poor.ore(3, 0, -3, TestMine.REDSTONE_ORE);
        poor.ore(4, 0, -3, TestMine.REDSTONE_ORE);
        oreFloor(poor, 20, -1, 40, 1);
        TunnelSteer stays = new TunnelSteer();
        decide(stays, poor, 0.5D, 0.5D, EAST, LongSet.of());
        TunnelSteer.Decision d = decide(stays, poor, 0.6D, 0.5D, EAST, LongSet.of());
        assertEquals(EAST, d.yaw(), 3.0F, "2 ores more is not worth a lane change: " + d.yaw());

        // ... 3 blocks north a full strip: it moves over towards it (slanting forward) and then walks straight on.
        TestMine rich = new TestMine(-4, -3, -8, 60, 6, 8).carve(-2, 1, -4, 55, 3, 4);
        oreFloor(rich, 3, -4, 40, -2);
        oreFloor(rich, 20, -1, 40, 1);
        Walk walk = new Walk(rich, 0.5D, 0.5D, EAST);
        walk.ticks(1, EAST);
        assertTrue(RotationMath.wrap(walk.last.yaw() - EAST) < -5.0F, "moves over to the north lane: " + walk.last.yaw());
        assertTrue(Math.abs(RotationMath.wrap(walk.last.yaw() - EAST)) <= 45.0F, "but forward: " + walk.last.yaw());
        walk.ticks(80, EAST);
        // Only slightly aside (at most LANE_ASIDE 1 block from the middle z 0.5).
        assertTrue(walk.z < 0.0D && walk.z > -1.0D, "on a lane towards the north strip: z " + walk.z);
        assertEquals(0, walk.reversals);
        assertTrue(walk.backwards < 0.2D, "never back: " + walk.backwards);
    }

    @Test
    void doesNotFlipBetweenTwoEqualWaysWhenNothingIsRightAhead() {
        // Open plane: two equal ore patches at ±40°, both beyond 5 blocks. Whichever it takes, it stays on it.
        TestMine mine = new TestMine(-40, -3, -40, 60, 6, 40).carve(-36, 1, -36, 56, 3, 36);
        for (float off : new float[]{-40.0F, 40.0F}) {
            double rad = Math.toRadians(EAST + off);
            for (int d = 7; d <= 30; d++) {
                for (int w = -2; w <= 2; w++) {
                    mine.ore((int) Math.floor(0.5D - Math.sin(rad) * d + Math.cos(rad) * w), 0,
                            (int) Math.floor(0.5D + Math.cos(rad) * d + Math.sin(rad) * w), TestMine.REDSTONE_ORE);
                }
            }
        }
        Walk walk = new Walk(mine, 0.5D, 0.5D, EAST);
        walk.ticks(1, EAST);
        float first = walk.last.heading();
        assertTrue(Math.abs(Math.abs(RotationMath.wrap(first - EAST)) - 40.0F) <= 16.0F, "to one of the patches: " + first);
        walk.ticks(40, first);
        assertEquals(first, walk.last.heading(), 16.0F, "keeps that way: " + walk.last.heading());
        assertEquals(0, walk.reversals);
    }

    @Test
    void followsABendWithoutZigZag() {
        // 5 wide tunnel: 40 blocks east, then 40 blocks north; a third of the floor ore (fixed seed).
        TestMine mine = new TestMine(-4, -3, -50, 50, 6, 8).carve(-2, 1, -2, 40, 3, 2).carve(36, 1, -45, 40, 3, 2);
        java.util.Random random = new java.util.Random(11L);
        for (int x = -2; x <= 40; x++) {
            for (int z = -45; z <= 2; z++) {
                if (mine.cell(x, 1, z) == io.theprisons.core.nav.Cell.AIR && random.nextInt(3) == 0) {
                    mine.ore(x, 0, z, TestMine.REDSTONE_ORE);
                }
            }
        }
        Walk walk = new Walk(mine, 0.5D, 0.5D, EAST);
        walk.ticks(340, EAST);
        assertEquals(0, walk.reversals, "never turns round");
        assertTrue(walk.z < -25.0D, "round the bend and on north: x " + walk.x + " z " + walk.z);
        assertTrue(walk.steerFlips <= 8, "no zig-zag: " + walk.steerFlips);
    }

    @Test
    void turnsRoundOnceAtADeadEnd() {
        // 5 wide tunnel east ending in a wall at x 20, ore behind: at the end it turns round once, cleanly.
        TestMine mine = new TestMine(-40, -3, -8, 30, 6, 8).carve(-36, 1, -2, 20, 3, 2);
        oreFloor(mine, -30, -2, -5, 2);
        oreFloor(mine, 10, -2, 20, 2);
        Walk walk = new Walk(mine, 12.5D, 0.5D, EAST);
        walk.ticks(80, EAST);
        assertEquals(1, walk.reversals, "one turn round at the end");
        assertTrue(walk.x < 10.0D, "and back along the tunnel: " + walk.x);
    }

    @Test
    void goesRoundAPillarInsteadOfIntoIt() {
        // 7 wide tunnel east, a 1x1 pillar 3 blocks ahead in the middle, ore on the floor behind it.
        TestMine mine = new TestMine(-4, -3, -8, 60, 6, 8).carve(-2, 1, -3, 55, 3, 3);
        mine.fill(3, 1, 0, 3, 3, 0);
        oreFloor(mine, 6, -3, 50, 3);
        Walk walk = new Walk(mine, 0.5D, 0.5D, EAST);
        walk.ticks(120, EAST);
        assertTrue(walk.x > 20.0D, "past the pillar: x " + walk.x);
        assertEquals(0, walk.reversals);
        assertTrue(walk.backwards < 0.2D, "never back: " + walk.backwards);
    }

    @Test
    void staysInTheMiddleWhenTheTunnelShiftsSideways() {
        // 5 wide tunnel east (z -2..2, middle 0.5), from x 20 on shifted 2 blocks south (z 0..4, middle 2.5); ore
        // everywhere. Before the change the lane stayed at z 0.5 - right at the new north wall.
        TestMine mine = new TestMine(-4, -3, -8, 90, 6, 8).carve(-2, 1, -2, 22, 3, 2).carve(18, 1, 0, 85, 3, 4);
        oreFloor(mine, -2, -2, 22, 2);
        oreFloor(mine, 18, 0, 85, 4);
        Walk walk = new Walk(mine, 0.5D, 0.5D, EAST);
        walk.ticks(160, EAST);
        assertTrue(walk.x > 30.0D, "walks on: " + walk.x);
        assertEquals(2.5D, walk.z, 0.8D, "in the middle of the shifted tunnel: z " + walk.z);
        walk.ticks(100, EAST);
        assertEquals(2.5D, walk.z, 0.8D, "and stays there: z " + walk.z);
        assertEquals(0, walk.reversals);
    }

    @Test
    void walksAPlannedDirectionInTheMiddleOfTheTunnel() {
        // 5 wide tunnel east with ore all along; a 5 wide branch north at x 18..22 (middle 20.5) without ore. The
        // planned route says north: it turns into the branch and walks its middle (not along the ore east).
        TestMine mine = new TestMine(-4, -3, -70, 50, 6, 8).carve(-2, 1, -2, 45, 3, 2).carve(18, 1, -65, 22, 3, 2);
        oreFloor(mine, -2, -2, 45, 2);
        Walk walk = new Walk(mine, 18.6D, 0.5D, EAST);
        walk.steer.direct(NORTH);
        walk.ticks(200, NORTH);
        assertTrue(walk.z < -25.0D, "walks north: z " + walk.z + " x " + walk.x);
        assertEquals(20.5D, walk.x, 0.8D, "in the middle of the branch: x " + walk.x);
        assertEquals(0, walk.reversals);
    }

    @Test
    void withoutAPlanTheOreEastIsWalkedOn() {
        TestMine mine = new TestMine(-4, -3, -70, 50, 6, 8).carve(-2, 1, -2, 45, 3, 2).carve(18, 1, -65, 22, 3, 2);
        oreFloor(mine, -2, -2, 45, 2);
        Walk walk = new Walk(mine, 18.6D, 0.5D, EAST);
        walk.ticks(80, EAST);
        assertTrue(walk.x > 30.0D, "straight on along the ore: x " + walk.x + " z " + walk.z);
    }

    @Test
    void neverWalksOverTheTaxZoneEdgeKnownFromAnEarlierRun() {
        // 5 wide tunnel east, ore all along; an earlier run found the guard XP tax gone at x 30.
        TestMine mine = new TestMine(-4, -3, -8, 70, 6, 8).carve(-2, 1, -2, 65, 3, 2);
        oreFloor(mine, -2, -2, 65, 2);
        it.unimi.dsi.fastutil.longs.LongArrayList inside = new it.unimi.dsi.fastutil.longs.LongArrayList();
        it.unimi.dsi.fastutil.longs.LongArrayList outside = new it.unimi.dsi.fastutil.longs.LongArrayList();
        for (int z = -2; z <= 2; z++) {
            for (int x = -2; x < 30; x++) {
                inside.add(Pos.pack(x, 1, z));
            }
            outside.add(Pos.pack(30, 1, z));
        }
        GuardArea area = new GuardArea();
        area.restore(inside.toLongArray(), outside.toLongArray());
        area.tax(Boolean.TRUE, 0, 1, 0);
        Walk walk = new Walk(mine, 0.5D, 0.5D, EAST);
        walk.steer.guards(area);
        double maxX = walk.x;
        for (int i = 0; i < 40; i++) {
            walk.ticks(10, EAST);
            maxX = Math.max(maxX, walk.x);
        }
        assertTrue(maxX > 20.0D, "walks east while inside: " + maxX);
        assertTrue(maxX < 29.0D, "never onto the learned edge: " + maxX);
    }

    @Test
    void keepsToTheMiddleOfAWindingTunnel() {
        // 5 wide tunnel east whose middle winds 3 blocks left and right; a third of the floor ore (fixed seed).
        TestMine mine = new TestMine(-4, -3, -12, 160, 6, 12);
        java.util.Random random = new java.util.Random(5L);
        for (int x = -2; x <= 150; x++) {
            int c = middleOf(x);
            mine.carve(x, 1, c - 2, x, 3, c + 2);
            for (int z = c - 2; z <= c + 2; z++) {
                if (random.nextInt(3) == 0) {
                    mine.ore(x, 0, z, TestMine.REDSTONE_ORE);
                }
            }
        }
        Walk walk = new Walk(mine, 0.5D, middleOf(0) + 0.5D, EAST);
        double off = 0.0D;
        int samples = 0;
        for (int i = 0; i < 60; i++) {
            walk.ticks(10, EAST);
            off += Math.abs(walk.z - (middleOf((int) Math.floor(walk.x)) + 0.5D));
            samples++;
        }
        assertTrue(walk.x > 90.0D, "walks on: " + walk.x);
        assertEquals(0, walk.reversals, "never turns round");
        assertTrue(off / samples < 1.0D, "close to the middle on average: " + off / samples);
        assertTrue(walk.steerFlips <= 20, "no weaving: " + walk.steerFlips);
    }

    @Test
    void followsASlantingTunnelInItsMiddleInsteadOfStraightIntoTheWall() {
        // 5 wide tunnel slanting 30° off east (middle z = 0.58 x); a third of the floor ore. The player starts looking
        // straight east: the way must bend onto the tunnel, not run east into its wall.
        TestMine mine = new TestMine(-4, -3, -8, 110, 6, 70);
        java.util.Random random = new java.util.Random(3L);
        for (int x = -2; x <= 100; x++) {
            int c = slantOf(x);
            mine.carve(x, 1, c - 2, x, 3, c + 2);
            for (int z = c - 2; z <= c + 2; z++) {
                if (random.nextInt(3) == 0) {
                    mine.ore(x, 0, z, TestMine.REDSTONE_ORE);
                }
            }
        }
        Walk walk = new Walk(mine, 0.5D, 0.5D, EAST);
        double off = 0.0D;
        int samples = 0;
        for (int i = 0; i < 50; i++) {
            walk.ticks(10, -60.0F);
            off += Math.abs(walk.z - (slantOf((int) Math.floor(walk.x)) + 0.5D));
            samples++;
        }
        assertTrue(walk.x > 60.0D, "walks on along the tunnel: x " + walk.x + " z " + walk.z);
        assertEquals(0, walk.reversals, "never turns round");
        assertTrue(off / samples < 1.0D, "close to the middle on average: " + off / samples);
        assertTrue(walk.bumps <= 5, "does not walk into the wall: " + walk.bumps);
    }

    private static int slantOf(int x) {
        return (int) Math.round(x * Math.tan(Math.toRadians(30.0D)));
    }

    @Test
    void followsTheMiddleOfAWideCurvingTunnel() {
        // 15 wide tunnel (walls 7.5 blocks from the middle) curving 60° from east to south-east over 80 blocks; ore
        // scattered. The middle is followed round the curve instead of walking straight on to the outer wall.
        TestMine mine = new TestMine(-10, -3, -12, 120, 6, 110);
        java.util.Random random = new java.util.Random(9L);
        for (int x = -2; x <= 110; x++) {
            int c = curveOf(x);
            mine.carve(x, 1, c - 7, x, 3, c + 7);
            for (int z = c - 7; z <= c + 7; z++) {
                if (random.nextInt(4) == 0) {
                    mine.ore(x, 0, z, TestMine.REDSTONE_ORE);
                }
            }
        }
        Walk walk = new Walk(mine, 0.5D, 0.5D, EAST);
        double worst = 0.0D;
        for (int i = 0; i < 60; i++) {
            walk.ticks(10, -70.0F);
            worst = Math.max(worst, Math.abs(walk.z - (curveOf((int) Math.floor(walk.x)) + 0.5D)));
        }
        assertTrue(walk.x > 50.0D, "walks on round the curve: x " + walk.x + " z " + walk.z);
        assertEquals(0, walk.reversals, "never turns round");
        assertTrue(walk.bumps <= 5, "does not walk into the wall: " + walk.bumps);
        // Lanes beside the middle for ore are allowed (up to 5 blocks), the wall (7.5 blocks away) is never reached.
        assertTrue(worst < 6.5D, "stays well inside the tunnel: " + worst);
    }

    @Test
    void goesToTheMiddleOfAWideTunnelFromBesideIt() {
        // 15 wide tunnel east (z -7..7, middle 0.5) without ore; the player starts 5 blocks beside the middle. Before,
        // the room was only measured up to 6 blocks, so it stopped about 2 blocks beside the middle.
        TestMine mine = new TestMine(-4, -3, -10, 90, 6, 10).carve(-2, 1, -7, 85, 3, 7);
        Walk walk = new Walk(mine, 0.5D, 5.5D, EAST);
        walk.ticks(200, EAST);
        assertTrue(walk.x > 25.0D, "walks on: " + walk.x);
        assertEquals(0.5D, walk.z, 1.0D, "in the middle: z " + walk.z);
        assertEquals(0, walk.bumps);
    }

    /** Middle of the curving tunnel: east first, the slope growing to tan 60° at x 80. */
    private static int curveOf(int x) {
        double t = Math.min(x, 80) / 80.0D;
        double z = 80.0D * Math.tan(Math.toRadians(60.0D)) * t * t / 2.0D;
        if (x > 80) {
            z += (x - 80) * Math.tan(Math.toRadians(60.0D));
        }
        return (int) Math.round(z);
    }

    @Test
    void walksAPlannedRouteThroughACurvingTunnelInItsMiddle() {
        // 5 wide tunnel curving from east to south-east (as above), no ore on the way, ore at its far end. The planned
        // route's legs are straight lines between corners; the macro walks them as the tunnel's middle - round the
        // curve, never straight on into the outer wall.
        TestMine mine = new TestMine(-10, -3, -12, 120, 6, 110);
        for (int x = -2; x <= 110; x++) {
            int c = curveOf(x);
            mine.carve(x, 1, c - 2, x, 3, c + 2);
            if (x >= 95) {
                oreFloor(mine, x, c - 2, x, c + 2);
            }
        }
        OrePlanner.Plan plan = OrePlanner.plan(mine, REDSTONE, new io.theprisons.core.nav.Walkability(mine, 3),
                Pos.pack(0, 1, 0), EAST, null, null, zone -> 1.0D, OrePlanner.Params.DEFAULT, () -> false);
        assertTrue(plan.found(), plan.reason());
        int[][] wp = plan.waypoints();
        Walk walk = new Walk(mine, 0.5D, 0.5D, EAST);
        int index = 0;
        int directed = -1;
        double off = 0.0D;
        int samples = 0;
        for (int t = 0; t < 900 && index < wp.length - 1; t++) {
            while (index < wp.length - 1 && Math.hypot(walk.x - wp[index + 1][0] - 0.5D, walk.z - wp[index + 1][2] - 0.5D) <= 2.5D) {
                index++;
            }
            if (index >= wp.length - 1) {
                break;
            }
            if (index != directed) {
                directed = index;
                walk.steer.direct(RotationMath.yawOf(wp[index + 1][0] + 0.5D - walk.x, wp[index + 1][2] + 0.5D - walk.z));
            }
            walk.ticks(1, -60.0F);
            if (t % 10 == 0) {
                off += Math.abs(walk.z - (curveOf((int) Math.floor(walk.x)) + 0.5D));
                samples++;
            }
        }
        assertTrue(walk.x > 85.0D, "walks the route to the ore: x " + walk.x + " z " + walk.z + " waypoint " + index + "/" + wp.length);
        assertTrue(walk.bumps <= 5, "does not walk into the wall: " + walk.bumps);
        assertTrue(off / samples < 1.0D, "close to the middle on average: " + off / samples);
    }

    private static int middleOf(int x) {
        return (int) Math.round(3.0D * Math.sin(x / 12.0D));
    }

    @Test
    void walksAPlannedRouteRoundACornerInTheMiddle() {
        // 5 wide tunnel east without ore (x -2..30), round the corner a 5 wide tunnel north (x 26..30, middle 28.5)
        // with ore from z -40 on. The planner's route is walked leg by leg as the macro does (direction to the next
        // waypoint, waypoint passed within 2.5 blocks).
        TestMine mine = new TestMine(-4, -3, -70, 40, 6, 8).carve(-2, 1, -2, 30, 3, 2).carve(26, 1, -65, 30, 3, 2);
        oreFloor(mine, 26, -55, 30, -40);
        OrePlanner.Plan plan = OrePlanner.plan(mine, REDSTONE, new io.theprisons.core.nav.Walkability(mine, 3),
                Pos.pack(0, 1, 0), EAST, null, null, zone -> 1.0D, OrePlanner.Params.DEFAULT, () -> false);
        assertTrue(plan.found(), plan.reason());
        int[][] wp = plan.waypoints();
        Walk walk = new Walk(mine, 0.5D, 0.5D, EAST);
        int index = 0;
        int directed = -1;
        double worstNorthOff = 0.0D;
        for (int t = 0; t < 600 && index < wp.length - 1; t++) {
            while (index < wp.length - 1 && Math.hypot(walk.x - wp[index + 1][0] - 0.5D, walk.z - wp[index + 1][2] - 0.5D) <= 2.5D) {
                index++;
            }
            if (index >= wp.length - 1) {
                break;
            }
            if (index != directed) {
                directed = index;
                walk.steer.direct(RotationMath.yawOf(wp[index + 1][0] + 0.5D - walk.x, wp[index + 1][2] + 0.5D - walk.z));
            }
            walk.ticks(1, EAST);
            if (walk.z < -10.0D) {
                worstNorthOff = Math.max(worstNorthOff, Math.abs(walk.x - 28.5D));
            }
        }
        assertTrue(walk.z < -35.0D, "round the corner up to the ore: x " + walk.x + " z " + walk.z + " waypoint " + index + "/" + wp.length);
        assertTrue(worstNorthOff <= 1.0D, "in the middle of the north tunnel: " + worstNorthOff);
    }

    @Test
    void onlyWalksOnStoneDeepslateAndOre() {
        // 5 wide tunnel east, ore all along; at x 10..14 the middle 3 columns of the floor are planks (z -1..1), only
        // the outer columns are stone. It never sets foot on the planks: round them on a stone column or turn.
        TestMine mine = new TestMine(-4, -3, -8, 60, 6, 8).carve(-2, 1, -2, 55, 3, 2);
        oreFloor(mine, -2, -2, 55, 2);
        for (int x = 10; x <= 14; x++) {
            for (int z = -1; z <= 1; z++) {
                mine.foreignBlock(x, 0, z);
            }
        }
        Walk walk = new Walk(mine, 0.5D, 0.5D, EAST);
        boolean onPlanks = false;
        for (int i = 0; i < 200; i++) {
            walk.ticks(1, EAST);
            int bx = (int) Math.floor(walk.x);
            int bz = (int) Math.floor(walk.z);
            onPlanks |= bx >= 10 && bx <= 14 && bz >= -1 && bz <= 1;
        }
        assertTrue(!onPlanks, "stood on the planks");
    }

    @Test
    void crossesAShortGapToTheNextGuardButNotAWideOne() {
        TestMine mine = new TestMine(-24, -3, -8, 70, 6, 8).carve(-20, 1, -2, 65, 3, 2);
        oreFloor(mine, -20, -2, 65, 2);
        GuardArea near = new GuardArea();
        near.update(new double[][]{{0.5, 1, 0.5}, {34.5, 1, 0.5}}, 0.5, 1, 0.5, 0L);
        Walk crossing = new Walk(mine, 0.5D, 0.5D, EAST);
        crossing.steer.guards(near);
        double maxX = 0.0D;
        for (int i = 0; i < 40; i++) {
            crossing.ticks(10, EAST);
            maxX = Math.max(maxX, crossing.x);
        }
        assertTrue(maxX > 30.0D, "gap of 4: walks on to the next guard: " + maxX);

        TestMine other = new TestMine(-24, -3, -8, 70, 6, 8).carve(-20, 1, -2, 65, 3, 2);
        oreFloor(other, -20, -2, 65, 2);
        GuardArea far = new GuardArea();
        far.update(new double[][]{{0.5, 1, 0.5}, {41.5, 1, 0.5}}, 0.5, 1, 0.5, 0L);
        Walk stopping = new Walk(other, 0.5D, 0.5D, EAST);
        stopping.steer.guards(far);
        maxX = 0.0D;
        for (int i = 0; i < 40; i++) {
            stopping.ticks(10, EAST);
            maxX = Math.max(maxX, stopping.x);
        }
        assertTrue(maxX < 15.0D, "gap of 11: turns before leaving the circle: " + maxX);
    }
}
