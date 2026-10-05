package com.freelocs.theprisons.modules.mining.ore.route;

import com.freelocs.theprisons.core.nav.MoveType;
import com.freelocs.theprisons.core.nav.NavigationPath;
import com.freelocs.theprisons.core.nav.Pos;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RouteCorridorTest {
    private static final int[] A = {0, 64, 0};
    private static final int[] B = {0, 64, 20};

    private static NavigationPath path(int[][] cells) {
        long[] nodes = new long[cells.length];
        double[] feet = new double[cells.length];
        MoveType[] moves = new MoveType[cells.length];
        for (int i = 0; i < cells.length; i++) {
            nodes[i] = Pos.pack(cells[i][0], 64, cells[i][1]);
            feet[i] = 64;
            moves[i] = i == 0 ? MoveType.START : MoveType.WALK;
        }
        return new NavigationPath(nodes, feet, moves, cells.length, 0L);
    }

    @Test
    void projectsOntoTheSegment() {
        double[] q = RouteCorridor.project(A, B, 3.5D, 8.5D);
        assertEquals(8.0D, q[0], 1.0E-9);
        assertEquals(3.0D, q[1], 1.0E-9);
        assertEquals(20.0D, q[2], 1.0E-9);
    }

    @Test
    void aPathOutsideTheCorridorIsAnOwnWay() {
        assertFalse(RouteCorridor.leaves(path(new int[][]{{0, 2}, {2, 4}, {4, 6}, {4, 8}}), A, B));
        assertTrue(RouteCorridor.leaves(path(new int[][]{{0, 2}, {4, 4}, {14, 6}, {4, 8}}), A, B));
    }
}
