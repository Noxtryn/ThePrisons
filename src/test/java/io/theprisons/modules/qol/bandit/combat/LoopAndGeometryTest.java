package io.theprisons.modules.qol.bandit.combat;

import io.theprisons.core.control.InputController.Keys;
import io.theprisons.testing.GridTerrain;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LoopAndGeometryTest {
    // ── loop detector ────────────────────────────────────────────────────────

    @Test
    void orbitWithoutProgressIsALoop() {
        LoopDetector d = new LoopDetector();
        LoopDetector.Kind found = LoopDetector.Kind.NONE;
        for (int i = 0; i < 200 && found == LoopDetector.Kind.NONE; i++) {
            found = d.sample(i * 50L, CombatState.ORBIT, 10.0, 10.0, 0.0F, 0.0D);
        }
        assertEquals(LoopDetector.Kind.ORBIT_NO_PROGRESS, found);
    }

    @Test
    void aHealthyOrbitIsNoLoop() {
        LoopDetector d = new LoopDetector();
        for (int i = 0; i < 600; i++) {
            double angle = i * 1.0D; // 20 deg per second around the target
            assertEquals(LoopDetector.Kind.NONE, d.sample(i * 50L, CombatState.ORBIT, 10 + Math.cos(Math.toRadians(angle)) * 17,
                    10 + Math.sin(Math.toRadians(angle)) * 17, (float) angle, angle), "tick " + i);
        }
    }

    @Test
    void flippingTheOrbitSideOverAndOverIsALoop() {
        LoopDetector d = new LoopDetector();
        for (int i = 0; i < 4; i++) {
            d.onOrbitFlip(1_000L + i * 800L);
        }
        assertEquals(LoopDetector.Kind.DIRECTION_FLIPPING, d.sample(4_500L, CombatState.ORBIT, 0, 0, 0, 0));
        d.reset();
        assertEquals(LoopDetector.Kind.NONE, d.sample(4_600L, CombatState.ORBIT, 0, 0, 0, 0));
    }

    @Test
    void flipsFarApartAreNoLoop() {
        LoopDetector d = new LoopDetector();
        for (int i = 0; i < 4; i++) {
            d.onOrbitFlip(i * 10_000L);
        }
        assertEquals(LoopDetector.Kind.NONE, d.sample(35_000L, CombatState.ORBIT, 0, 0, 0, 0));
    }

    @Test
    void twoStatesHandingTheMacroBackAndForthIsALoop() {
        LoopDetector d = new LoopDetector();
        long t = 0;
        CombatState[] pair = {CombatState.APPROACH, CombatState.REPOSITION};
        for (int i = 0; i < LoopDetector.PINGPONG_MOVES; i++) {
            d.onState(t += 400L, pair[i % 2], pair[(i + 1) % 2]);
        }
        assertEquals(LoopDetector.Kind.STATE_PINGPONG, d.sample(t + 10, CombatState.APPROACH, 0, 0, 0, 0));
    }

    @Test
    void waitingAndAttackTogglesAreNotPingPong() {
        LoopDetector d = new LoopDetector();
        long t = 0;
        for (int i = 0; i < 8; i++) {
            d.onState(t += 300L, i % 2 == 0 ? CombatState.SCAN : CombatState.TARGET_SELECT, i % 2 == 0 ? CombatState.TARGET_SELECT : CombatState.SCAN);
        }
        assertEquals(LoopDetector.Kind.NONE, d.sample(t, CombatState.SCAN, 0, 0, 0, 0));
        d.reset();
        t = 0;
        for (int i = 0; i < 8; i++) {
            d.onState(t += 300L, i % 2 == 0 ? CombatState.ORBIT : CombatState.ENGAGE, i % 2 == 0 ? CombatState.ENGAGE : CombatState.ORBIT);
        }
        assertEquals(LoopDetector.Kind.NONE, d.sample(t, CombatState.ORBIT, 0, 0, 0, 0));
    }

    @Test
    void choosingTheTargetAgainAndAgainIsALoop() {
        LoopDetector d = new LoopDetector();
        for (int i = 0; i < LoopDetector.REACQUIRES; i++) {
            d.onTargetChosen(i * 2_000L);
        }
        assertEquals(LoopDetector.Kind.TARGET_REACQUIRE, d.sample(6_100L, CombatState.SCAN, 0, 0, 0, 0));
    }

    @Test
    void theSamePathFailingRepeatedlyIsALoop() {
        LoopDetector d = new LoopDetector();
        for (int i = 0; i < LoopDetector.PATH_FAILURES; i++) {
            d.onPathFailure(i * 3_000L);
        }
        assertEquals(LoopDetector.Kind.PATH_FAILING, d.sample(9_000L, CombatState.APPROACH, 0, 0, 0, 0));
    }

    @Test
    void turningALotWithoutMovingIsASpin() {
        LoopDetector d = new LoopDetector();
        LoopDetector.Kind found = LoopDetector.Kind.NONE;
        for (int i = 0; i < 100 && found == LoopDetector.Kind.NONE; i++) {
            found = d.sample(i * 50L, CombatState.APPROACH, 5.0, 5.0, i * 25.0F, 0);
        }
        assertEquals(LoopDetector.Kind.SPIN, found);
    }

    @Test
    void turningWhileHoldingStillForAnAttackIsNoSpin() {
        LoopDetector d = new LoopDetector();
        for (int i = 0; i < 100; i++) {
            assertEquals(LoopDetector.Kind.NONE, d.sample(i * 50L, CombatState.ENGAGE, 5.0, 5.0, i * 25.0F, 0));
        }
    }

    // ── keys from a direction ───────────────────────────────────────────────

    @Test
    void keysFollowTheViewNotTheWorld() {
        // yaw 0 faces +z. Moving +z = forward; -x is the player's right.
        Keys forward = Geo.keysFor(0, 1, 0, false, false);
        assertTrue(forward.forward() && !forward.back() && !forward.left() && !forward.right());
        Keys right = Geo.keysFor(-1, 0, 0, false, false);
        assertTrue(right.right() && !right.left() && !right.forward() && !right.back());
        Keys left = Geo.keysFor(1, 0, 0, false, false);
        assertTrue(left.left() && !left.right());
        Keys back = Geo.keysFor(0, -1, 0, false, false);
        assertTrue(back.back() && !back.forward());
        // the same world direction after the view turned 90 degrees (yaw 90 faces -x): +z is now the player's left
        Keys turned = Geo.keysFor(0, 1, 90, false, false);
        assertTrue(turned.left() && !turned.forward(), turned.toString());
        // diagonals press two keys
        Keys diagonal = Geo.keysFor(-1, 1, 0, false, false);
        assertTrue(diagonal.forward() && diagonal.right() && !diagonal.sprint());
        // sprint only goes with plain forward
        assertTrue(Geo.keysFor(0, 1, 0, true, false).sprint());
        assertFalse(Geo.keysFor(-1, 0, 0, true, false).sprint());
        assertEquals(new Keys(false, false, false, false, true, false, false), Geo.keysFor(0, 0, 0, true, true));
    }

    @Test
    void anOrbitStepBecomesAStrafeWhileFacingTheTarget() {
        // Player south of the target facing north (yaw 180), moving LEFT of the facing direction = west... the keys must be 'left'.
        double[] r = {0, 1};
        double[] left = {-r[1], r[0]};
        Keys keys = Geo.keysFor(left[0], left[1], Geo.yawOf(0, -1), false, false);
        assertTrue(keys.left() && !keys.forward() && !keys.right(), keys.toString());
    }

    // ── escape ───────────────────────────────────────────────────────────────

    @Test
    void escapeGoesAwayFromTheThreatOnOpenGround() {
        Foe threat = new Foe("b", "ORE_BANDIT", 60, 64, 50, 0, 0, 20, 20, Foe.Sight.YES);
        EscapePlanner.Escape e = EscapePlanner.best(GridTerrain.open(100), 50, 64, 50, List.of(threat), EscapePlanner.LOOK);
        assertNotNull(e);
        assertTrue(e.dx() < -0.5D, "away from +x: " + e);
    }

    @Test
    void escapeAvoidsWallsAndDrops() {
        // Wall to the west, drop to the north: the free way is east/south although the threat is east.
        GridTerrain t = GridTerrain.open(100);
        for (int z = 0; z < 100; z++) {
            for (int x = 0; x < 48; x++) {
                t = t.with(x, z, '#');
            }
        }
        for (int x = 48; x < 100; x++) {
            for (int z = 0; z < 48; z++) {
                t = t.with(x, z, ' ');
            }
        }
        Foe threat = new Foe("b", "ORE_BANDIT", 70, 64, 50, 0, 0, 20, 20, Foe.Sight.YES);
        EscapePlanner.Escape e = EscapePlanner.best(t, 50.5, 64, 50.5, List.of(threat), EscapePlanner.LOOK);
        assertNotNull(e);
        assertTrue(e.dz() > 0.0D || e.dx() > 0.0D, "not into the wall or the pit: " + e);
        assertTrue(e.free() >= EscapePlanner.MIN_FREE);
    }

    @Test
    void corneredMeansNoEscape() {
        GridTerrain cell = GridTerrain.open(20);
        for (int x = 8; x <= 11; x++) {
            for (int z = 8; z <= 11; z++) {
                if (x == 8 || x == 11 || z == 8 || z == 11) {
                    cell = cell.with(x, z, '#');
                }
            }
        }
        Foe threat = new Foe("b", "ORE_BANDIT", 10, 64, 5, 0, 0, 20, 20, Foe.Sight.YES);
        assertNull(EscapePlanner.best(cell, 10.0, 64, 10.0, List.of(threat), EscapePlanner.LOOK));
        assertTrue(EscapePlanner.blockedDirections(EscapePlanner.scan(cell, 10.0, 64, 10.0, 4.0D), 2.0D) >= 12);
    }

    @Test
    void terrainReportsWhyAProbeStopped() {
        GridTerrain t = new GridTerrain("......", "..#...", "..    ", "..~...", "?.....");
        assertEquals(Terrain.Stop.WALL, t.cast(0.5, 64, 1.5, 1, 0, 5).stop());
        assertEquals(Terrain.Stop.DROP, t.cast(0.5, 64, 2.5, 1, 0, 5).stop());
        assertEquals(Terrain.Stop.HAZARD, t.cast(0.5, 64, 3.5, 1, 0, 5).stop());
        assertEquals(Terrain.Stop.UNKNOWN, t.cast(1.5, 64, 4.5, -1, 0, 5).stop());
        assertEquals(Terrain.Stop.CLEAR, t.cast(0.5, 64, 0.5, 1, 0, 5).stop());
        assertEquals(1.5D, t.cast(0.5, 64, 1.5, 1, 0, 5).free(), 0.11D);
    }

    // ── threat context ───────────────────────────────────────────────────────

    @Test
    void threatContextSeparatesThePrimaryFromTheOthers() {
        Foe primary = new Foe("a", "ORE_BANDIT", 50, 64, 40, 0, 0, 20, 20, Foe.Sight.YES);
        Foe near = new Foe("b", "ORE_BANDIT", 55, 64, 58, 0, 0, 20, 20, Foe.Sight.UNKNOWN);
        Foe far = new Foe("c", "ORE_BANDIT", 90, 64, 90, 0, 0, 20, 20, Foe.Sight.UNKNOWN);
        ThreatContext t = ThreatContext.of(List.of(primary, near, far), primary, 50, 50, 15.0D);
        assertEquals(2, t.count(), "primary (10 away) and the near one (9.4 away)");
        assertEquals(List.of("b"), t.nearby().stream().map(Foe::id).toList());
        assertEquals(Math.hypot(5, 8), t.nearestDistance(), 1e-9);
        assertTrue(ThreatContext.of(List.of(far), null, 50, 50, 15.0D).nearestDistance() != t.nearestDistance());
        assertFalse(ThreatContext.of(List.of(), null, 0, 0, 10.0D).any());
    }
}
