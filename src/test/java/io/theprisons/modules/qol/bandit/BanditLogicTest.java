package io.theprisons.modules.qol.bandit;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BanditLogicTest {
    // ── identification ──

    @Test
    void banditNames() {
        assertTrue(BanditScan.isBanditName("bandit_ae_821e4c"));
        assertTrue(BanditScan.isBanditName("Bandit_0F_00ab12"));
        assertFalse(BanditScan.isBanditName("bandit_xyz"));
        assertFalse(BanditScan.isBanditName("Steve"));
        assertFalse(BanditScan.isBanditName("guard_452_e3d1c7"));
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
}
