package io.theprisons.modules.qol.bandit.dodge;

import io.theprisons.core.nav.Cell;
import io.theprisons.core.nav.VoxelView;
import io.theprisons.modules.qol.bandit.WorldTerrain;
import io.theprisons.modules.qol.bandit.combat.Geo;
import io.theprisons.modules.qol.bandit.combat.Terrain;
import io.theprisons.testing.DodgeSim;
import io.theprisons.testing.GridTerrain;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** From "step seen" to "player is on top of it and runs on": detection, validation, commit, key hold, lift-off, locked course, landing. */
class DodgeJumpTest {
    // ── the real WorldTerrain on a small synthetic world ─────────────────────

    private static class World implements VoxelView {
        final Map<String, Integer> cells = new HashMap<>();

        void set(int x, int y, int z, int cell) {
            cells.put(x + "," + y + "," + z, cell);
        }

        /** A band of cells over x in [-3, 3] and z from zFrom down to -30 (the step / wall runs away to the north). */
        void band(int zFrom, int y, int cell) {
            for (int x = -3; x <= 3; x++) {
                for (int z = zFrom; z >= -30; z--) {
                    set(x, y, z, cell);
                }
            }
        }

        @Override
        public int cell(int x, int y, int z) {
            Integer o = cells.get(x + "," + y + "," + z);
            return o != null ? o : y <= 63 ? Cell.SOLID : Cell.AIR;
        }
    }

    private static DodgeInputs worldInputs(World w, double z, long now, boolean onGround) {
        return new DodgeInputs(now, 0.5D, 64.0D, z, 0, -5.6, Geo.yawOf(0.0D, -1.0D), onGround, List.of(), new WorldTerrain(w), SpearAreaState.UNKNOWN);
    }

    private static DodgeCandidate north(DodgeDecision d) {
        return d.candidates().stream().filter(c -> c.index() >= 0 && Math.abs(c.headingDegrees() + 90.0D) < 1.0D || c.index() >= 0 && Math.abs(c.headingDegrees() - 270.0D) < 1.0D)
                .findFirst().orElseThrow();
    }

    @Test
    void realWorldTerrainReportsWhereAOneBlockStepStarts() {
        World w = new World();
        w.band(-4, 64, Cell.SOLID);              // a one block step from z = -4 on: its face is at z = -3
        Terrain.Ray ray = new WorldTerrain(w).cast(0.5D, 64.0D, 0.5D, 0.0D, -1.0D, 8.0D);
        assertEquals(Terrain.Stop.CLEAR, ray.stop());
        assertTrue(ray.jumpAt() >= 3.2D && ray.jumpAt() <= 3.6D, "the step face is 3.5 blocks ahead: " + ray.jumpAt());
        assertEquals(1.0D, ray.heightChange(), 1e-9);
    }

    // C: a step with headroom is planned, committed and the key goes down

    @Test
    void aStepWithHeadroomIsValidatedAndJumpedInsideTheWindow() {
        World w = new World();
        w.band(-4, 64, Cell.SOLID);
        BanditDodgePlanner planner = new BanditDodgePlanner(new DodgeConfig());
        DodgeDecision far = planner.plan(worldInputs(w, 0.5D, 1_000_000L, true));
        assertFalse(far.jump(), "3.5 blocks away is too early (the window is 2.0)");
        assertTrue(north(far).jumpAt() > 0.0D, "but the step is known: " + north(far).jumpAt());
        DodgeDecision near = planner.plan(worldInputs(w, -1.3D, 1_000_050L, true));
        assertTrue(near.jump(), "1.8 blocks from the face: jump. " + near.reason());
        assertEquals(JumpPhase.HOLD, near.jumpPhase());
        assertEquals(1, near.jumpTicksHeld());
        assertTrue(near.dirZ() < -0.7D, "straight on: " + near.dirX() + "," + near.dirZ());
    }

    // D: no headroom above the landing

