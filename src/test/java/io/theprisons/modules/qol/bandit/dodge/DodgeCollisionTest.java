package io.theprisons.modules.qol.bandit.dodge;

import io.theprisons.modules.qol.bandit.combat.Geo;
import io.theprisons.testing.DodgeSim;
import io.theprisons.testing.GridTerrain;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Walls, body width and the executed movement. x east, z south; the player runs north (-z). The simulator uses the very same DodgeDrive key logic as
 * the live module, a 0.6 wide body that slides along walls, and counts every tick that touches a wall.
 */
class DodgeCollisionTest {
    private static GridTerrain wallRow(GridTerrain t, int z, int fromX, int toX) {
        for (int x = fromX; x <= toX; x++) {
            t = t.with(x, z, '#');
        }
        return t;
    }

    private static DodgeSim run(GridTerrain t, double x, double z) {
        return new DodgeSim(new DodgeConfig(), t, x, z, 0.0D, -5.6D);
    }

    private static DodgeInputs at(GridTerrain t, double x, double z, double yaw, double halfWidth) {
        return new DodgeInputs(1_000_000L, x, 64.0D, z, 0, -5.6, yaw, true, List.of(), t, SpearAreaState.UNKNOWN, halfWidth);
    }

    private static DodgeCandidate candidate(DodgeDecision d, double desiredDegrees) {
        return d.candidates().stream().filter(c -> c.index() >= 0 && Math.abs(Math.floorMod(Math.round(c.headingDegrees()), 360) - desiredDegrees) < 1.0D)
                .findFirst().orElseThrow();
    }

    // A, H, J: a wall straight ahead at sprint speed

    @Test
    void aStraightWallIsLeftBeforeTheBodyTouchesItAndTheStuckDetectorNeverFires() {
        GridTerrain t = wallRow(GridTerrain.open(100), 30, 0, 99);
        DodgeSim s = run(t, 50.5D, 50.5D);
        s.run(300);
        assertEquals(0, s.contactTicks, "the body never touched the wall");
        assertTrue(s.decisions.stream().noneMatch(DodgeDecision::stuck), "stuck recovery is not the navigation");
        assertTrue(s.decisions.stream().noneMatch(DodgeDecision::jump), "a full wall is not a step");
        // it bent away before the wall: the first clear turn happened with the wall still several blocks ahead
        double zAtTurn = Double.NaN;
        DodgeSim probe = run(t, 50.5D, 50.5D);
        for (int i = 0; i < 300; i++) {
            DodgeDecision d = probe.tick();
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
        DodgeSim s = run(t, 50.5D, 60.5D);
        boolean pressureSeen = false;
        for (int i = 0; i < 250; i++) {
            DodgeDecision d = s.tick();
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
        DodgeDecision body = new BanditDodgePlanner(new DodgeConfig()).plan(at(t, 50.7D, 50.5D, yawNorth, 0.3D));
        DodgeDecision point = new BanditDodgePlanner(new DodgeConfig()).plan(at(t, 50.7D, 50.5D, yawNorth, 0.0D));
        DodgeCandidate ne = candidate(body, 315.0D);       // north-east in world terms: W + D keys
        DodgeCandidate nePoint = candidate(point, 315.0D);
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
        DodgeCandidate offCentre = candidate(new BanditDodgePlanner(new DodgeConfig()).plan(at(doorway(), 50.9D, 50.5D, yawNorth, 0.3D)), 270.0D);
        assertTrue(offCentre.centerFree() >= 9.9D, "the centre line fits through: " + offCentre.centerFree());
        assertTrue(offCentre.free() < 7.0D, "the body does not: " + offCentre.free());
        DodgeCandidate centred = candidate(new BanditDodgePlanner(new DodgeConfig()).plan(at(doorway(), 50.5D, 50.5D, yawNorth, 0.3D)), 270.0D);
        assertTrue(centred.free() >= 9.9D, "centred, the body fits: " + centred.free());
        assertTrue(centred.safe());
    }

    @Test
    void aPlayerCentredInFrontOfTheDoorwayRunsThroughItWithoutTouchingAnything() {
        DodgeSim s = run(doorway(), 50.5D, 58.5D);
        s.run(60);
        assertEquals(0, s.contactTicks);
        assertTrue(s.z < 43.0D, "through the doorway, z=" + s.z);
    }

    // C: camera misalignment

    @Test
    void theWallCheckUsesTheDirectionThatTheKeysReallyWalkNotTheDesiredOne() {
        // The camera looks north. The heading 22.5 degrees east of north is executed by the keys as W + D = north-east (45 degrees). A wall piece
        // stands on the north-east line only; the ideal 22.5 degree line would miss it.
        GridTerrain t = GridTerrain.open(100);
        for (int x = 54; x <= 56; x++) {
            t = t.with(x, 45, '#');
        }
        double yawNorth = Geo.yawOf(0.0D, -1.0D);
        DodgeDecision d = new BanditDodgePlanner(new DodgeConfig()).plan(at(t, 50.5D, 50.5D, yawNorth, 0.3D));
        DodgeCandidate nne = candidate(d, 292.5D);
        assertEquals(Math.sqrt(0.5D), nne.execX(), 1e-6, "executed north-east");
        assertEquals(-Math.sqrt(0.5D), nne.execZ(), 1e-6);
        assertEquals(22.5D, nne.errorDegrees(), 0.1D);
        assertTrue(t.cast(50.5D, 64.0D, 50.5D, nne.dirX(), nne.dirZ(), 10.0D).free() >= 9.9D, "the ideal line is clear");
        assertTrue(nne.free() < 7.0D, "but the executed line is not, and the planner sees it: " + nne.free());
        assertEquals("WALL", nne.stop());
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
        DodgeSim s = run(t, 49.7D, 80.5D);
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
        DodgeSim s = run(t, 51.3D, 80.5D);
        s.run(250);
        assertEquals(0, s.contactTicks, "never touched");
        assertTrue(s.z < 40.0D, "kept running, z=" + s.z);
        assertTrue(smooth(s), "no sharp turns while following the wall");
    }

    private static boolean smooth(DodgeSim s) {
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
        DodgeSim s = run(t, 50.5D, 55.5D);
        s.run(120);
        assertTrue(s.decisions.stream().anyMatch(DodgeDecision::jump), "it jumped");
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
        DodgeSim s = run(t, 50.5D, 52.5D);
        DodgeDecision d = s.planner.plan(s.inputs());
        DodgeCandidate ahead = d.candidates().stream().filter(c -> c.index() == -1).findFirst().orElseThrow();
        assertFalse(ahead.blocked().isEmpty(), "the step leads into a wall: not a way (" + ahead.stop() + " free " + ahead.free() + ")");
        s.run(200);
        assertTrue(s.decisions.stream().noneMatch(DodgeDecision::jump), "no jump into a dead end");
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
        DodgeSim s = run(t, 50.5D, 100.5D);   // south-east of the wall (x + z = 150.9), the wall runs north-west to south-east
        s.run(300);
        assertTrue(s.contactTicks <= 2, "contacts with the diagonal wall: " + s.contactTicks);
        assertTrue(s.decisions.stream().noneMatch(DodgeDecision::stuck));
        assertTrue(Math.hypot(s.x - 50.5D, s.z - 100.5D) > 20.0D, "it kept moving");
    }
}
