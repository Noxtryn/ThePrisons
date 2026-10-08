package io.theprisons.modules.qol.bandit.nav;

import io.theprisons.modules.qol.bandit.combat.Geo;
import io.theprisons.testing.NavSim;
import io.theprisons.testing.GridTerrain;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Walls, body width and the executed movement. x east, z south; the player runs north (-z). The simulator uses the very same NavDrive key logic as
 * the live module, a 0.6 wide body that slides along walls, and counts every tick that touches a wall.
 */
class NavCollisionTest {
    private static GridTerrain wallRow(GridTerrain t, int z, int fromX, int toX) {
        for (int x = fromX; x <= toX; x++) {
            t = t.with(x, z, '#');
        }
        return t;
    }

    private static NavSim run(GridTerrain t, double x, double z) {
        return new NavSim(new NavConfig(), t, x, z, 0.0D, -5.6D);
    }

    private static NavInputs at(GridTerrain t, double x, double z, double yaw, double halfWidth) {
        return new NavInputs(1_000_000L, x, 64.0D, z, 0, -5.6, yaw, true, List.of(), t, SpearAreaState.UNKNOWN, halfWidth);
    }

    private static NavCandidate candidate(NavDecision d, double desiredDegrees) {
        return d.candidates().stream().filter(c -> c.index() >= 0 && Math.abs(Math.floorMod(Math.round(c.headingDegrees()), 360) - desiredDegrees) < 1.0D)
                .findFirst().orElseThrow();
    }

    // A, H, J: a wall straight ahead at sprint speed

    @Test
    void aStraightWallIsLeftBeforeTheBodyTouchesItAndTheStuckDetectorNeverFires() {
        GridTerrain t = wallRow(GridTerrain.open(100), 30, 0, 99);
        NavSim s = run(t, 50.5D, 50.5D);
        s.run(300);
        assertEquals(0, s.contactTicks, "the body never touched the wall");
        assertTrue(s.decisions.stream().noneMatch(NavDecision::stuck), "stuck recovery is not the navigation");
        assertTrue(s.decisions.stream().noneMatch(NavDecision::jump), "a full wall is not a step");
        // it bent away before the wall: the first clear turn happened with the wall still several blocks ahead
        double zAtTurn = Double.NaN;
        NavSim probe = run(t, 50.5D, 50.5D);
        for (int i = 0; i < 300; i++) {
            NavDecision d = probe.tick();
            if (Math.abs(d.dirX()) > 0.5D) {
                zAtTurn = probe.z;
                break;
            }
        }
        assertFalse(Double.isNaN(zAtTurn), "it turned");
        assertTrue(zAtTurn - 31.0D >= 3.0D, "turned with at least 3 blocks left to the wall, z=" + zAtTurn);
    }

    @Test
    void wallPressureChangesTheRouteWhileTheWallIsStillFarAndBeforeAnyStuckTime() {
        GridTerrain t = wallRow(GridTerrain.open(100), 30, 0, 79);   // a wall with the end at x=80
        NavSim s = run(t, 50.5D, 60.5D);
        boolean pressureSeen = false;
        for (int i = 0; i < 250; i++) {
            NavDecision d = s.tick();
            pressureSeen |= d.chosen().pressure() > 0.0D || d.chosen().free() < 8.0D;
            assertFalse(d.stuck(), "no stuck decision at tick " + i);
        }
        assertTrue(pressureSeen);
        assertEquals(0, s.contactTicks);
    }

    // B, D, E: body width

    @Test
    void aDiagonalLineThatPassesACornerWithTheCentreButNotWithTheBodyIsRejected() {
        GridTerrain t = GridTerrain.open(100).with(52, 47, '#');
        double yawNorth = Geo.yawOf(0.0D, -1.0D);
        double yawNe = Geo.yawOf(1.0D, -1.0D);   // the view already looks north-east: plain W walks the diagonal
        NavDecision body = new LocalNavigator(new NavConfig()).plan(at(t, 50.7D, 50.5D, yawNe, 0.3D));
        NavDecision point = new LocalNavigator(new NavConfig()).plan(at(t, 50.7D, 50.5D, yawNe, 0.0D));
        NavCandidate ne = candidate(body, 315.0D);       // north-east in world terms: W + D keys
        NavCandidate nePoint = candidate(point, 315.0D);
        assertTrue(ne.execX() > 0.6D && ne.execZ() < -0.6D, "executed north-east: " + ne.execX() + "," + ne.execZ());
        assertTrue(nePoint.free() >= 9.9D, "a zero-width point sneaks past the corner: " + nePoint.free());
        assertTrue(ne.centerFree() >= 9.9D, "the centre line is clear: " + ne.centerFree());
        assertTrue(ne.free() < 6.0D, "the body hits the corner: " + ne.free() + " left " + ne.leftFree() + " right " + ne.rightFree());
        assertFalse(ne.safe());
    }

