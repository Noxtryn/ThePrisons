package io.theprisons.modules.qol.bandit;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BanditLogicTest {
    private static final BanditTargeting.Params PARAMS = new BanditTargeting.Params(6, 34, 60, 25);

    private static BanditTargeting.Candidate cand(String id, double dist, boolean sight, int crowd, int neighbours) {
        return new BanditTargeting.Candidate(id, dist, 64, 0, dist, sight, crowd, neighbours);
    }

    // ── identification ──

    @Test
    void banditNames() {
        assertTrue(BanditScan.isBanditName("bandit_ae_821e4c"));
        assertTrue(BanditScan.isBanditName("Bandit_0F_00ab12"));
        assertFalse(BanditScan.isBanditName("bandit_xyz"));
        assertFalse(BanditScan.isBanditName("Steve"));
        assertFalse(BanditScan.isBanditName("guard_452_e3d1c7"));
    }

    // ── target selection ──

    @Test
    void prefersTheBanditInCombatDistanceWithSight() {
        List<BanditTargeting.Candidate> list = List.of(cand("far", 55, true, 0, 0), cand("ideal", 20, true, 0, 0), cand("blind", 20, false, 0, 0));
        assertEquals("ideal", BanditTargeting.select(list, null, 0, 6000, PARAMS).id());
    }

    @Test
    void ignoresTheOutOfRangeAndTheCrowdedOnes() {
        List<BanditTargeting.Candidate> list = List.of(cand("out", 90, true, 0, 0), cand("crowded", 20, true, 1, 0));
        assertNull(BanditTargeting.select(list, null, 0, 6000, PARAMS));
    }

    @Test
    void stickinessKeepsTheTargetOnlyWhileItIsFresh() {
        BanditTargeting.Candidate current = cand("a", 24, true, 0, 0);
        BanditTargeting.Candidate slightlyBetter = cand("b", 20, true, 0, 0);
        List<BanditTargeting.Candidate> list = List.of(current, slightlyBetter);
        assertEquals("a", BanditTargeting.select(list, "a", 1000, 6000, PARAMS).id());
        assertEquals("b", BanditTargeting.select(list, "a", 9000, 6000, PARAMS).id());
        // a much better one still wins
        List<BanditTargeting.Candidate> clear = List.of(cand("a", 70, false, 0, 0), cand("c", 20, true, 0, 2));
        assertEquals("c", BanditTargeting.select(clear, "a", 1000, 6000, PARAMS).id());
    }

    // ── danger ──

    @Test
    void dangerLevels() {
        assertEquals(BanditDanger.Level.NONE, BanditDanger.evaluate(List.of(), 30, 20, 6).level());
        assertEquals(BanditDanger.Level.NONE, BanditDanger.evaluate(List.of(new BanditDanger.Contact("A", 55, 0.0)), 30, 20, 6).level());
        assertEquals(BanditDanger.Level.CAUTION, BanditDanger.evaluate(List.of(new BanditDanger.Contact("A", 25, 0.0)), 30, 20, 6).level());
        assertEquals(BanditDanger.Level.CAUTION, BanditDanger.evaluate(List.of(new BanditDanger.Contact("A", 40, 0.25)), 30, 20, 6).level());
        assertEquals(BanditDanger.Level.NONE, BanditDanger.evaluate(List.of(new BanditDanger.Contact("A", 40, 0.05)), 30, 20, 6).level());
        assertEquals(BanditDanger.Level.CRITICAL, BanditDanger.evaluate(List.of(new BanditDanger.Contact("A", 10, 0.0)), 30, 20, 6).level());
        assertEquals(BanditDanger.Level.CRITICAL, BanditDanger.evaluate(List.of(), 30, 5, 6).level());
        List<BanditDanger.Contact> crowd = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            crowd.add(new BanditDanger.Contact("P" + i, 25, 0.0));
        }
        assertEquals(BanditDanger.Level.CRITICAL, BanditDanger.evaluate(crowd, 30, 20, 6).level());
        assertEquals("A", BanditDanger.evaluate(List.of(new BanditDanger.Contact("A", 25, 0.0)), 30, 20, 6).name());
    }

    // ── spear ──

    @Test
    void spearStates() {
        assertEquals(SpearState.READY, SpearState.classify(true, true, false, false));
        assertEquals(SpearState.AVAILABLE, SpearState.classify(false, true, false, false));
        assertEquals(SpearState.MISSING, SpearState.classify(false, false, false, false));
        assertEquals(SpearState.THROWN, SpearState.classify(false, false, true, false));
        assertEquals(SpearState.RETURNING, SpearState.classify(false, false, true, true));
    }

    // ── state machine ──

    private static BanditFsm fsm(List<String> log) {
        return new BanditFsm(new BanditFsm.Timeouts(100_000, 14_000, 8_000, 3_000, 9_000, 3_000, 10_000, 5_000, 4_000), log::add);
    }

    @Test
    void normalAttackFlow() {
        List<String> log = new ArrayList<>();
        BanditFsm f = fsm(log);
        long t = 0;
        for (BanditState s : List.of(BanditState.SEARCHING, BanditState.TARGET_ACQUIRED, BanditState.POSITIONING, BanditState.AIMING,
                BanditState.READY_TO_THROW, BanditState.THROWING, BanditState.WAITING_FOR_RETURN, BanditState.RECALL_REQUIRED,
                BanditState.RECALLING, BanditState.TARGET_RECHECK, BanditState.TARGET_ACQUIRED)) {
            assertTrue(f.to(s, "test", t += 10), s.name());
        }
        assertEquals(11, f.moves());
        assertTrue(log.get(0).contains("IDLE -> SEARCHING"));
    }

    @Test
    void refusesMovesThatSkipTheSafeOrder() {
        BanditFsm f = fsm(new ArrayList<>());
        assertFalse(f.to(BanditState.THROWING, "skip", 0), "IDLE cannot throw");
        f.to(BanditState.SEARCHING, "go", 0);
        assertFalse(f.to(BanditState.THROWING, "skip", 1), "no throw without aiming");
        assertFalse(f.to(BanditState.READY_TO_THROW, "skip", 1));
        assertEquals(BanditState.SEARCHING, f.state());
    }

    @Test
    void safetyMovesWorkFromEveryActiveState() {
        for (BanditState s : BanditState.values()) {
            if (s.inactive()) {
                continue;
            }
            assertTrue(BanditFsm.allowed(s, BanditState.RETREATING) || s == BanditState.RETREATING, s + " -> RETREATING");
            assertTrue(BanditFsm.allowed(s, BanditState.FAILSAFE), s + " -> FAILSAFE");
            assertTrue(BanditFsm.allowed(s, BanditState.STOPPED), s + " -> STOPPED");
            if (s != BanditState.RECALL_REQUIRED) {
                assertTrue(BanditFsm.allowed(s, BanditState.RECALL_REQUIRED), s + " -> RECALL_REQUIRED");
            }
        }
        assertFalse(BanditFsm.allowed(BanditState.IDLE, BanditState.RETREATING));
        assertFalse(BanditFsm.allowed(BanditState.STOPPED, BanditState.RECOVERING));
        assertTrue(BanditFsm.allowed(BanditState.FAILSAFE, BanditState.STOPPED));
    }

    @Test
    void timeoutsPerState() {
        BanditFsm f = fsm(new ArrayList<>());
        f.to(BanditState.SEARCHING, "go", 0);
        f.to(BanditState.TARGET_ACQUIRED, "t", 1000);
        f.to(BanditState.AIMING, "a", 2000);
        f.to(BanditState.READY_TO_THROW, "r", 2000);
        f.to(BanditState.THROWING, "f", 2000);
        assertFalse(f.timedOut(4_900));
        assertTrue(f.timedOut(5_100), "throw limit 3 s");
        f.to(BanditState.WAITING_FOR_RETURN, "away", 6000);
        assertFalse(f.timedOut(14_900));
        assertTrue(f.timedOut(15_100), "return limit 9 s");
        f.to(BanditState.RECALL_REQUIRED, "late", 15_100);
        f.to(BanditState.RECOVERING, "timeout", 15_200);
        f.to(BanditState.SEARCHING, "recovered", 16_000);
        assertEquals(100_000, f.limit());
        assertNotNull(f.reason());
    }

    @Test
    void failsafeEndsInStopped() {
        BanditFsm f = fsm(new ArrayList<>());
        f.to(BanditState.SEARCHING, "go", 0);
        assertTrue(f.to(BanditState.FAILSAFE, "NO_SPEAR", 10));
        assertFalse(f.to(BanditState.SEARCHING, "again", 20));
        assertTrue(f.to(BanditState.STOPPED, "done", 30));
        assertTrue(f.state().inactive());
    }

    @Test
    void spearOutStates() {
        assertTrue(BanditState.WAITING_FOR_RETURN.spearOut());
        assertTrue(BanditState.RECALLING.spearOut());
        assertFalse(BanditState.AIMING.spearOut());
    }

    // ── piercing aim ──

    @Test
    void pierceYawTurnsToHitASecondBanditBehindTheTarget() {
        List<BanditLine.Body> bodies = List.of(new BanditLine.Body(1, 20, 0.3), new BanditLine.Body(2, 40, 0.3));
        float yaw = BanditLine.pierceYaw(0, 0, 0, bodies, 0, 60, 0.45, 6);
        assertTrue(BanditLine.hits(0, 0, yaw, bodies.get(0), 60, 0.45), "still hits the target");
        assertEquals(2, BanditLine.countRay(0, 0, yaw, bodies, 60, 0.45));
        assertTrue(yaw < 0, "towards +x is a negative yaw: " + yaw);
    }

    @Test
    void pierceYawKeepsTheDirectionWhenNothingIsBehind() {
        List<BanditLine.Body> bodies = List.of(new BanditLine.Body(0, 20, 0.3), new BanditLine.Body(30, 5, 0.3));
        assertEquals(0.0F, BanditLine.pierceYaw(0, 0, 0, bodies, 0, 60, 0.45, 6), 0.01F);
    }

    @Test
    void pierceYawNeverLosesTheTarget() {
        List<BanditLine.Body> bodies = List.of(new BanditLine.Body(0, 20, 0.3), new BanditLine.Body(6, 30, 0.3));
        float yaw = BanditLine.pierceYaw(0, 0, 0, bodies, 0, 60, 0.45, 6);
        assertTrue(BanditLine.hits(0, 0, yaw, bodies.get(0), 60, 0.45));
        assertEquals(0.0F, BanditLine.pierceYaw(0, 0, 0, bodies, 5, 60, 0.45, 6), 0.01F, "unknown target: unchanged");
    }

    /** Every move BanditMacroModule makes (by hand from its handlers): none may be refused by the state machine. */
    @Test
    void everyMoveTheModuleMakesIsAllowed() {
        BanditState[][] moves = {
                {BanditState.IDLE, BanditState.SEARCHING},
                {BanditState.SEARCHING, BanditState.TARGET_ACQUIRED}, {BanditState.SEARCHING, BanditState.RECALL_REQUIRED},
                {BanditState.TARGET_ACQUIRED, BanditState.SEARCHING}, {BanditState.TARGET_ACQUIRED, BanditState.AIMING},
                {BanditState.TARGET_ACQUIRED, BanditState.POSITIONING},
                {BanditState.POSITIONING, BanditState.SEARCHING}, {BanditState.POSITIONING, BanditState.AIMING},
                {BanditState.POSITIONING, BanditState.RECOVERING},
                {BanditState.AIMING, BanditState.SEARCHING}, {BanditState.AIMING, BanditState.POSITIONING},
                {BanditState.AIMING, BanditState.READY_TO_THROW},
                {BanditState.READY_TO_THROW, BanditState.AIMING}, {BanditState.READY_TO_THROW, BanditState.THROWING},
                {BanditState.THROWING, BanditState.WAITING_FOR_RETURN}, {BanditState.THROWING, BanditState.RECOVERING},
                {BanditState.WAITING_FOR_RETURN, BanditState.TARGET_RECHECK}, {BanditState.WAITING_FOR_RETURN, BanditState.RECALL_REQUIRED},
                {BanditState.RECALL_REQUIRED, BanditState.TARGET_RECHECK}, {BanditState.RECALL_REQUIRED, BanditState.RECALLING},
                {BanditState.RECALL_REQUIRED, BanditState.RECOVERING},
                {BanditState.RECALLING, BanditState.TARGET_RECHECK}, {BanditState.RECALLING, BanditState.RECOVERING},
                {BanditState.TARGET_RECHECK, BanditState.TARGET_ACQUIRED}, {BanditState.TARGET_RECHECK, BanditState.SEARCHING},
                {BanditState.RETREATING, BanditState.RECALL_REQUIRED}, {BanditState.RETREATING, BanditState.SEARCHING},
                {BanditState.RETREATING, BanditState.RECOVERING},
                {BanditState.RECOVERING, BanditState.RECALL_REQUIRED}, {BanditState.RECOVERING, BanditState.SEARCHING},
                {BanditState.SEARCHING, BanditState.FAILSAFE}, {BanditState.FAILSAFE, BanditState.STOPPED},
        };
        for (BanditState[] m : moves) {
            assertTrue(BanditFsm.allowed(m[0], m[1]), m[0] + " -> " + m[1]);
        }
    }
}
