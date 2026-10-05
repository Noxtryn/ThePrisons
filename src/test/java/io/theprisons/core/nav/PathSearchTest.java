package io.theprisons.core.nav;

import io.theprisons.testing.TestMine;

import it.unimi.dsi.fastutil.longs.LongList;
import org.junit.jupiter.api.Test;


import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PathSearchTest {
    private static PathSearch.Result find(VoxelView view, long start, long goal) {
        return PathSearch.findPath(new Walkability(view, 3), start, LongList.of(goal), 100_000, 128, 0L, () -> false);
    }

    private static boolean contains(NavigationPath path, int x, int y, int z) {
        long wanted = Pos.pack(x, y, z);
        for (long node : path.nodes()) {
            if (node == wanted) {
                return true;
            }
        }
        return false;
    }

    /** TEST E: a long straight tunnel is one segment and walked without direction changes. */
    @Test
    void straightTunnelIsOneSegment() {
        TestMine mine = new TestMine(0, 0, 0, 40, 15, 20);
        mine.carve(2, 5, 10, 30, 6, 10);
        PathSearch.Result result = find(mine, Pos.pack(2, 5, 10), Pos.pack(30, 5, 10));
        assertTrue(result.found(), result.reason());
        NavigationPath path = result.path();
        // 28 steps, each into a 1-wide (tight) cell: +0.15 per step.
        assertEquals(28.0D * 1.15D, path.cost(), 1.0E-6D);
        assertEquals(0, path.turns());
        assertEquals(1, path.segments().size());
    }

    /** TEST D: a one block high passage is rejected; the longer two-high detour is used. */
    @Test
    void lowTunnelDoesNotFitThePlayer() {
        TestMine mine = new TestMine(0, 0, 0, 20, 15, 20);
        mine.carve(2, 5, 2, 10, 6, 2).set(6, 6, 2, Cell.SOLID);
        mine.carve(2, 5, 2, 2, 6, 6).carve(2, 5, 6, 10, 6, 6).carve(10, 5, 2, 10, 6, 6);
        PathSearch.Result result = find(mine, Pos.pack(2, 5, 2), Pos.pack(10, 5, 2));
        assertTrue(result.found(), result.reason());
        assertFalse(contains(result.path(), 6, 5, 2), "path squeezes through the 1-high gap");
        assertTrue(contains(result.path(), 6, 5, 6));
    }

    /** Planning part of TEST P: a one block ledge becomes a JUMP move. */
    @Test
    void ledgeIsPlannedAsJump() {
        TestMine mine = new TestMine(0, 0, 0, 20, 15, 10);
        mine.carve(2, 5, 2, 12, 7, 2).fill(8, 5, 2, 12, 5, 2);
        PathSearch.Result result = find(mine, Pos.pack(2, 5, 2), Pos.pack(12, 6, 2));
        assertTrue(result.found(), result.reason());
        NavigationPath path = result.path();
        int jumps = 0;
        for (int i = 1; i < path.size(); i++) {
            if (path.moves()[i] == MoveType.JUMP) {
                jumps++;
                assertEquals(Pos.pack(8, 6, 2), path.nodes()[i]);
            }
        }
        assertEquals(1, jumps);
    }

    /** TEST Q: a ledge under a low ceiling cannot be jumped; the planner takes the high-ceiling detour. */
    @Test
    void lowCeilingPreventsJumpAndUsesAlternative() {
        TestMine mine = new TestMine(0, 0, 0, 20, 15, 10);
        // Row z=2: 2 high tunnel, ledge at x>=8 with room above the ledge but not above the take-off block.
        mine.carve(2, 5, 2, 7, 6, 2).carve(8, 6, 2, 12, 7, 2);
        // Row z=4: 3 high tunnel with the same ledge.
        mine.carve(2, 5, 4, 12, 7, 4).fill(8, 5, 4, 12, 5, 4);
        mine.carve(2, 5, 3, 2, 6, 3);
        mine.carve(12, 6, 3, 12, 7, 3);

        Walkability walk = new Walkability(mine, 3);
        boolean[] jumpFromLowCeiling = new boolean[1];
        walk.neighbours(7, 5, 2, 5.0D, (x, y, z, feet, type, cost) -> {
            if (x == 8 && z == 2) {
                jumpFromLowCeiling[0] = true;
            }
        });
        assertFalse(jumpFromLowCeiling[0], "jump into a 2-high ceiling must not be offered");

        PathSearch.Result result = find(mine, Pos.pack(2, 5, 2), Pos.pack(12, 6, 2));
        assertTrue(result.found(), result.reason());
        assertTrue(contains(result.path(), 8, 6, 4), "detour through the high tunnel expected");
    }

    /** TEST I: tunnels crossing chunk / section borders are handled seamlessly by the section store. */
    @Test
    void pathAcrossChunkBorders() {
        TestMine mine = new TestMine(0, 0, 0, 47, 31, 47);
        mine.carve(5, 14, 5, 40, 15, 5).carve(40, 14, 5, 40, 15, 36).carve(20, 14, 36, 40, 15, 36);
        // A drop across the y=16 section border inside the last corridor is not needed; stay on one level.
        io.theprisons.core.world.SectionStore store = mine.store();
        PathSearch.Result result = find(store, Pos.pack(5, 14, 5), Pos.pack(20, 14, 36));
        assertTrue(result.found(), result.reason());
        assertTrue(result.path().size() > 60);
    }

    /** A* and the Dijkstra flood agree on the optimal cost (heuristic is admissible). */
    @Test
    void aStarMatchesFloodCost() {
        TestMine mine = new TestMine(0, 0, 0, 30, 15, 30);
        mine.carve(2, 5, 2, 25, 7, 25);
        mine.fill(10, 5, 5, 11, 6, 22).fill(15, 5, 3, 16, 6, 20);
        mine.fill(18, 5, 18, 25, 5, 25);
        Walkability walk = new Walkability(mine, 3);
        long start = Pos.pack(3, 5, 3);
        long goal = Pos.pack(24, 6, 24);
        PathSearch.Result result = PathSearch.findPath(walk, start, LongList.of(goal), 100_000, 64, 0L, () -> false);
        PathSearch.Flood flood = PathSearch.flood(walk, start, 100_000, 64, Double.POSITIVE_INFINITY, 0L, () -> false);
        assertTrue(result.found());
        assertNotNull(flood);
        assertEquals(flood.cost(goal), result.path().cost(), 1.0E-6D);
        NavigationPath fromFlood = flood.pathTo(goal);
        assertNotNull(fromFlood);
        assertEquals(fromFlood.cost(), result.path().cost(), 1.0E-6D);
    }


    @Test
    void cancelledSearchStops() {
        TestMine mine = new TestMine(0, 0, 0, 60, 15, 60);
        mine.carve(1, 5, 1, 59, 6, 59);
        PathSearch.Result result = PathSearch.findPath(new Walkability(mine, 3), Pos.pack(1, 5, 1),
                LongList.of(Pos.pack(59, 5, 80)), // unreachable: the search would expand the whole room
                100_000, 128, 0L, () -> true);
        assertEquals(PathSearch.Status.CANCELLED, result.status());
    }

    /** A goal beyond the search budget yields a partial path that ends closer to the goal (Baritone style). */
    @Test
    void farGoalGivesPartialPath() {
        TestMine mine = new TestMine(0, 0, 0, 200, 15, 20);
        mine.carve(2, 5, 10, 190, 6, 10);
        PathSearch.Result result = PathSearch.findPath(new Walkability(mine, 3), Pos.pack(2, 5, 10),
                LongList.of(Pos.pack(190, 5, 10)), 60, 256, 0L, () -> false);
        assertEquals(PathSearch.Status.PARTIAL, result.status());
        assertTrue(result.usable());
        assertFalse(result.found());
        assertTrue(Pos.x(result.path().goal()) > 40, "walked a good part of the way");
    }

    /** No partial path when nothing gets closer (walled off). */
    @Test
    void walledOffGoalHasNoPartialPath() {
        TestMine mine = new TestMine(0, 0, 0, 40, 15, 20);
        mine.carve(2, 5, 10, 4, 6, 10).carve(30, 5, 10, 32, 6, 10);
        PathSearch.Result result = find(mine, Pos.pack(2, 5, 10), Pos.pack(31, 5, 10));
        assertFalse(result.usable());
    }
}
