package com.freelocs.theprisons.modules.mining.ore.route;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WaypointMathTest {
    /** An L-shaped route: south 10 blocks, then west 10 blocks. */
    private static final List<int[]> ROUTE = List.of(new int[]{0, 64, 0}, new int[]{0, 64, 10}, new int[]{-10, 64, 10});

    @Test
    void aPointBesideASegmentGoesBetweenItsWaypoints() {
        assertEquals(1, WaypointMath.insertionIndex(ROUTE, new int[]{1, 64, 5}), "between 1 and 2 -> becomes 2");
        assertEquals(2, WaypointMath.insertionIndex(ROUTE, new int[]{-5, 64, 11}), "between 2 and 3 -> becomes 3");
    }

    @Test
    void pointsBeyondTheEndsExtendTheRouteInItsDirection() {
        assertEquals(3, WaypointMath.insertionIndex(ROUTE, new int[]{-16, 64, 10}), "after the last one");
        assertEquals(0, WaypointMath.insertionIndex(ROUTE, new int[]{0, 64, -6}), "before the first one");
    }

    @Test
    void findsTheWaypointInTheViewRay() {
        // Standing at (0.5, 65.6, -5) looking south (+z): waypoint 1 is straight ahead, closer than waypoint 2.
        assertEquals(0, WaypointMath.lookedAt(ROUTE, 0.5D, 64.5D, -5.0D, 0.0D, 0.0D, 1.0D, 64.0D));
        // Looking west from next to waypoint 2 towards waypoint 3.
        assertEquals(2, WaypointMath.lookedAt(ROUTE, -3.0D, 64.5D, 10.5D, -1.0D, 0.0D, 0.0D, 64.0D));
        // Looking up into the sky: nothing.
        assertEquals(-1, WaypointMath.lookedAt(ROUTE, 0.5D, 64.5D, 5.0D, 0.0D, 1.0D, 0.0D, 64.0D));
    }
}
