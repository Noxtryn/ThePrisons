package io.theprisons.core.nav;

import io.theprisons.testing.TestMine;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WalkabilityTest {
    private final TestMine mine = new TestMine(0, 0, 0, 31, 15, 31);
    private final Walkability walk = new Walkability(mine, 3);

    @Test
    void fullBlockFloorPutsFeetOnTheBlockTop() {
        mine.carve(1, 5, 1, 1, 6, 1);
        assertEquals(5.0D, walk.standHeight(1, 5, 1), 1.0E-9D);
    }

    @Test
    void bottomSlabRaisesTheFeet() {
        mine.carve(2, 5, 2, 2, 7, 2).set(2, 5, 2, Cell.partial(0, 8));
        assertEquals(5.5D, walk.standHeight(2, 5, 2), 1.0E-9D);
    }

    @Test
    void fenceBelowCountsAsFloorAtOneAndAHalf() {
        mine.carve(3, 5, 3, 3, 7, 3).set(3, 4, 3, Cell.partial(0, 24));
        assertEquals(5.5D, walk.standHeight(3, 5, 3), 1.0E-9D);
    }

    @Test
    void airWithoutFloorIsNotStandable() {
        mine.carve(4, 4, 4, 4, 6, 4);
        assertTrue(Double.isNaN(walk.standHeight(4, 5, 4)));
        assertEquals(4.0D, walk.standHeight(4, 4, 4), 1.0E-9D);
    }

    @Test
    void unknownBlocksAreNeverWalkable() {
        // Outside the mine there is no data: must not be treated as air.
        assertTrue(Double.isNaN(walk.standHeight(40, 5, 40)));
        mine.carve(0, 5, 5, 0, 6, 5);
        assertTrue(walk.isStandable(0, 5, 5));
        List<Long> targets = new ArrayList<>();
        walk.neighbours(0, 5, 5, 5.0D, (x, y, z, feet, type, cost) -> targets.add(Pos.pack(x, y, z)));
        assertFalse(targets.contains(Pos.pack(-1, 5, 5)));
    }

    @Test
    void oneBlockGapIsTooLowForThePlayer() {
        mine.carve(5, 5, 5, 5, 5, 5);
        assertTrue(Double.isNaN(walk.standHeight(5, 5, 5)));
    }

    @Test
    void topSlabCeilingLeavesOnlyOneAndAHalfBlocks() {
        mine.carve(6, 5, 6, 6, 6, 6).set(6, 6, 6, Cell.partial(8, 16));
        assertTrue(Double.isNaN(walk.standHeight(6, 5, 6)));
    }

    @Test
    void dangerAndLiquidAreAvoided() {
        mine.carve(7, 5, 7, 7, 6, 7).set(7, 4, 7, Cell.encode(0, 16, Cell.COLLISION | Cell.FULL | Cell.DANGER | Cell.BREAKABLE));
        assertTrue(Double.isNaN(walk.standHeight(7, 5, 7)));
        mine.carve(8, 5, 8, 8, 6, 8).set(8, 5, 8, Cell.encode(0, 0, Cell.LIQUID));
        assertTrue(Double.isNaN(walk.standHeight(8, 5, 8)));
    }

    @Test
    void diagonalMovesDoNotCutWallCorners() {
        // L-shaped corridor: (10,5,10) -> (11,5,10) -> (11,5,11). The diagonal (10,10)->(11,11) would clip (10,11).
        mine.carve(10, 5, 10, 11, 6, 10).carve(11, 5, 11, 11, 6, 11);
        List<MoveType> moves = new ArrayList<>();
        walk.neighbours(10, 5, 10, 5.0D, (x, y, z, feet, type, cost) -> {
            if (x == 11 && z == 11) {
                moves.add(type);
            }
        });
        assertTrue(moves.isEmpty());
    }

    @Test
    void stepUpNeedsAJumpAndDropIsLimited() {
        mine.carve(12, 5, 12, 20, 7, 12).fill(14, 5, 12, 20, 5, 12);
        List<MoveType> up = new ArrayList<>();
        walk.neighbours(13, 5, 12, 5.0D, (x, y, z, feet, type, cost) -> {
            if (x == 14) {
                up.add(type);
            }
        });
        assertEquals(List.of(MoveType.JUMP), up);

        // A 4 block drop is too deep with max drop 3.
        mine.carve(22, 1, 12, 22, 7, 12).carve(21, 5, 12, 21, 6, 12);
        List<MoveType> drop = new ArrayList<>();
        walk.neighbours(21, 5, 12, 5.0D, (x, y, z, feet, type, cost) -> {
            if (x == 22) {
                drop.add(type);
            }
        });
        assertTrue(drop.isEmpty());
    }

    @Test
    void onlyStoneDeepslateAndOreCarryThePlayer() {
        mine.carve(1, 5, 1, 4, 6, 1).foreignBlock(2, 4, 1);
        assertEquals(5.0D, walk.standHeight(1, 5, 1), 1.0E-9D, "stone below");
        assertTrue(Double.isNaN(walk.standHeight(2, 5, 1)), "planks below: taboo");
        assertEquals(5.0D, walk.startHeight(2, 5, 1), 1.0E-9D, "but a way may start where the player stands");
        assertEquals(5.0D, new Walkability(mine, 3).exempt(2, 1).standHeight(2, 5, 1), 1.0E-9D, "the player's own column");
        assertEquals(Pos.pack(2, 5, 1), walk.settle(2.5D, 5.0D, 1.5D), "standing on planks: still found");
    }

    @Test
    void pathsGoRoundForeignFloor() {
        // A 3 wide corridor along x; the middle row has planks at x 5: the path takes a side row there.
        mine.carve(1, 5, 1, 12, 6, 3);
        mine.foreignBlock(5, 4, 2);
        PathSearch.Result result = PathSearch.findPath(walk, Pos.pack(1, 5, 2), it.unimi.dsi.fastutil.longs.LongList.of(Pos.pack(11, 5, 2)),
                10_000, 32, 0L, () -> false);
        assertTrue(result.found(), result.reason());
        for (long node : result.path().nodes()) {
            assertFalse(Pos.x(node) == 5 && Pos.z(node) == 2, "stepped on the planks");
        }
    }
}