    private static GridTerrain doorway() {
        GridTerrain t = wallRow(GridTerrain.open(100), 45, 0, 49);
        t = wallRow(t, 45, 51, 99);
        t = wallRow(t, 44, 0, 49);
        return wallRow(t, 44, 51, 99);   // a 1 block wide, 2 block deep doorway in the cell x=50
    }

    @Test
    void aOneBlockDoorwayIsRejectedWhenTheBodyDoesNotFitAndAcceptedWhenItDoes() {
        double yawNorth = Geo.yawOf(0.0D, -1.0D);
        NavCandidate offCentre = candidate(new LocalNavigator(new NavConfig()).plan(at(doorway(), 50.9D, 50.5D, yawNorth, 0.3D)), 270.0D);
        assertTrue(offCentre.centerFree() >= 9.9D, "the centre line fits through: " + offCentre.centerFree());
        assertTrue(offCentre.free() < 7.0D, "the body does not: " + offCentre.free());
        NavCandidate centred = candidate(new LocalNavigator(new NavConfig()).plan(at(doorway(), 50.5D, 50.5D, yawNorth, 0.3D)), 270.0D);
        assertTrue(centred.free() >= 9.9D, "centred, the body fits: " + centred.free());
        assertTrue(centred.safe());
    }

    @Test
    void aPlayerCentredInFrontOfTheDoorwayRunsThroughItWithoutTouchingAnything() {
        NavSim s = run(doorway(), 50.5D, 58.5D);
        s.run(60);
        assertEquals(0, s.contactTicks);
        assertTrue(s.z < 43.0D, "through the doorway, z=" + s.z);
    }

    // C: camera misalignment

    @Test
    void theRouteIsWhatTheKeysWalkWhileTheViewCatchesUpNotTheIdealLine() {
        // The view looks 20 degrees east of north, the desired heading is north. Plain W walks the VIEW direction first (20 degrees off), then the
        // view catches up and the route turns into the desired line.
        double yaw = Geo.yawOf(Math.sin(Math.toRadians(20.0D)), -Math.cos(Math.toRadians(20.0D)));
        ExecutedPath path = ExecutionModel.simulate(50.5D, 50.5D, yaw, 0.0D, -1.0D, 38);
        ExecutedPath.Leg first = path.first();
        ExecutedPath.Leg last = path.legs().get(path.legs().size() - 1);
        assertTrue(Geo.angleBetween(first.dirX(), first.dirZ(), 0.0D, -1.0D) >= 15.0D, "starts 20 degrees off: " + first);
        assertTrue(Geo.angleBetween(last.dirX(), last.dirZ(), 0.0D, -1.0D) <= 8.0D, "ends on the desired line: " + last);
        assertTrue(path.legs().size() >= 2);
        assertEquals(38 * 5.6D * 0.05D, path.length(), 1e-6, "all sprint");
        // a view far off still ends up on the line, only later
        ExecutedPath far = ExecutionModel.simulate(50.5D, 50.5D, Geo.yawOf(1.0D, 0.0D), 0.0D, -1.0D, 38);
        ExecutedPath.Leg farLast = far.legs().get(far.legs().size() - 1);
        assertTrue(Geo.angleBetween(farLast.dirX(), farLast.dirZ(), 0.0D, -1.0D) <= 8.0D, "ends on the desired line: " + farLast);
    }

    @Test
    void aMisalignedViewIsVisibleInTheCandidateAndAWallInTheFirstMetreIsSeen() {
        // View 22 degrees east of north, desired north: plain W walks the view direction, so the candidate's first leg is 22 degrees off.
        double lean = Geo.yawOf(Math.sin(Math.toRadians(22.0D)), -Math.cos(Math.toRadians(22.0D)));
        NavCandidate open = candidate(new LocalNavigator(new NavConfig()).plan(at(GridTerrain.open(100), 50.5D, 50.5D, lean, 0.3D)), 270.0D);
        assertEquals(22.0D, open.errorDegrees(), 1.0D);
        assertTrue(open.legs() >= 2, "the route bends while the view catches up");
        assertTrue(open.free() >= 9.9D);
        // a wall touching the body's east edge right at the start: the view-leaning first leg presses into it, the aligned one runs along it
        GridTerrain t = GridTerrain.open(100).with(51, 49, '#').with(51, 50, '#').with(51, 48, '#');
        NavCandidate pressed = candidate(new LocalNavigator(new NavConfig()).plan(at(t, 50.68D, 50.5D, lean, 0.3D)), 270.0D);
        NavCandidate along = candidate(new LocalNavigator(new NavConfig()).plan(at(t, 50.68D, 50.5D, Geo.yawOf(0.0D, -1.0D), 0.3D)), 270.0D);
        assertTrue(pressed.free() < along.free(), "judged on the executed route: " + pressed.free() + " vs " + along.free());
    }

