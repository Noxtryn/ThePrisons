package io.theprisons.modules.mining.ore;

/**
 * How far down the view looks, from the stairs on the path ahead (pure logic, one call per tick). Up and down are
 * treated the same; what counts is how many flat blocks lie between two steps (the tread):
 *
 * <table>
 *     <tr><td>straight on (4+ flat blocks)</td><td>{@value #FLAT}°</td></tr>
 *     <tr><td>stairs, a step every block</td><td>{@value #TREAD_1}°</td></tr>
 *     <tr><td>stairs, 2 flat blocks between the steps</td><td>{@value #TREAD_2}°</td></tr>
 *     <tr><td>stairs, 3 flat blocks between the steps</td><td>{@value #TREAD_3}°</td></tr>
 *     <tr><td>the last step up before 4+ flat blocks</td><td>{@value #SETTLE}° for {@value #SETTLE_TICKS} ticks (at
 *     least until on top), then straight to flat</td></tr>
 * </table>
 * The angle changes {@value #LEAD} blocks before each step, to the angle of the tread after it. The change itself is
 * smoothed by the view motion ({@code HumanRotation}).
 */
public final class TerrainPitch {
    public enum Ground {
        FLAT(TerrainPitch.FLAT), TREAD_1(TerrainPitch.TREAD_1), TREAD_2(TerrainPitch.TREAD_2),
        TREAD_3(TerrainPitch.TREAD_3), SETTLE(TerrainPitch.SETTLE);

        private final float pitch;

        Ground(float pitch) {
            this.pitch = pitch;
        }

        public float pitch() {
            return pitch;
        }
    }

    public static final float FLAT = 46.0F;
    public static final float TREAD_1 = 82.0F;
    public static final float TREAD_2 = 74.0F;
    public static final float TREAD_3 = 68.0F;
    public static final float SETTLE = 82.0F;
    public static final int SETTLE_TICKS = 8;
    /** The angle changes this far (blocks, horizontal) before a step. */
    public static final double LEAD = 0.8D;
    /** A tread this long (blocks) or longer is straight on, not stairs. */
    public static final int FLAT_TREAD = 4;
    /** Height difference that counts as a step (a slab-high bump does not). */
    private static final double STEP = 0.5D;
    /** The path is sampled every half block; a tread measured between two samples is up to this much off. */
    private static final double SAMPLE = 0.5D;

    private Ground state = Ground.FLAT;
    private int settle;

    public Ground state() {
        return state;
    }

    public float pitch() {
        return state.pitch();
    }

    public void reset() {
        state = Ground.FLAT;
        settle = 0;
    }

    /**
     * @param feet      the player's feet height
     * @param onGround  false while jumping / falling (the current angle is kept then)
     * @param aheadFeet feet heights of the path ahead, nearest first
     * @param aheadDist their horizontal distances from the player
     */
    public Ground update(double feet, boolean onGround, double[] aheadFeet, double[] aheadDist) {
        if (settle > 0) {
            settle--;
        }
        if (!onGround) {
            return state;
        }
        int step = nextStep(feet, aheadFeet, 0);
        // The edge lies between this sample and the one before it.
        double stepAt = step < 0 ? Double.POSITIVE_INFINITY : aheadDist[step] - SAMPLE * 0.5D;
        if (state == Ground.SETTLE) {
            // The short look down on the last step: held until on top, then straight to flat.
            if (settle > 0 || stepAt <= LEAD) {
                return state;
            }
            state = Ground.FLAT;
        }
        if (stepAt <= LEAD) {
            int tread = tread(aheadFeet, aheadDist, step);
            boolean up = aheadFeet[step] > feet;
            if (tread >= FLAT_TREAD) {
                if (up) {
                    state = Ground.SETTLE;
                    settle = SETTLE_TICKS;
                } else {
                    state = Ground.FLAT;
                }
            } else {
                state = ofTread(tread);
            }
        } else if (stepAt > FLAT_TREAD - SAMPLE) {
            // No step on this tread: straight on (also when the way turned off the stairs).
            state = Ground.FLAT;
        }
        return state;
    }

    /** The first sample from {@code from} on that is a step (up or down) away from {@code height}, or -1. */
    private static int nextStep(double height, double[] aheadFeet, int from) {
        for (int i = from; i < aheadFeet.length; i++) {
            if (Math.abs(aheadFeet[i] - height) > STEP) {
                return i;
            }
        }
        return -1;
    }

    /** Whole flat blocks after the step at sample {@code step}, up to the next step (or as far as the path is known). */
    private static int tread(double[] aheadFeet, double[] aheadDist, int step) {
        int next = nextStep(aheadFeet[step], aheadFeet, step + 1);
        double length = next < 0
                ? aheadDist[aheadDist.length - 1] - aheadDist[step] + SAMPLE
                : aheadDist[next] - aheadDist[step];
        return Math.max(1, (int) Math.round(length - 0.25D));
    }

    private static Ground ofTread(int tread) {
        return switch (tread) {
            case 1 -> Ground.TREAD_1;
            case 2 -> Ground.TREAD_2;
            default -> Ground.TREAD_3;
        };
    }
}
