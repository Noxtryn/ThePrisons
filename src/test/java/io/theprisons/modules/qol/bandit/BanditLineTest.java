package io.theprisons.modules.qol.bandit;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class BanditLineTest {
    private static BanditLine.Body b(double x, double z) {
        return new BanditLine.Body(x, z, 0.3D);
    }

    @Test
    void countsAllInALine() {
        var bodies = List.of(b(0, 5), b(0, 9), b(0, 14), b(6, 8));
        assertEquals(3, BanditLine.countRay(0, 0, 0, bodies, 30, 0.4));
    }

    @Test
    void bestYawFindsTheLine() {
        // three along +x diagonal-ish: yaw -90 is +x
        var bodies = List.of(b(5, 0.2), b(9, -0.1), b(14, 0.0), b(0, 8));
        var best = BanditLine.best(0, 0, -80, bodies, 30, 0.4, 20);
        assertEquals(3, best.count());
        assertEquals(-90.0F, best.yaw(), 1.5F);
        assertTrue(best.currentCount() < 3);
    }

    @Test
    void segmentCountsBetweenSpearAndPlayer() {
        var bodies = List.of(b(0, 5), b(0, 12), b(0, 40), b(8, 6));
        assertEquals(2, BanditLine.countSegment(0, 0, 0, 20, bodies, 0.5));
    }

    @Test
    void plannerFiresAtDeadlineAndOnFullLine() {
        var p = new RecallPlanner(3000, 2, 250);
        p.start(0);
        assertFalse(p.update(100, 3, 3, 3));
        assertTrue(p.update(300, 3, 3, 3));      // everyone is on the line
        p.start(0);
        assertFalse(p.update(500, 1, 1, 4));
        assertTrue(p.update(3000, 0, 0, 4));     // deadline
        p.start(0);
        assertFalse(p.update(400, 2, 2, 5));
        assertTrue(p.update(600, 2, 1, 5));      // peak, about to get worse
    }
}
