package io.theprisons.modules.mining.ore.route;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class RouteTrackerTest {
    /** Walks from (x0, z0) to (x1, z1) in 0.2-block steps with the given view yaw. */
    private static void walk(RouteTracker tracker, double x0, double z0, double x1, double z1, float yaw) {
        int steps = (int) Math.ceil(Math.hypot(x1 - x0, z1 - z0) / 0.2D);
        for (int i = 1; i <= steps; i++) {
            double t = i / (double) steps;
            tracker.update(x0 + (x1 - x0) * t, 64.0D, z0 + (z1 - z0) * t, yaw, true);
        }
    }

    @Test
    void aStraightWalkIsOnlyStartAndEnd() {
        RouteTracker tracker = new RouteTracker();
        tracker.start(0.5D, 64.0D, 0.5D, 0.0F);
        walk(tracker, 0.5D, 0.5D, 0.5D, 15.5D, 0.0F); // yaw 0 = south (+z)
        tracker.finish(0.5D, 64.0D, 15.5D);
        assertEquals(2, tracker.waypoints().size());
        assertArrayEquals(new int[]{0, 64, 15}, tracker.waypoints().get(1));
    }

    @Test
    void aCornerTurningTheViewMakesAWaypoint() {
        RouteTracker tracker = new RouteTracker();
        tracker.start(0.5D, 64.0D, 0.5D, 0.0F);
        walk(tracker, 0.5D, 0.5D, 0.5D, 10.5D, 0.0F);
        // Turn 90° to the west (yaw 90) and walk on.
        walk(tracker, 0.5D, 10.5D, -9.5D, 10.5D, 90.0F);
        tracker.finish(-9.5D, 64.0D, 10.5D);
        List<int[]> points = tracker.waypoints();
        assertEquals(3, points.size());
        assertEquals(10, points.get(1)[2], "the corner");
    }

    @Test
    void smallViewChangesAndTurningOnTheSpotDoNotCount() {
        RouteTracker tracker = new RouteTracker();
        tracker.start(0.5D, 64.0D, 0.5D, 0.0F);
        tracker.update(0.6D, 64.0D, 0.6D, 170.0F, true); // looks around before walking
        tracker.update(0.6D, 64.0D, 0.6D, 5.0F, true);
        walk(tracker, 0.5D, 0.5D, 0.5D, 10.5D, 15.0F);
        assertEquals(1, tracker.waypoints().size());
    }

    @Test
    void driftingTwoBlocksSidewaysMakesAWaypoint() {
        RouteTracker tracker = new RouteTracker();
        tracker.start(0.5D, 64.0D, 0.5D, 0.0F);
        walk(tracker, 0.5D, 0.5D, 0.5D, 4.5D, 0.0F);
        walk(tracker, 0.5D, 4.5D, 3.0D, 10.5D, 0.0F); // view straight, but walks off to the side
        assertEquals(2, tracker.waypoints().size());
    }

    @Test
    void theOreMinedMostNamesTheRoute() {
        Map<String, Integer> mined = new LinkedHashMap<>();
        assertEquals(Route.NO_ORE, Route.mostMined(mined));
        mined.put("redstone", 3);
        mined.put("deepslate_redstone", 12);
        assertEquals("deepslate_redstone", Route.mostMined(mined));
    }

    @Test
    void savesAndLoadsRoutes() {
        Route route = new Route("Redstone 1", "redstone", Map.of("redstone", 4), List.of(new int[]{1, 64, 2}, new int[]{-3, 65, 9}));
        Route back = RouteStore.fromJson(RouteStore.toJson(route));
        assertEquals("Redstone 1", back.name());
        assertEquals("redstone", back.ore());
        assertEquals(4, back.mined().get("redstone"));
        assertArrayEquals(new int[]{-3, 65, 9}, back.waypoints().get(1));
    }
}
