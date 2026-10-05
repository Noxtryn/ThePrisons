package io.theprisons.modules.mining.ore;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TerrainPitchTest {
    private static final double SAMPLE = 0.5D;
    private static final double RANGE = 12.0D;

    /**
     * One tick on stairs: the first step {@code firstStep} blocks ahead, then a step every {@code tread} blocks,
     * {@code steps} steps in direction {@code dir} (+1 up, -1 down), flat after that.
     */
    private static TerrainPitch.Ground tick(TerrainPitch pitch, double feet, double firstStep, int tread, int steps, int dir) {
        int n = (int) (RANGE / SAMPLE);
        double[] heights = new double[n];
        double[] dist = new double[n];
        for (int i = 0; i < n; i++) {
            double d = (i + 1) * SAMPLE;
            int done = d < firstStep ? 0 : Math.min(steps, (int) Math.floor((d - firstStep) / tread) + 1);
            heights[i] = feet + dir * done;
            dist[i] = d;
        }
        return pitch.update(feet, true, heights, dist);
    }

    private static TerrainPitch.Ground flat(TerrainPitch pitch) {
        return tick(pitch, 10, RANGE + 1, 1, 0, 1);
    }

    @Test
    void straightOnIs46() {
        TerrainPitch pitch = new TerrainPitch();
        assertEquals(TerrainPitch.Ground.FLAT, flat(pitch));
        assertEquals(46.0F, pitch.pitch());
    }

    @Test
    void angleChangesOnlyPointEightBeforeTheStep() {
        TerrainPitch pitch = new TerrainPitch();
        assertEquals(TerrainPitch.Ground.FLAT, tick(pitch, 10, 1.5, 1, 4, -1), "1.5 blocks before: not yet");
        assertEquals(TerrainPitch.Ground.TREAD_1, tick(pitch, 10, 0.5, 1, 4, -1), "0.5 blocks before: stairs");
        assertEquals(82.0F, pitch.pitch());
    }

    @Test
    void treadLengthPicksTheAngleDownAndUp() {
        for (int dir : new int[]{-1, 1}) {
            for (int[] c : new int[][]{{1, 82}, {2, 74}, {3, 68}}) {
                TerrainPitch pitch = new TerrainPitch();
                tick(pitch, 10, 0.5, c[0], 4, dir);
                assertEquals((float) c[1], pitch.pitch(), "tread " + c[0] + " dir " + dir);
                // On the stairs, between two steps: the angle is held.
                assertEquals((float) c[1], tick(pitch, 10, c[0] - 0.3, c[0], 3, dir).pitch(), "held, tread " + c[0]);
            }
        }
    }

    @Test
    void lastStepUpLooksDownBrieflyThenFlat() {
        TerrainPitch pitch = new TerrainPitch();
        tick(pitch, 10, 0.5, 3, 4, 1);
        assertEquals(68.0F, pitch.pitch());
        // The last step: 4+ flat blocks after it.
        assertEquals(TerrainPitch.Ground.SETTLE, tick(pitch, 10, 0.6, 3, 1, 1));
        assertEquals(82.0F, pitch.pitch());
        // Airborne on the step: kept.
        assertEquals(TerrainPitch.Ground.SETTLE, pitch.update(10.4, false, new double[]{11}, new double[]{0.5}));
        for (int i = 0; i < TerrainPitch.SETTLE_TICKS; i++) {
            flat(pitch);
        }
        assertEquals(TerrainPitch.Ground.FLAT, flat(pitch));
        assertEquals(46.0F, pitch.pitch());
    }

    @Test
    void lastStepDownGoesStraightToFlat() {
        TerrainPitch pitch = new TerrainPitch();
        tick(pitch, 10, 0.5, 2, 4, -1);
        assertEquals(TerrainPitch.Ground.FLAT, tick(pitch, 10, 0.6, 2, 1, -1));
    }

    @Test
    void singleStepUpOnAFlatWayIsTheShortLookDown() {
        TerrainPitch pitch = new TerrainPitch();
        assertEquals(TerrainPitch.Ground.SETTLE, tick(pitch, 10, 0.7, 1, 1, 1));
    }

    @Test
    void wayOffTheStairsIsFlatAgain() {
        TerrainPitch pitch = new TerrainPitch();
        tick(pitch, 10, 0.5, 1, 4, -1);
        assertEquals(TerrainPitch.Ground.TREAD_1, pitch.state());
        assertEquals(TerrainPitch.Ground.FLAT, flat(pitch), "no step within 4 blocks");
    }

    @Test
    void slabHighBumpIsFlat() {
        TerrainPitch pitch = new TerrainPitch();
        assertEquals(TerrainPitch.Ground.FLAT, pitch.update(10, true, new double[]{10.5, 10.5}, new double[]{0.5, 1.0}));
    }
}
