package com.freelocs.theprisons.modules.qol.bandit;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SpearBallisticsTest {
    @Test
    void closeTargetIsAboutLevel() {
        var s = SpearBallistics.solve(0, 0, 0, 0, 0, 5, 0, 0, 0, 2.5, 0.05, 0.99);
        assertNotNull(s);
        assertTrue(s.pitch() < 0 && s.pitch() > -3, "slightly up: " + s.pitch());
        assertEquals(0.0F, s.yaw(), 0.01F);
    }

    @Test
    void farTargetNeedsMoreArc() {
        var near = SpearBallistics.solve(0, 0, 0, 0, 0, 10, 0, 0, 0, 2.5, 0.05, 0.99);
        var far = SpearBallistics.solve(0, 0, 0, 0, 0, 40, 0, 0, 0, 2.5, 0.05, 0.99);
        assertTrue(far.pitch() < near.pitch());
    }

    @Test
    void leadsAMovingTarget() {
        var s = SpearBallistics.solve(0, 0, 0, 10, 0, 10, 0.2, 0, 0, 2.5, 0.05, 0.99);
        var still = SpearBallistics.solve(0, 0, 0, 10, 0, 10, 0, 0, 0, 2.5, 0.05, 0.99);
        assertTrue(s.aimX() > 10.5);
        assertTrue(s.yaw() < still.yaw(), "turns towards +x (yaw negative)");
    }

    @Test
    void outOfRangeIsNull() {
        assertNull(SpearBallistics.solve(0, 0, 0, 0, 0, 500, 0, 0, 0, 2.5, 0.05, 0.99));
    }
}
