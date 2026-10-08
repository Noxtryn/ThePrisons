package io.theprisons.modules.qol.bandit.combat;

import io.theprisons.core.cosmic.value.GameValue;
import io.theprisons.testing.CombatSim;
import io.theprisons.testing.GridTerrain;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CombatBrainTest {
    private static final String B1 = "bandit_aa_000001";
    private static final String B2 = "bandit_bb_000002";

    private static CombatSim sim(GridTerrain terrain, double x, double z) {
        return new CombatSim(new CombatConfig(), terrain, x, z);
    }

    // ── distance band ────────────────────────────────────────────────────────

    @Test
    void distanceBandHasADeadBandAtEveryLimit() {
        DistanceBand band = new DistanceBand(6.0D, 34.0D, 2.0D);
        assertEquals(DistanceBand.State.COMBAT, band.update(20.0D));
        assertEquals(DistanceBand.State.TOO_FAR, band.update(34.5D));
        assertEquals(DistanceBand.State.TOO_FAR, band.update(33.0D), "still too far inside the dead band");
        assertEquals(DistanceBand.State.COMBAT, band.update(31.9D));
        assertEquals(DistanceBand.State.TOO_CLOSE, band.update(5.5D));
        assertEquals(DistanceBand.State.TOO_CLOSE, band.update(7.0D), "still too close inside the dead band");
        assertEquals(DistanceBand.State.COMBAT, band.update(8.1D));
        // standing on the line: no flutter
        int changes = 0;
        DistanceBand.State previous = band.update(34.0D);
        for (int i = 0; i < 40; i++) {
            DistanceBand.State s = band.update(i % 2 == 0 ? 34.4D : 33.6D);
            if (s != previous) {
                changes++;
            }
            previous = s;
        }
        assertTrue(changes <= 1, "changes: " + changes);
        assertEquals(DistanceBand.State.UNKNOWN, band.update(Double.NaN));
    }

    @Test
    void ringIsAutoOrConfiguredOrVerified() {
        CombatConfig cfg = new CombatConfig();
        DistanceBand band = new DistanceBand(cfg.minSafe, cfg.maxCombat, cfg.hysteresis);
        assertEquals(6.0D + 0.4D * 28.0D, cfg.ring(band), 1e-9);
        assertTrue(cfg.ringSource().startsWith("AUTO"));
        cfg.preferredDistance = 15.0D;
        assertEquals(15.0D, cfg.ring(band), 1e-9);
        cfg.knownPreferredDistance = 11.0D;
        assertEquals(11.0D, cfg.ring(band), 1e-9, "a verified value wins");
        assertEquals("verified", cfg.ringSource());
        cfg.knownPreferredDistance = 500.0D;
        assertEquals(33.0D, cfg.ring(band), 1e-9, "never outside the band");
    }

    // ── lifecycle and basic flow ────────────────────────────────────────────

    @Test
    void idleDoesNothing() {
        CombatSim s = sim(GridTerrain.open(60), 30, 30);
        s.bandit(B1, 40, 30);
        Decision d = s.tick();
        assertEquals(CombatState.IDLE, d.state());
        assertEquals(Decision.Move.Kind.NONE, d.move().kind());
        assertEquals(Decision.Look.NONE, d.look());
        assertFalse(d.attackAllowed());
    }

    @Test
    void nothingInReachStaysInScan() {
        CombatSim s = sim(GridTerrain.open(60), 30, 30);
        s.start();
        s.run(40);
        assertEquals(CombatState.SCAN, s.brain.state());
        assertEquals(Decision.Move.Kind.NONE, s.last.move().kind());
    }

    @Test
    void aFarBanditIsApproachedThenOrbited() {
        CombatSim s = sim(GridTerrain.open(100), 10, 50);
        s.bandit(B1, 70, 50);
        s.start();
        assertTrue(s.runUntil(() -> s.brain.state() == CombatState.ORBIT, 400), "history " + s.history);
        assertTrue(s.history.indexOf(CombatState.APPROACH) < s.history.indexOf(CombatState.ORBIT));
        assertTrue(s.distanceToBandit(B1) <= 34.0D, "arrived inside the combat band");
        assertTrue(s.attackAllowedSeen || s.runUntil(() -> s.attackAllowedSeen, 20), "the attack is allowed in orbit");
    }

    // ── orbit ────────────────────────────────────────────────────────────────

    @Test
    void orbitMovesAroundTheTargetNotAroundItself() {
        CombatSim s = sim(GridTerrain.open(100), 50, 70);
        s.spear = SpearStatus.KNOWN_COOLDOWN; // no attack: the orbit alone
        s.bandit(B1, 50, 50);
        s.start();
        s.run(10);
        assertEquals(CombatState.ORBIT, s.brain.state());
        double start = Math.atan2(s.z - 50, s.x - 50);
        double maxDrift = 0;
        double travelled = 0;
        double lastAngle = start;
        for (int i = 0; i < 700; i++) {
            s.tick();
            double angle = Math.atan2(s.z - 50, s.x - 50);
            double d = angle - lastAngle;
            while (d > Math.PI) d -= 2 * Math.PI;
            while (d < -Math.PI) d += 2 * Math.PI;
            travelled += d;
            lastAngle = angle;
            maxDrift = Math.max(maxDrift, Math.abs(s.distanceToBandit(B1) - s.brain.ring()));
            assertEquals(CombatState.ORBIT, s.brain.state(), "history " + s.history);
        }
        assertTrue(Math.abs(Math.toDegrees(travelled)) > 360.0D, "went round the target more than once: " + Math.toDegrees(travelled));
        assertTrue(maxDrift < 4.0D, "kept the radius, drift " + maxDrift);
        assertNotNull(s.brain.orbitDir());
    }

    @Test
    void theOrbitKeepsTheSameSideWhileItIsFree() {
        CombatSim s = sim(GridTerrain.open(100), 50, 70);
        s.spear = SpearStatus.KNOWN_COOLDOWN;
        s.bandit(B1, 50, 50);
        s.start();
        s.run(200);
        long flips = s.traceLog.stream().filter(l -> l.startsWith("ORBIT_FLIP")).count();
        assertEquals(0, flips);
    }

    @Test
    void aBlockedLeftSideMakesTheOrbitGoRight() {
        // Player south of the bandit facing north: LEFT tangent points west; wall on the west side.
        GridTerrain wallWest = GridTerrain.open(100);
        for (int zz = 55; zz < 100; zz++) {
            for (int xx = 0; xx < 49; xx++) {
                wallWest = wallWest.with(xx, zz, '#');
            }
        }
        CombatSim s = sim(wallWest, 50.5, 68.5);
        s.bandit(B1, 50.5, 50.5);
        s.start();
        s.run(6);
        assertEquals(OrbitPlanner.Dir.RIGHT, s.brain.orbitDir(), "the free side");
        assertTrue(s.x >= 50.0D, "did not walk into the wall, x=" + s.x);
    }

    @Test
    void aBlockedRightSideMakesTheOrbitGoLeft() {
        GridTerrain wallEast = GridTerrain.open(100);
        for (int zz = 55; zz < 100; zz++) {
            for (int xx = 52; xx < 100; xx++) {
                wallEast = wallEast.with(xx, zz, '#');
            }
        }
        CombatSim s = sim(wallEast, 50.5, 68.5);
        s.bandit(B1, 50.5, 50.5);
        s.start();
        s.run(6);
        assertEquals(OrbitPlanner.Dir.LEFT, s.brain.orbitDir());
    }

    @Test
    void theOrbitFlipsWhenTheSideGetsBlockedLaterButNotMoreOftenThanAllowed() {
        CombatConfig cfg = new CombatConfig();
        OrbitPlanner orbit = new OrbitPlanner();
        GridTerrain open = GridTerrain.open(100);
        // start: both free, deterministic first side
        OrbitPlanner.Step first = orbit.plan(cfg, 50.5, 64.0, 70.5, 50.5, 50.5, 20.0, List.of(), open, 1_000L);
        assertFalse(first.blocked());
        OrbitPlanner.Dir chosen = first.dir();
        // the chosen side gets walled
        GridTerrain walled = open;
        double[] r = {0.0, 1.0};
        double[] t = OrbitPlanner.Dir.LEFT == chosen ? new double[]{-r[1], r[0]} : new double[]{r[1], -r[0]};
        for (int i = 1; i <= 4; i++) {
            walled = walled.with((int) Math.floor(50.5 + t[0] * i), (int) Math.floor(70.5 + t[1] * i), '#');
        }
        OrbitPlanner.Step second = orbit.plan(cfg, 50.5, 64.0, 70.5, 50.5, 50.5, 20.0, List.of(), walled, 1_100L);
        assertEquals(chosen.name().equals("LEFT") ? "RIGHT" : "LEFT", second.dir().name(), "flipped to the free side: " + second.note());
        assertTrue(second.flipped() || second.dir() != chosen);
        // immediately blocked on the new side too: no flip-flop inside the minimum time unless fully stuck
        int flipsBefore = orbit.flips();
        for (int i = 0; i < 5; i++) {
            orbit.plan(cfg, 50.5, 64.0, 70.5, 50.5, 50.5, 20.0, List.of(), walled, 1_200L + i * 50L);
        }
        assertTrue(orbit.flips() - flipsBefore <= 1, "flips " + (orbit.flips() - flipsBefore));
    }

    @Test
    void bothSidesBlockedMeansReposition() {
        GridTerrain corridor = GridTerrain.open(100);
        for (int xx = 0; xx < 100; xx++) {
            if (xx < 50 || xx > 51) {
                for (int zz = 50; zz < 100; zz++) {
                    corridor = corridor.with(xx, zz, '#');
                }
            }
        }
        CombatSim s = sim(corridor, 50.5, 68.5);
        s.bandit(B1, 50.5, 52.5);
        s.start();
        s.run(30);
        assertTrue(s.seen.contains(CombatState.REPOSITION) || s.seen.contains(CombatState.EVADE) || s.seen.contains(CombatState.RECOVER),
                "history " + s.history);
        assertTrue(s.seen.contains(CombatState.REPOSITION) || s.seen.contains(CombatState.RECOVER), "history " + s.history);
    }

    @Test
    void anEdgeIsNotOrbitedOver() {
        // A drop on the west side of the bandit's ring.
        GridTerrain pit = GridTerrain.open(100);
        for (int zz = 45; zz < 100; zz++) {
            for (int xx = 0; xx < 47; xx++) {
                pit = pit.with(xx, zz, ' ');
            }
        }
        CombatSim s = sim(pit, 50.5, 70.5);
        s.bandit(B1, 50.5, 50.5);
        s.start();
        s.run(300);
        assertTrue(s.x > 45.0D, "stayed on the floor, x=" + s.x);
    }

    // ── target choice and lock ──────────────────────────────────────────────

    @Test
    void theBanditCloserToTheRingWithSightWins() {
        CombatSim s = sim(GridTerrain.open(100), 50, 50);
        s.bandit("bandit_aa_00000a", 50 + 55, 50);   // far, out of range 60? within 60? 55
        s.bandit("bandit_aa_00000b", 50 + 17, 50);   // near the ring
        s.start();
        s.tick();
        assertEquals("bandit_aa_00000b", s.brain.targetId());
    }

    @Test
    void banditsBeyondTheRangeAndCrowdedByPlayersAreNoTargets() {
        CombatSim s = sim(GridTerrain.open(200), 50, 50);
        s.bandit("bandit_aa_00000a", 50 + 90, 50);     // beyond 60
        s.bandit("bandit_aa_00000b", 50 + 20, 50);     // a player stands next to it
        s.player("Alex", 50 + 22, 50);
        s.cfg.playerSafetyRadius = 10.0D;              // (so the player alone is no reason to retreat)
        s.start();
        s.run(5);
        assertNull(s.brain.targetId());
        assertEquals(CombatState.SCAN, s.brain.state(), "history " + s.history);
    }

    @Test
    void theTargetStaysLockedWhileAnotherIsOnlySlightlyBetter() {
        CombatSim s = sim(GridTerrain.open(100), 50, 70);
        s.bandit(B1, 50, 52);
        s.bandit(B2, 70, 62);
        s.start();
        s.run(5);
        String first = s.brain.targetId();
        assertNotNull(first);
        int switches = 0;
        String current = first;
        for (int i = 0; i < 200; i++) {
            // wobble the other bandit so the scores keep changing a little
            double[] other = s.bandits.get(first.equals(B1) ? B2 : B1);
            other[0] += (i % 2 == 0 ? 0.3 : -0.3);
            s.tick();
            if (s.brain.targetId() != null && !s.brain.targetId().equals(current)) {
                switches++;
                current = s.brain.targetId();
            }
        }
        assertEquals(0, switches, "no target thrashing");
    }

    @Test
    void aMuchBetterTargetTakesOverOnlyAfterTheLockIsOldEnough() {
        CombatConfig cfg = new CombatConfig();
        TargetSelector selector = new TargetSelector();
        DistanceBand band = new DistanceBand(cfg.minSafe, cfg.maxCombat, cfg.hysteresis);
        Foe far = new Foe(B1, "ORE_BANDIT", 50, 64, 100, 0, 0, 20, 20, Foe.Sight.NO);        // 50 blocks, no sight
        Foe near = new Foe(B2, "ORE_BANDIT", 50, 64, 70, 0, 0, 20, 20, Foe.Sight.YES);       // 20 blocks, sight
        GridTerrain open = GridTerrain.open(200);
        TargetSelector.Result first = selector.select(List.of(far), List.of(), 50, 64, 50, 20.0, open, cfg, band, 1_000L);
        assertEquals(B1, first.target().id());
        // a far better one shows up right away: the lock holds for lockMinMs
        TargetSelector.Result early = selector.select(List.of(far, near), List.of(), 50, 64, 50, 20.0, open, cfg, band, 1_200L);
        assertEquals(B1, early.target().id(), "too early to switch");
        TargetSelector.Result later = selector.select(List.of(far, near), List.of(), 50, 64, 50, 20.0, open, cfg, band, 3_000L);
        assertEquals(B2, later.target().id(), "much better and the lock is old enough");
        assertTrue(later.switched());
    }

    @Test
    void aBlacklistedBanditIsNotChosenAgainUntilItExpires() {
        CombatConfig cfg = new CombatConfig();
        TargetSelector selector = new TargetSelector();
        DistanceBand band = new DistanceBand(cfg.minSafe, cfg.maxCombat, cfg.hysteresis);
        Foe f = new Foe(B1, "ORE_BANDIT", 70, 64, 50, 0, 0, 20, 20, Foe.Sight.YES);
        selector.blacklist(B1, 10_000L);
        assertNull(selector.select(List.of(f), List.of(), 50, 64, 50, 20.0, GridTerrain.open(100), cfg, band, 5_000L).target());
        assertNotNull(selector.select(List.of(f), List.of(), 50, 64, 50, 20.0, GridTerrain.open(100), cfg, band, 10_001L).target());
    }

    // ── threats, evade, retreat ─────────────────────────────────────────────

    @Test
    void aBanditThatClosesInIsEvaded() {
        CombatSim s = sim(GridTerrain.open(100), 50, 70);
        s.bandit(B1, 50, 50);
        s.banditSpeed = 7.0D; // faster than the player: it cannot be kited, it must be evaded
        s.start();
        assertTrue(s.runUntil(() -> s.seen.contains(CombatState.EVADE), 600), "history " + s.history);
        assertTrue(s.seen.contains(CombatState.ORBIT) || s.seen.contains(CombatState.APPROACH));
    }

    @Test
    void anOtherBanditComingCloseChangesPositioningWithoutBecomingTheTarget() {
        CombatSim s = sim(GridTerrain.open(100), 50, 70);
        s.bandit(B1, 50, 50);
        s.bandit(B2, 56, 66);   // 7.2 blocks from the player: a threat, not the target
        s.start();
        s.run(5);
        assertEquals(B1, s.brain.targetId());
        assertEquals(1, s.brain.threats().count(), "the target is 18 blocks away, outside the 12 block threat radius");
        assertEquals(1, s.brain.threats().nearby().size());
        assertEquals(B2, s.brain.threats().nearby().get(0).id());
        assertEquals(B1, s.brain.targetId(), "still the first target");
    }

    @Test
    void twoBanditsCloseAtOnceMeanEvade() {
        CombatConfig cfg = new CombatConfig();
        CombatSim s = new CombatSim(cfg, GridTerrain.open(100), 50, 70);
        s.bandit(B1, 50, 52);
        s.bandit(B2, 53, 67);
        s.bandit("bandit_cc_000003", 47, 68);
        s.start();
        s.run(4);
        assertTrue(s.seen.contains(CombatState.EVADE) || s.seen.contains(CombatState.RETREAT), "history " + s.history);
    }

    @Test
    void tooManyThreatsBreakOffTheFight() {
        CombatSim s = sim(GridTerrain.open(100), 50, 70);
        s.bandit(B1, 50, 52);
        for (int i = 0; i < 4; i++) {
            s.bandit(String.format("bandit_dd_%06x", i), 44 + i * 3, 64);
        }
        s.start();
        s.run(10);
        assertTrue(s.seen.contains(CombatState.RETREAT), "history " + s.history);
        assertTrue(s.brain.retreatReason().startsWith("TOO_MANY_THREATS"));
    }

    @Test
    void lowHealthRetreatsThenWaitsForHealthBeforeResuming() {
        CombatSim s = sim(GridTerrain.open(100), 50, 70);
        s.bandit(B1, 50, 40);
        s.start();
        s.run(5);
        s.health = 7.0D;
        s.run(5);
        assertEquals(CombatState.RETREAT, s.brain.state());
        assertEquals("LOW_HEALTH", s.brain.retreatReason());
        s.bandits.clear(); // out of sight / reach
        s.runUntil(() -> s.brain.state() == CombatState.COOLDOWN, 400);
        assertEquals(CombatState.COOLDOWN, s.brain.state(), "history " + s.history);
        s.run(60);
        assertEquals(CombatState.COOLDOWN, s.brain.state(), "still waiting for health: " + s.last.detail());
        s.health = 14.0D;
        s.bandit(B1, 50, 40);
        s.run(40);
        assertTrue(s.seen.contains(CombatState.SCAN) && s.history.get(s.history.size() - 1) != CombatState.COOLDOWN, "history " + s.history);
    }

    @Test
    void retreatingThreeTimesQuicklyStopsTheMacro() {
        CombatSim s = sim(GridTerrain.open(100), 50, 70);
        s.start();
        for (int round = 0; round < 3 && s.stopReason == null; round++) {
            s.bandit(B1, 50, 52);
            s.run(3);
            s.health = 7.0D;
            s.run(3);
            s.bandits.clear();
            s.health = 20.0D;
            s.run(2);
            s.runUntil(() -> s.brain.state() == CombatState.SCAN || s.brain.state() == CombatState.STOPPED, 500);
            s.now += 12_000L; // the next round comes later than the target-reacquire window (a quick chain of those is a loop of its own)
        }
        assertEquals(CombatState.STOPPED, s.brain.state(), "history " + s.history);
        assertTrue(s.stopReason.startsWith("Keeps retreating"), s.stopReason);
    }

    @Test
    void criticalHealthStopsAfterTheSpearIsBack() {
        CombatSim s = sim(GridTerrain.open(100), 50, 70);
        s.bandit(B1, 50, 52);
        s.start();
        s.run(3);
        s.health = 3.0D;
        Decision d = s.tick();
        assertEquals(CombatState.STOPPED, d.state());
        assertTrue(d.stopAfterRecall());
        assertTrue(d.stopReason().contains("HEALTH_CRITICAL"));
    }

    @Test
    void aNearbyPlayerMakesTheMacroBackOff() {
        CombatSim s = sim(GridTerrain.open(100), 50, 70);
        s.bandit(B1, 50, 52);
        s.start();
        s.run(3);
        s.player("Alex", 50, 85);   // 15 blocks, inside the 30 block radius
        s.run(3);
        assertTrue(s.seen.contains(CombatState.RETREAT), "history " + s.history);
        assertTrue(s.brain.retreatReason().startsWith("PLAYER_IN_RADIUS"));
    }

    @Test
    void lowHealthWithNothingAroundIsAWaitNotARetreat() {
        CombatSim s = sim(GridTerrain.open(100), 50, 70);
        s.start();
        s.run(3);
        s.health = 7.0D;
        s.run(5);
        assertEquals(CombatState.COOLDOWN, s.brain.state());
        assertEquals("LOW_HEALTH_WAIT", s.brain.reason());
        assertFalse(s.traceLog.stream().anyMatch(l -> l.contains("-> RETREAT")), "no retreat without a threat");
        assertTrue(s.last.detail().startsWith("waiting for health"));
        s.health = 15.0D;
        s.run(30);
        assertEquals(CombatState.SCAN, s.brain.state());
    }

    @Test
    void aRetreatFromAPlayerEndsOnlyFarOutsideTheSafetyRadius() {
        CombatSim s = sim(GridTerrain.open(200), 100, 100);
        s.bandit(B1, 100, 82);
        s.start();
        s.run(3);
        s.player("Alex", 100, 125);    // 25 blocks: inside the 30 block radius
        s.run(3);
        assertEquals(CombatState.RETREAT, s.brain.state());
        // The player stays at a fixed place; the macro runs away and may only calm down beyond 36 blocks (1.2 x 30).
        s.runUntil(() -> s.brain.state() != CombatState.RETREAT, 600);
        double distance = Math.hypot(s.x - 100, s.z - 125);
        assertTrue(distance >= 36.0D || s.brain.state() == CombatState.COOLDOWN && distance >= 35.0D, "stopped retreating at " + distance + " blocks");
    }

    @Test
    void aTargetMissingForAMomentKeepsItsLock() {
        CombatSim s = sim(GridTerrain.open(100), 50, 70);
        s.spear = SpearStatus.KNOWN_COOLDOWN;
        s.bandit(B1, 50, 52);
        s.start();
        s.run(10);
        String id = s.brain.targetId();
        double[] gone = s.bandits.remove(B1);
        s.run(10); // 0.5 s: inside the grace time
        assertEquals(id, s.brain.targetId(), "still locked");
        assertEquals(CombatState.ORBIT, s.brain.state());
        assertEquals(Decision.Move.Kind.NONE, s.last.move().kind(), "holds still while it looks for it");
        s.bandits.put(B1, gone);
        s.run(10);
        assertEquals(id, s.brain.targetId());
        assertFalse(s.seen.contains(CombatState.LOST_TARGET));
        s.bandits.remove(B1);
        s.run(60); // 3 s: beyond the grace time
        assertTrue(s.traceLog.stream().anyMatch(l -> l.contains("-> LOST_TARGET reason=TARGET_GONE")));
    }

    // ── lost target, recovery, manual input ─────────────────────────────────

    @Test
    void aVanishedTargetIsLostThenTheMacroScansAgain() {
        CombatSim s = sim(GridTerrain.open(100), 50, 70);
        s.bandit(B1, 50, 52);
        s.start();
        s.run(10);
        assertEquals(B1, s.brain.targetId());
        s.bandits.get(B1)[2] = 5.0D; // hurt
        s.run(3);
        s.bandits.remove(B1);
        s.run(2);
        assertEquals(B1, s.brain.targetId(), "inside the grace time the lock stays");
        s.run(40);
        assertTrue(s.traceLog.stream().anyMatch(l -> l.contains("-> LOST_TARGET")), "trace " + s.traceLog);
        s.run(30);
        assertEquals(CombatState.SCAN, s.brain.state());
        assertNull(s.brain.targetId());
        assertEquals(1, s.brain.kills(), "a hurt bandit that disappeared counts as gone");
    }

    @Test
    void manualInputStopsEverythingAtOnce() {
        CombatSim s = sim(GridTerrain.open(100), 50, 70);
        s.spear = SpearStatus.KNOWN_COOLDOWN;
        s.bandit(B1, 50, 52);
        s.start();
        s.run(10);
        assertEquals(CombatState.ORBIT, s.brain.state());
        s.manual = true;
        Decision d = s.tick();
        assertEquals(CombatState.STOPPED, d.state());
        assertEquals("MANUAL_INPUT", d.stopReason());
        assertEquals(Decision.Move.Kind.NONE, d.move().kind());
        assertEquals(Decision.Look.NONE, d.look());
        assertFalse(d.attackAllowed());
        s.manual = false;
        assertEquals(CombatState.STOPPED, s.tick().state(), "stays stopped");
    }

    @Test
    void recoveryIsBoundedAndEndsInALostTargetNotAnEndlessLoop() {
        // A pocket: walls on three sides, the bandit just outside it. Every escape is a dead end.
        GridTerrain pocket = GridTerrain.open(100);
        for (int xx = 47; xx <= 53; xx++) {
            pocket = pocket.with(xx, 71, '#');
        }
        for (int zz = 66; zz <= 71; zz++) {
            pocket = pocket.with(47, zz, '#').with(53, zz, '#');
        }
        CombatSim s = sim(pocket, 50.5, 68.5);
        s.bandit(B1, 50.5, 62.5);   // 6 blocks, too close, the only way out is through it
        s.start();
        s.run(1500);
        long recovers = s.history.stream().filter(c -> c == CombatState.RECOVER).count();
        assertTrue(recovers <= 12, "bounded recoveries: " + recovers + " history " + s.history);
        assertTrue(s.brain.state() != CombatState.IDLE);
    }

    @Test
    void pathFailuresEndInARepositionThenTheTargetIsGivenUp() {
        // A wall between the player and the bandit and a path planner that never finds a way.
        GridTerrain wall = GridTerrain.open(120);
        for (int zz = 0; zz < 120; zz++) {
            wall = wall.with(60, zz, '#');
        }
        CombatSim s = sim(wall, 30.5, 60.5);
        s.bandit(B1, 80.5, 60.5);
        s.pathWorks = false;
        s.start();
        for (int i = 0; i < 400; i++) {
            s.pathFailures = Math.min(6, i / 20);
            s.tick();
        }
        String all = String.join("\n", s.traceLog);
        assertTrue(all.contains("-> REPOSITION reason=NO_PATH"), all);
        assertTrue(all.contains("-> LOST_TARGET reason=UNREACHABLE"), all);
        assertTrue(s.traceLog.size() < 40, "no flutter afterwards: " + s.traceLog.size() + " lines");
    }

    // ── spear: unknown stays unknown ────────────────────────────────────────

    @Test
    void anUnknownSpearIsHandledConservativelyAKnownCooldownBlocksTheAttack() {
        CombatSim unknown = sim(GridTerrain.open(100), 50, 70);
        unknown.spear = SpearStatus.UNKNOWN;
        unknown.bandit(B1, 50, 52);
        unknown.start();
        unknown.run(20);
        assertTrue(unknown.attackAllowedSeen, "unknown: the macro's own pacing decides, the brain does not forbid");

        CombatSim cooling = sim(GridTerrain.open(100), 50, 70);
        cooling.spear = SpearStatus.KNOWN_COOLDOWN;
        cooling.bandit(B1, 50, 52);
        cooling.start();
        cooling.run(60);
        assertFalse(cooling.attackAllowedSeen, "a known cooldown means no attack");
        assertEquals(CombatState.ORBIT, cooling.brain.state(), "but it keeps orbiting");
    }

    @Test
    void spearStatusNeverGuessesACooldown() {
        assertEquals(SpearStatus.UNKNOWN, SpearStatus.of(GameValue.unknown("x"), GameValue.unknown("y")));
        assertEquals(SpearStatus.UNKNOWN, SpearStatus.of(GameValue.live(true, "held", 1L), GameValue.unknown("cooldown")));
        assertEquals(SpearStatus.UNKNOWN, SpearStatus.of(GameValue.live(false, "held", 1L), GameValue.live(0.0D, "cd", 1L)));
        assertEquals(SpearStatus.KNOWN_READY, SpearStatus.of(GameValue.live(true, "held", 1L), GameValue.live(0.0D, "cd", 1L)));
        assertEquals(SpearStatus.KNOWN_COOLDOWN, SpearStatus.of(GameValue.live(true, "held", 1L), GameValue.live(0.4D, "cd", 1L)));
    }

    @Test
    void theAttackCommitMakesTheMacroStandStill() {
        CombatSim s = sim(GridTerrain.open(100), 50, 70);
        s.bandit(B1, 50, 52);
        s.start();
        assertTrue(s.runUntil(() -> s.brain.state() == CombatState.ENGAGE, 200), "history " + s.history);
        assertEquals(Decision.Move.Kind.NONE, s.last.move().kind());
        assertTrue(s.runUntil(() -> s.brain.state() == CombatState.ORBIT, 100), "back to orbit after the throw");
    }

    // ── world change, reset, logging ────────────────────────────────────────

    @Test
    void invalidateDropsTheTargetAndScansAgain() {
        CombatSim s = sim(GridTerrain.open(100), 50, 70);
        s.bandit(B1, 50, 52);
        s.start();
        s.run(10);
        assertNotNull(s.brain.targetId());
        s.brain.invalidate(s.now, "WORLD_CHANGED");
        assertEquals(CombatState.COOLDOWN, s.brain.state());
        assertNull(s.brain.targetId());
        assertNull(s.brain.orbitDir());
        s.run(30);
        assertTrue(s.seen.contains(CombatState.SCAN));
    }

    @Test
    void idleResetsEveryTemporaryState() {
        CombatSim s = sim(GridTerrain.open(100), 50, 70);
        s.bandit(B1, 50, 52);
        s.start();
        s.run(30);
        s.brain.idle(s.now);
        assertEquals(CombatState.IDLE, s.brain.state());
        assertNull(s.brain.targetId());
        assertNull(s.brain.orbitDir());
        assertEquals(0, s.brain.recoveryCount());
        assertEquals(0, s.brain.threats().count());
        Decision d = s.tick();
        assertEquals(Decision.Move.Kind.NONE, d.move().kind());
    }

    @Test
    void stateChangesAreLoggedCompactlyNotEveryTick() {
        CombatSim s = sim(GridTerrain.open(100), 50, 70);
        s.spear = SpearStatus.KNOWN_COOLDOWN;
        s.bandit(B1, 50, 52);
        s.start();
        s.run(400);
        long stateLines = s.traceLog.stream().filter(l -> l.startsWith("STATE")).count();
        assertTrue(stateLines <= 12, "state lines: " + stateLines);
        assertTrue(s.traceLog.size() < 40, "trace lines: " + s.traceLog.size());
        assertTrue(s.traceLog.stream().anyMatch(l -> l.startsWith("TARGET " + B1)));
    }

    @Test
    void summaryHasWhatADiagnosisNeeds() {
        CombatSim s = sim(GridTerrain.open(100), 50, 70);
        s.bandit(B1, 50, 52);
        s.start();
        s.run(30);
        var m = s.brain.summary(s.now);
        for (String key : List.of("bandit.state", "bandit.target", "bandit.targetDistance", "bandit.distanceState", "bandit.threats",
                "bandit.orbitDirection", "bandit.recoveries", "bandit.retreatReason", "bandit.loops", "bandit.terrain.00", "bandit.ring")) {
            assertTrue(m.containsKey(key), key);
        }
        assertTrue(m.get("bandit.ring").contains("AUTO"), "the radius says it is AUTO / unknown: " + m.get("bandit.ring"));
        assertTrue(s.brain.describe(new CombatInputs(s.now, new CombatInputs.Me(0, 0, 0, 0, 20, true, false), List.of(), List.of(), s.terrain,
                SpearStatus.UNKNOWN, AttackPhase.NONE, CombatInputs.PathStatus.NONE, 0)).contains("BANDIT_STATE="));
    }
}