    @Test
    void aStepWithALowCeilingAboveTheLandingIsNotJumped() {
        World w = new World();
        w.band(-4, 64, Cell.SOLID);
        w.band(-4, 66, Cell.SOLID);            // a ceiling 2 blocks above the floor of the step: no room for the player on top
        BanditDodgePlanner planner = new BanditDodgePlanner(new DodgeConfig());
        DodgeDecision d = planner.plan(worldInputs(w, -1.3D, 1_000_000L, true));
        DodgeCandidate n = north(d);
        assertTrue(n.jumpAt() < 0.0D, "no jump into a space the body does not fit: " + n.jumpAt());
        assertTrue(n.free() < 3.0D, "the way ends at the step: " + n.free() + " " + n.stop());
        assertFalse(d.jump());
    }

    // E: a full wall

    @Test
    void aTwoBlockWallIsNeverJumped() {
        World w = new World();
        w.band(-4, 64, Cell.SOLID);
        w.band(-4, 65, Cell.SOLID);
        BanditDodgePlanner planner = new BanditDodgePlanner(new DodgeConfig());
        for (double z : new double[]{0.5D, -0.3D, -1.3D, -2.0D}) {
            DodgeDecision d = planner.plan(worldInputs(w, z, 1_000_000L + (long) (z * 100), true));
            assertFalse(d.jump(), "a wall is not a step (z=" + z + "): " + d.reason());
            assertTrue(north(d).jumpAt() < 0.0D);
            assertTrue(north(d).stop().equals("WALL") || north(d).stop().equals("STEP"), north(d).stop());
        }
    }

    // F: the key is held for several ticks, released after lift-off, retried when it never lifted

    private static DodgeInputs gridInputs(GridTerrain t, double z, long now, boolean onGround) {
        return new DodgeInputs(now, 50.5D, 64.0D, z, 0, -5.6, Geo.yawOf(0.0D, -1.0D), onGround, List.of(), t, SpearAreaState.UNKNOWN);
    }

    private static GridTerrain stepFrom(int z) {
        GridTerrain t = GridTerrain.open(100);
        for (int x = 0; x < 100; x++) {
            for (int zz = 0; zz <= z; zz++) {
                t = t.with(x, zz, '^');
            }
        }
        return t;
    }

    @Test
    void theJumpKeyIsHeldForSeveralTicksWhileTheGroundIsUnderTheFeetAndRetriedWhenItNeverLifts() {
        GridTerrain t = stepFrom(45);
        BanditDodgePlanner planner = new BanditDodgePlanner(new DodgeConfig());
        boolean[] jump = new boolean[8];
        for (int i = 0; i < 8; i++) {
            jump[i] = planner.plan(gridInputs(t, 47.6D, 1_000_000L + i * 50L, true)).jump();     // the player stands 1.6 from the face and never lifts
        }
        assertTrue(jump[0] && jump[1] && jump[2] && jump[3], "held for 4 ticks");
        assertFalse(jump[4], "released (200 ms)");
        assertTrue(jump[5], "tried again after 250 ms: nothing lifted");
    }

    @Test
    void theKeyIsReleasedOneTickAfterLiftOffAndTheCourseStaysLocked() {
        GridTerrain t = stepFrom(45);
        BanditDodgePlanner planner = new BanditDodgePlanner(new DodgeConfig());
        DodgeDecision a = planner.plan(gridInputs(t, 47.6D, 1_000_000L, true));
        DodgeDecision b = planner.plan(gridInputs(t, 47.3D, 1_000_050L, false));       // lifted off
        DodgeDecision c = planner.plan(gridInputs(t, 47.0D, 1_000_100L, false));
        assertTrue(a.jump());
        assertFalse(b.jump(), "no key while in the air");
        assertEquals(JumpPhase.AIR, b.jumpPhase());
        assertTrue(b.reason().contains("locked"), b.reason());
        assertEquals(a.headingDegrees(), c.headingDegrees(), 1e-6, "the course stays the same until the landing");
    }

    // A: the full run on the step

    @Test
    void aOneBlockStepRightAheadIsJumpedOnceAndTheRunCarriesOn() {
        DodgeSim s = new DodgeSim(new DodgeConfig(), stepFrom(45), 50.5D, 58.5D, 0.0D, -5.6D);
        s.run(160);
        assertEquals(1, s.liftOffs, "one jump");
        assertEquals(0, s.contactTicks, "never ran into the block");
        assertTrue(s.jumpKeyTicks >= 1 && s.jumpKeyTicks <= 4, "key ticks " + s.jumpKeyTicks);
        assertTrue(s.z < 42.0D, "on the step and running on: z=" + s.z);
        assertTrue(s.onGround && s.feetY == 1.0D, "landed on top: " + s.feetY);
        assertTrue(s.decisions.stream().noneMatch(DodgeDecision::stuck));
    }

