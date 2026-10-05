package io.theprisons.modules.mining.ore;

import io.theprisons.core.control.FollowMotion;
import io.theprisons.core.nav.MoveType;
import io.theprisons.core.nav.NavigationPath;
import io.theprisons.core.nav.Pos;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LaneDriverTest {
    /** Path through the given cells (flat, feet y = 1); a cell with y = 2 is a step up. */
    private static NavigationPath path(int[][] cells) {
        long[] nodes = new long[cells.length];
        double[] feet = new double[cells.length];
        MoveType[] moves = new MoveType[cells.length];
        for (int i = 0; i < cells.length; i++) {
            nodes[i] = Pos.pack(cells[i][0], cells[i][1], cells[i][2]);
            feet[i] = cells[i][1];
            moves[i] = i == 0 ? MoveType.START : cells[i][1] > cells[i - 1][1] ? MoveType.JUMP : MoveType.WALK;
        }
        return new NavigationPath(nodes, feet, moves, cells.length, 0L);
    }

    /** Walks the driver with a simple player: yaw follows the drive through the same springs as in game. */
    private static double[] drive(NavigationPath path, int maxTicks) {
        LaneDriver driver = new LaneDriver(3.0D);
        driver.start(path);
        FollowMotion view = new FollowMotion();
        view.reset(0.0F, 50.0F);
        double x = Pos.x(path.nodes()[0]) + 0.5D;
        double z = Pos.z(path.nodes()[0]) + 0.5D;
        double y = path.feet()[0];
        double maxCross = 0.0D;
        int jumps = 0;
        int stopped = 0;
        for (int tick = 1; tick <= maxTicks; tick++) {
            LaneDriver.Drive d = driver.tick(new LaneDriver.Player(x, y, z, view.yaw(), true, false), true);
            if (d.status() == LaneDriver.Status.ARRIVED) {
                return new double[]{tick, maxCross, jumps, stopped};
            }
            assertTrue(d.status() != LaneDriver.Status.STUCK, "stuck at tick " + tick);
            for (int frame = 0; frame < 3; frame++) {
                view.step(d.yaw(), 50.0F, 1.0D / 60.0D, 9.0D, 6.0D);
            }
            if (d.jump()) {
                jumps++;
                y = Math.floor(y) + 1.0D;
            }
            if (d.forward()) {
                double speed = d.sprint() ? 0.28D : 0.216D;
                double rad = Math.toRadians(view.yaw());
                x += -Math.sin(rad) * speed;
                z += Math.cos(rad) * speed;
            } else {
                stopped++;
            }
            maxCross = Math.max(maxCross, distanceToPath(path, x, z));
        }
        throw new AssertionError("did not arrive");
    }

    private static double distanceToPath(NavigationPath path, double px, double pz) {
        double best = Double.POSITIVE_INFINITY;
        for (int i = 0; i < path.size() - 1; i++) {
            double ax = path.x(i);
            double az = path.z(i);
            double sx = path.x(i + 1) - ax;
            double sz = path.z(i + 1) - az;
            double l = sx * sx + sz * sz;
            double t = l == 0 ? 0 : Math.max(0, Math.min(1, ((px - ax) * sx + (pz - az) * sz) / l));
            best = Math.min(best, Math.hypot(px - (ax + sx * t), pz - (az + sz * t)));
        }
        return best;
    }

    @Test
    void walksAStraightLaneWithoutStopping() {
        List<int[]> cells = new ArrayList<>();
        for (int x = 0; x <= 20; x++) {
            cells.add(new int[]{0, 1, x});
        }
        double[] r = drive(path(cells.toArray(new int[0][])), 400);
        assertEquals(0.0D, r[3], "never stops on a straight lane");
        assertTrue(r[1] < 0.3D, "stays on the line: " + r[1]);
    }

    @Test
    void roundsACornerAsOneCurveCloseToTheWay() {
        List<int[]> cells = new ArrayList<>();
        for (int z = 0; z <= 10; z++) {
            cells.add(new int[]{0, 1, z});
        }
        for (int x = -1; x >= -10; x--) {
            cells.add(new int[]{x, 1, 10});
        }
        double[] r = drive(path(cells.toArray(new int[0][])), 400);
        assertTrue(r[1] < 1.0D, "keeps close to the way in the corner: " + r[1]);
        assertTrue(r[3] <= 4, "no real stop in the corner: stopped " + r[3] + " ticks");
    }

    @Test
    void takesAUTurnWithoutLosingTheWay() {
        List<int[]> cells = new ArrayList<>();
        for (int z = 0; z <= 12; z++) {
            cells.add(new int[]{0, 1, z});
        }
        cells.add(new int[]{-1, 1, 12});
        cells.add(new int[]{-2, 1, 12});
        for (int z = 11; z >= 0; z--) {
            cells.add(new int[]{-2, 1, z});
        }
        double[] r = drive(path(cells.toArray(new int[0][])), 600);
        assertTrue(r[1] < 1.5D, "stays near the way through the U-turn: " + r[1]);
    }

    @Test
    void aWayThatRunsBackOverItselfIsFollowedToItsEnd() {
        // Into a pocket and back out on the same blocks (the case that made it stand still and turn).
        List<int[]> cells = new ArrayList<>();
        for (int z = 0; z <= 8; z++) {
            cells.add(new int[]{0, 1, z});
        }
        for (int z = 7; z >= 0; z--) {
            cells.add(new int[]{0, 1, z});
        }
        double[] r = drive(path(cells.toArray(new int[0][])), 600);
        assertTrue(r[0] < 200, "walks in and back out without getting stuck: " + r[0] + " ticks");
    }

    @Test
    void jumpsUpAStepBeforeReachingIt() {
        List<int[]> cells = new ArrayList<>();
        for (int z = 0; z <= 12; z++) {
            cells.add(new int[]{0, z < 6 ? 1 : 2, z});
        }
        double[] r = drive(path(cells.toArray(new int[0][])), 400);
        assertEquals(1.0D, r[2], "one jump for one step");
    }
}
