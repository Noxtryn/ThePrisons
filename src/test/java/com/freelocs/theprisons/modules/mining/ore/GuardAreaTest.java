package com.freelocs.theprisons.modules.mining.ore;

import com.freelocs.theprisons.core.nav.Pos;
import it.unimi.dsi.fastutil.longs.Long2DoubleMap;
import it.unimi.dsi.fastutil.longs.Long2DoubleOpenHashMap;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GuardAreaTest {
    @Test
    void withoutGuardsNothingIsBlocked() {
        GuardArea area = new GuardArea();
        assertFalse(area.blocks(500, 0, 500, 0, 0, 0));
    }

    @Test
    void stepsEndAtTheRadiusButMayComeBack() {
        GuardArea area = new GuardArea();
        area.radius(12.0D);
        area.update(new double[][]{{0, 64, 0}}, 0, 64, 0, 0L);
        assertFalse(area.blocks(10, 64, 0, 9, 64, 0), "inside 12 blocks (less the edge)");
        assertTrue(area.blocks(11, 64, 0, 10, 64, 0), "the last 1.5 blocks to the edge are not walked into");
        assertTrue(area.blocks(13, 64, 0, 11, 64, 0), "leaving the area");
        assertFalse(area.blocks(19, 64, 0, 20, 64, 0), "outside, but closer to the guard");
        assertTrue(area.blocks(21, 64, 0, 20, 64, 0), "outside and further away");
        assertTrue(area.blocks(0, 78, 0, 0, 70, 0), "12 blocks counts in 3D");
    }

    @Test
    void guardsFromAnEarlierRunAreKnownUntilTheyAreMissedNearby() {
        GuardArea area = new GuardArea();
        area.remember(0, 64, 0);
        area.remember(300, 64, 0);
        area.remember(1, 64, 1);
        assertEquals(2, area.size(), "the same guard is not added twice");
        assertTrue(area.inside(5, 64, 0));
        area.update(new double[0][], 270, 64, 0, 20_000L);
        assertEquals(2, area.size(), "30 blocks away the client may just not get the NPC: kept");
        area.update(new double[0][], 290, 64, 0, 20_000L);
        assertEquals(1, area.size(), "the guard at 300 should be in sight from 290 and is not: forgotten");
        assertEquals(0.0D, area.guards().get(0)[0]);
    }

    @Test
    void guardsAreRememberedOutOfSightAndForgottenWhenGoneNearby() {
        GuardArea area = new GuardArea();
        area.update(new double[][]{{0, 64, 0}, {100, 64, 0}}, 0, 64, 0, 0L);
        area.update(new double[][]{{1, 64, 0}}, 0, 64, 0, 3_000L);
        assertEquals(2, area.size(), "moved a little = same guard");
        area.update(new double[0][], 0, 64, 0, 10_000L);
        assertEquals(2, area.size(), "not seen for a few seconds: still kept (no flicker)");
        area.update(new double[0][], 0, 64, 0, 20_000L);
        assertEquals(1, area.size(), "the near one is gone, the far one (out of sight) is kept");
        assertEquals(100.0D, area.guards().get(0)[0]);
    }

    @Test
    void hardSafetyBudgetZeroInvalidatesAnOldExcursionCorridor() {
        GuardArea area = new GuardArea();
        area.update(new double[][]{{0.5, 64, 0.5}}, 0.5, 64, 0.5, 0L);
        area.outsideBudget(8);
        area.corridor(new long[]{Pos.pack(20, 64, 0)});
        assertTrue(area.inside(20.5, 64, 0.5), "an active excursion corridor may temporarily extend the zone");

        area.outsideBudget(0);
        assertFalse(area.inside(20.5, 64, 0.5), "zero outside budget must invalidate the old corridor immediately");
        assertTrue(area.blocks(14.0, 64, 0.0, 13.0, 64, 0.0), "the hard lock keeps movement at the guarded edge");
    }

    @Test
    void pathNodesOutsideAreForbidden() {
        GuardArea area = new GuardArea();
        area.update(new double[][]{{0.5, 64, 0.5}}, 0, 64, 0, 0L);
        Long2DoubleMap costs = area.penalties(new Long2DoubleOpenHashMap(), 1.0D, 64.0D, 0.0D);
        assertFalse(costs.isEmpty());
        assertEquals(0.0D, costs.get(Pos.pack(5, 64, 0)));
        assertEquals(Double.POSITIVE_INFINITY, costs.get(Pos.pack(20, 64, 0)));
    }

    @Test
    void scoreboardTaxDecidesAndTheEdgeIsLearned() {
        GuardArea area = new GuardArea();
        area.tax(Boolean.FALSE, 0, 64, 0);
        assertFalse(area.scoreboard(), "no tax line seen yet: the radius decides");
        area.tax(Boolean.TRUE, 0, 64, 0);
        assertTrue(area.scoreboard());
        assertFalse(area.isEmpty(), "taxed = guarded, even without a guard in sight");
        assertTrue(area.inside(0, 64, 0));
        assertFalse(area.blocks(100, 64, 0, 99, 64, 0), "no radius: anywhere while taxed");

        area.tax(Boolean.FALSE, 20, 64, 0);
        assertFalse(area.inside(20, 64, 0), "tax gone = outside");
        area.tax(Boolean.TRUE, 18, 64, 0);
        assertTrue(area.blocks(19.5, 64, 0.5, 17.5, 64, 0.5), "ways end a block before a learned edge block");
        assertFalse(area.blocks(16.5, 64, 0.5, 17.5, 64, 0.5), "away from the edge is fine");
        assertFalse(area.blocks(18.5, 64, 2.5, 19.5, 64, 0.5), "next to the edge already: it may walk on beside it");
        assertTrue(area.blocks(20.5, 64, 0.5, 19.5, 64, 0.5), "but not onto it");
        assertEquals(18, area.lastInside()[0]);

        Long2DoubleMap costs = area.penalties(new Long2DoubleOpenHashMap(), 0.5D, 64.0D, 0.5D);
        assertEquals(Double.POSITIVE_INFINITY, costs.get(Pos.pack(21, 64, 0)));
        assertEquals(Double.POSITIVE_INFINITY, costs.get(Pos.pack(10, 64, 0)), "never taxed there yet: paths do not go into the unknown");
        assertEquals(0.0D, costs.get(Pos.pack(16, 64, 1)), "next to known taxed ground");
    }

    @Test
    void plannedPathsOnlyLeadOverKnownTaxedGround() {
        GuardArea area = new GuardArea();
        for (int x = 0; x <= 30; x++) {
            area.tax(Boolean.TRUE, x, 64, 0);
        }
        area.tax(Boolean.FALSE, 31, 64, 0);
        Long2DoubleMap costs = area.penalties(new Long2DoubleOpenHashMap(), 0.5D, 64.0D, 0.5D);
        assertEquals(0.0D, costs.get(Pos.pack(25, 64, 2)), "within 2 blocks of the walked taxed way");
        assertEquals(Double.POSITIVE_INFINITY, costs.get(Pos.pack(25, 64, 5)), "unknown ground beside it");
        assertEquals(Double.POSITIVE_INFINITY, costs.get(Pos.pack(31, 64, 0)), "the learned edge");
    }

    @Test
    void theTaxZoneIsKeptForTheNextRun() {
        GuardArea first = new GuardArea();
        first.tax(Boolean.TRUE, 0, 64, 0);
        first.tax(Boolean.TRUE, 5, 64, 0);
        first.tax(Boolean.FALSE, 9, 64, 0);
        GuardArea next = new GuardArea();
        next.restore(first.taxedBlocks(), first.untaxedBlocks());
        next.tax(Boolean.TRUE, 0, 64, 0);
        Long2DoubleMap costs = next.penalties(new Long2DoubleOpenHashMap(), 0.5D, 64.0D, 0.5D);
        assertEquals(0.0D, costs.get(Pos.pack(6, 64, 0)), "known from the earlier run");
        assertTrue(next.blocks(8.5, 64, 0.5, 7.5, 64, 0.5), "the edge from the earlier run is known at once");
    }

    @Test
    void theTaxDecidesOnceSeenAndTheCirclesOnlyWithoutIt() {
        // No tax line seen: two guards 24 blocks apart, their 15 block circles overlap to one area.
        GuardArea circles = new GuardArea();
        circles.update(new double[][]{{0.5, 64, 0.5}, {24.5, 64, 0.5}}, 0.5, 64, 0.5, 0L);
        assertFalse(circles.scoreboard(), "no tax line: the circles decide");
        assertFalse(circles.blocks(12.5, 64, 0.5, 11.5, 64, 0.5), "between the guards: inside both circles");
        assertTrue(circles.blocks(39.5, 64, 0.5, 38.5, 64, 0.5), "past 13.5 (15 less the edge): not walked into");
        // User rule: stay where the guard tax is on - once a tax line is seen the tax decides, guards or not.
        GuardArea area = new GuardArea();
        area.update(new double[][]{{0.5, 64, 0.5}, {24.5, 64, 0.5}}, 0.5, 64, 0.5, 0L);
        area.tax(Boolean.TRUE, 0, 64, 0);
        assertTrue(area.scoreboard(), "tax line seen: the tax decides");
        assertFalse(area.blocks(39.5, 64, 0.5, 38.5, 64, 0.5), "beyond the circles while taxed: free");
    }

    @Test
    void theEdgeIsMarkedAcrossTheWayWhereTheTaxWentOff() {
        GuardArea area = new GuardArea();
        for (int x = 0; x <= 19; x++) {
            area.tax(Boolean.TRUE, x, 64, 0);
        }
        area.tax(Boolean.FALSE, 20, 64, 0);
        area.edge(20, 64, 0, -90.0F);
        area.tax(Boolean.TRUE, 17, 64, 0);
        assertTrue(area.blocks(19.5, 64, 3.5, 18.5, 64, 3.5), "3 blocks beside the spot where it walked out: edge too");
        assertFalse(area.blocks(17.5, 64, 3.5, 16.5, 64, 3.5), "before the edge it is fine");
        assertEquals(Double.POSITIVE_INFINITY, area.penalties(new Long2DoubleOpenHashMap(), 0.5D, 64.0D, 0.5D)
                .get(Pos.pack(20, 64, -3)));
    }

    @Test
    void aGapOfAtMostFiveBlocksBetweenTwoCirclesIsCrossed() {
        GuardArea area = new GuardArea();
        // 34 blocks apart: 15 + 15 leaves a gap of 4.
        area.update(new double[][]{{0.5, 64, 0.5}, {34.5, 64, 0.5}}, 0.5, 64, 0.5, 0L);
        assertTrue(area.inside(17.5, 64, 0.5), "the middle of the gap");
        assertFalse(area.blocks(17.5, 64, 0.5, 16.5, 64, 0.5), "walked across");
        assertFalse(area.blocks(17.5, 64, 2.5, 16.5, 64, 2.5), "a few blocks beside the line too (a tunnel)");
        assertTrue(area.blocks(17.5, 64, 9.5, 17.5, 64, 8.5), "but not far off it");
    }

    @Test
    void aWiderGapIsNotCrossed() {
        GuardArea area = new GuardArea();
        // 41 blocks apart: a gap of 11.
        area.update(new double[][]{{0.5, 64, 0.5}, {41.5, 64, 0.5}}, 0.5, 64, 0.5, 0L);
        assertFalse(area.inside(20.5, 64, 0.5), "the middle of the gap is outside");
        assertTrue(area.blocks(14.5, 64, 0.5, 13.5, 64, 0.5), "turns at its own edge");
    }

    @Test
    void taxModeWayBackLeadsDeepIntoTheZoneNotToTheEdge() {
        GuardArea area = new GuardArea();
        for (int x = 0; x <= 20; x++) {
            area.tax(Boolean.TRUE, x, 64, 0);
        }
        area.tax(Boolean.FALSE, 21, 64, 0);
        int[] target = area.taxedTarget(21.5D, 64.0D, 0.5D);
        assertTrue(target != null && target[0] <= 15, "at least 6 blocks from the edge: " + (target == null ? "none" : target[0]));
    }

    @Test
    void lookAheadSeesTheCircleEnd() {
        GuardArea area = new GuardArea();
        area.update(new double[][]{{0, 64, 0}}, 0, 64, 0, 0L);
        assertTrue(area.predictInside(10, 64, 0));
        assertFalse(area.predictInside(20, 64, 0), "5 blocks further on is outside the 15 block circle");
        assertTrue(new GuardArea().predictInside(500, 64, 0), "nothing known: no limit");
    }

    @Test
    void plannerUsesTheSameZoneAsTheSteering() {
        // One guard at 0,0 (radius 15). The player stands outside at 20,0: only the blocks right next to it back in are
        // open to the planner; a way round outside (also closer to the guard than the player) is not.
        GuardArea area = new GuardArea();
        area.update(new double[][]{{0, 64, 0}}, 0, 64, 0, 0L);
        Long2DoubleMap costs = area.penalties(new Long2DoubleOpenHashMap(), 20.5, 64, 0.5);
        assertEquals(0.0D, costs.get(Pos.pack(5, 64, 0)), "inside");
        assertEquals(0.0D, costs.get(Pos.pack(18, 64, 0)), "the step back in next to the player");
        assertEquals(Double.POSITIVE_INFINITY, costs.get(Pos.pack(12, 64, 14)), "outside, away from the player");
    }

    @Test
    void groundLearnedTaxedCountsAsGuardedBeyondTheCircle() {
        // A guard 6 blocks higher: at 13.4 blocks (3D) the circle says "edge", the tax said "guarded" here before.
        GuardArea area = new GuardArea();
        area.update(new double[][]{{704, 85, 237}}, 704, 85, 237, 0L);
        area.edge(2.0D);
        assertTrue(area.blocks(705.5, 79, 224.5, 705.5, 79, 225.5), "circle only: the step leaves the area");
        area.restore(new long[]{Pos.pack(705, 79, 224), Pos.pack(705, 79, 223)}, new long[0]);
        assertFalse(area.blocks(705.5, 79, 224.5, 705.5, 79, 225.5), "learned taxed: still guarded");
        area.restore(new long[0], new long[]{Pos.pack(705, 79, 222)});
        assertTrue(area.blocks(705.5, 79, 222.5, 705.5, 79, 223.5), "next to learned outside: not");
    }

    @Test
    void excursionsReachAFewBlocksOutAtACost() {
        // Guard at 0,0: ways end at 13.5 blocks. Budget 3 → up to 5 blocks beyond, at a cost; 0 → none.
        GuardArea area = new GuardArea();
        area.update(new double[][]{{0, 64, 0}}, 0, 64, 0, 0L);
        assertEquals(Double.POSITIVE_INFINITY, area.penalties(new Long2DoubleOpenHashMap(), 0.5, 64, 0.5).get(Pos.pack(15, 64, 0)));
        area.outsideBudget(3);
        Long2DoubleMap costs = area.penalties(new Long2DoubleOpenHashMap(), 0.5, 64, 0.5);
        assertEquals(0.0D, costs.get(Pos.pack(10, 64, 0)), "inside: no extra cost");
        assertEquals(GuardArea.OUTSIDE_COST, costs.get(Pos.pack(14, 64, 0)), "a block out: allowed, costs more");
        assertEquals(Double.POSITIVE_INFINITY, costs.get(Pos.pack(20, 64, 0)), "further out than budget + 2: not");
    }

    @Test
    void longestOutsideCountsTheUnguardedBlocksInARow() {
        GuardArea area = new GuardArea();
        area.update(new double[][]{{0, 64, 0}}, 0, 64, 0, 0L);
        long[] dip = {Pos.pack(12, 64, 0), Pos.pack(13, 64, 0), Pos.pack(14, 64, 0), Pos.pack(15, 64, 0),
                Pos.pack(15, 64, 1), Pos.pack(14, 64, 1), Pos.pack(13, 64, 1), Pos.pack(12, 64, 1)};
        assertEquals(6, area.longestOutside(dip, 0.5, 64, 0.5), "out and back in: x 13-15 and back lie beyond 13.5");
        long[] out = {Pos.pack(12, 64, 0), Pos.pack(13, 64, 0), Pos.pack(14, 64, 0)};
        assertEquals(Integer.MAX_VALUE, area.longestOutside(out, 0.5, 64, 0.5), "ending outside is never allowed");
        assertEquals(0, new GuardArea().longestOutside(out, 0.5, 64, 0.5), "nothing known: no limit");
    }

    @Test
    void theSteeringMayFollowTheRouteOutOnlyWithABudget() {
        GuardArea area = new GuardArea();
        area.update(new double[][]{{0, 64, 0}}, 0, 64, 0, 0L);
        long[] route = {Pos.pack(13, 64, 0), Pos.pack(14, 64, 0), Pos.pack(15, 64, 0)};
        area.corridor(route);
        assertTrue(area.blocks(15.5, 64, 0.5, 14.5, 64, 0.5), "budget 0: no corridor");
        area.outsideBudget(6);
        area.corridor(route);
        assertFalse(area.blocks(15.5, 64, 0.5, 14.5, 64, 0.5), "on the route: may go on");
        assertTrue(area.inside(15.5, 64, 4.5), "a lane beside the route still counts");
        assertFalse(area.inside(15.5, 64, 7.5), "off the route (more than the 5 lane blocks aside): outside");
        assertFalse(area.guardedHere(15.5, 64, 0.5), "really guarded: no");
        area.corridor(null);
        assertTrue(area.blocks(15.5, 64, 0.5, 14.5, 64, 0.5), "route dropped: strict again");
    }

    @Test
    void freeSteeringRoamsNearTheZoneWhileBudgetIsLeft() {
        GuardArea area = new GuardArea();
        area.update(new double[][]{{0, 64, 0}}, 0, 64, 0, 0L);
        area.outsideBudget(6);
        assertTrue(area.blocks(15.5, 64, 0.5, 14.5, 64, 0.5), "no excursion: ends at the edge");
        area.roam(true);
        assertFalse(area.blocks(15.5, 64, 0.5, 14.5, 64, 0.5), "budget left: walks on near the zone");
        assertTrue(area.inside(15.5, 64, 0.5));
        assertTrue(area.blocks(23.5, 64, 0.5, 22.5, 64, 0.5), "never far out (budget + 2 beyond the edge)");
        area.roam(false);
        assertFalse(area.inside(15.5, 64, 0.5), "budget used up: outside, back in");
    }
}