    @Test
    void aLostKeyPressDoesNotMeanRunningIntoTheBlock() {
        DodgeSim s = new DodgeSim(new DodgeConfig(), stepFrom(45), 50.5D, 58.5D, 0.0D, -5.6D);
        s.swallowJumpTicks = 2;      // the first two key ticks never reach the game
        s.run(160);
        assertEquals(1, s.liftOffs);
        assertEquals(0, s.contactTicks, "the held key still got it over");
        assertTrue(s.z < 42.0D, "z=" + s.z);
    }

    // B: diagonal approach

    @Test
    void aStepApproachedDiagonallyIsJumpedToo() {
        DodgeSim s = new DodgeSim(new DodgeConfig(), stepFrom(45), 58.5D, 58.5D, 4.0D, -4.0D);
        s.run(120);
        assertTrue(s.liftOffs >= 1, "it jumped");
        assertTrue(s.contactTicks <= 2, "at most a graze with the corner: " + s.contactTicks);
        assertTrue(s.z < 44.0D, "got up and over: z=" + s.z);
    }

    // G: the jump belongs to the executed route

    @Test
    void aJumpIsOnlyPlannedWhereTheBodyRouteReallyMeetsTheStep() {
        GridTerrain t = GridTerrain.open(100).with(50, 46, '^').with(50, 45, '^').with(50, 44, '^');     // one narrow step column at x = 50
        double yaw = Geo.yawOf(0.0D, -1.0D);
        DodgeDecision onIt = new BanditDodgePlanner(new DodgeConfig()).plan(new DodgeInputs(1_000_000L, 50.5D, 64.0D, 52.5D, 0, -5.6, yaw, true, List.of(), t, SpearAreaState.UNKNOWN));
        DodgeDecision beside = new BanditDodgePlanner(new DodgeConfig()).plan(new DodgeInputs(1_000_000L, 53.2D, 64.0D, 52.5D, 0, -5.6, yaw, true, List.of(), t, SpearAreaState.UNKNOWN));
        assertTrue(north(onIt).jumpAt() > 0.0D, "the route meets the step: " + north(onIt).jumpAt());
        assertTrue(north(beside).jumpAt() < 0.0D, "three blocks beside it there is nothing to jump: " + north(beside).jumpAt());
        // every candidate's jumpAt is the one of its own executed route, not of the ideal ray
        for (DodgeCandidate c : onIt.candidates()) {
            ExecutedPath path = ExecutionModel.simulate(50.5D, 52.5D, yaw, c.dirX(), c.dirZ(), new DodgeConfig().pathTicks);
            BodyClearance sweep = BodyClearance.sweep(t, 64.0D, path, 0.3D, new DodgeConfig().bodyInset);
            if (c.jumpAt() >= 0.0D) {
                assertEquals(sweep.jumpAt(), c.jumpAt(), 1e-9, "candidate " + c.index());
            }
        }
    }

    // stairs after a bend: the floor level is carried from leg to leg

    @Test
    void twoStepsInARowAfterABendAreNotAWall() {
        World w = new World();
        w.band(-6, 64, Cell.SOLID);
        w.band(-9, 65, Cell.SOLID);        // two steps: floor +1 from z = -6, +2 from z = -9
        ExecutedPath path = new ExecutedPath(List.of(new ExecutedPath.Leg(0.5D, 0.5D, 0.0D, -1.0D, 10.0D, 0, true),       // up both steps ...
                new ExecutedPath.Leg(0.5D, -9.5D, 0.0D, -1.0D, 6.0D, 36, true)), 16.0D, 57, 5.6D);   // ... then on, standing two blocks higher
        BodyClearance c = BodyClearance.sweep(new WorldTerrain(w), 64.0D, path, 0.3D, 0.02D);
        assertEquals(Terrain.Stop.CLEAR, c.stop(), "walkable stairs: " + c);
        assertTrue(c.free() >= 15.9D);
    }
}