    // F, G: a wall beside the player

    @Test
    void aWallOnTheLeftIsFollowedWithoutTouchingItOrFlipping() {
        GridTerrain t = GridTerrain.open(100);
        for (int x = 0; x <= 48; x++) {
            for (int z = 0; z < 100; z++) {
                t = t.with(x, z, '#');
            }
        }
        NavSim s = run(t, 49.7D, 80.5D);
        s.run(250);
        assertEquals(0, s.contactTicks, "never touched");
        assertTrue(s.z < 40.0D, "kept running, z=" + s.z);
        assertTrue(smooth(s), "no sharp turns while following the wall");
    }

    @Test
    void aWallOnTheRightIsFollowedWithoutTouchingItOrFlipping() {
        GridTerrain t = GridTerrain.open(100);
        for (int x = 52; x < 100; x++) {
            for (int z = 0; z < 100; z++) {
                t = t.with(x, z, '#');
            }
        }
        NavSim s = run(t, 51.3D, 80.5D);
        s.run(250);
        assertEquals(0, s.contactTicks, "never touched");
        assertTrue(s.z < 40.0D, "kept running, z=" + s.z);
        assertTrue(smooth(s), "no sharp turns while following the wall");
    }

    private static boolean smooth(NavSim s) {
        int sharp = 0;
        for (int i = 1; i < s.decisions.size(); i++) {
            double a = Math.abs(((s.decisions.get(i).headingDegrees() - s.decisions.get(i - 1).headingDegrees()) % 360.0D + 540.0D) % 360.0D - 180.0D);
            if (a > 50.0D) {
                sharp++;
            }
        }
        return sharp <= 2;
    }

    // I, J: a step and a full wall

    @Test
    void aOneBlockStepIsJumpedAndTheRunContinues() {
        GridTerrain t = GridTerrain.open(100);
        for (int x = 0; x < 100; x++) {
            for (int z = 0; z <= 45; z++) {
                t = t.with(x, z, '^');
            }
        }
        NavSim s = run(t, 50.5D, 55.5D);
        s.run(120);
        assertTrue(s.decisions.stream().anyMatch(NavDecision::jump), "it jumped");
        assertTrue(s.z < 40.0D, "and carried on, z=" + s.z);
        assertEquals(0, s.contactTicks);
    }

    @Test
    void aStepIntoAWallIsNotJumpedAndAFullBlockWallNeverIsAJump() {
        // a step directly followed by a wall: 1 block of floor, then the wall
        GridTerrain t = GridTerrain.open(100);
        for (int x = 0; x < 100; x++) {
            t = t.with(x, 45, '^').with(x, 44, '#');
        }
        NavSim s = run(t, 50.5D, 52.5D);
        NavDecision d = s.planner.plan(s.inputs());
        NavCandidate ahead = d.candidates().stream().filter(c -> c.index() == -1).findFirst().orElseThrow();
        assertFalse(ahead.blocked().isEmpty(), "the step leads into a wall: not a way (" + ahead.stop() + " free " + ahead.free() + ")");
        s.run(200);
        assertTrue(s.decisions.stream().noneMatch(NavDecision::jump), "no jump into a dead end");
        assertEquals(0, s.contactTicks);
    }

    // K: diagonal wall

    @Test
    void aDiagonalWallIsFollowedWithoutClippingIt() {
        GridTerrain t = GridTerrain.open(160);
        for (int x = 0; x < 160; x++) {
            int z = 120 - x;
            if (z >= 0 && z < 160) {
                t = t.with(x, z, '#').with(x, z + 1, '#');
            }
        }
        NavSim s = run(t, 50.5D, 100.5D);   // south-east of the wall (x + z = 150.9), the wall runs north-west to south-east
        s.run(300);
        assertTrue(s.contactTicks <= 2, "contacts with the diagonal wall: " + s.contactTicks);
        assertTrue(s.decisions.stream().noneMatch(NavDecision::stuck));
        assertTrue(Math.hypot(s.x - 50.5D, s.z - 100.5D) > 20.0D, "it kept moving");
    }
}
