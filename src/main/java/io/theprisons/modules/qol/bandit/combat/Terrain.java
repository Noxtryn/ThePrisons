package io.theprisons.modules.qol.bandit.combat;

/**
 * The ground around the player, answered by short horizontal probes (no navmesh). A probe walks from a point along a direction in
 * half-block steps and says how far a player could walk and what stopped it. Implemented over the world cache in the module and by a
 * character grid in the tests.
 */
public interface Terrain {
    enum Stop {
        /** Nothing in the way up to the probe length. */
        CLEAR,
        /** A wall (block in the way that cannot be stepped onto). */
        WALL,
        /** A step too high to walk up (more than a jump). */
        STEP,
        /** The ceiling is too low. */
        HEADROOM,
        /** A drop of more than the allowed height. */
        DROP,
        /** Lava, fire, water ... */
        HAZARD,
        /** The blocks there are not known to the client. */
        UNKNOWN
    }

    /**
     * @param free         blocks that can be walked before {@code stop}
     * @param heightChange net change of the feet height at the end
     * @param jumpAt       blocks until the first step up that needs a jump (the landing is a valid floor); -1 = no jump needed on this probe
     */
    record Ray(double free, Stop stop, double heightChange, double jumpAt) {
        public Ray(double free, Stop stop, double heightChange) {
            this(free, stop, heightChange, -1.0D);
        }

        public boolean needsJump() {
            return jumpAt >= 0.0D;
        }

        public boolean clear(double needed) {
            return free >= needed || stop == Stop.CLEAR && free >= needed - 0.01D;
        }

        /** True when walking on is not an option (a wall, a drop ...), not just a short probe. */
        public boolean blocked(double needed) {
            return free < needed && stop != Stop.CLEAR;
        }
    }

    /** Probes from feet position (x, y, z) in the horizontal direction (dirX, dirZ) (need not be unit length) for {@code maxDist} blocks. */
    Ray cast(double x, double y, double z, double dirX, double dirZ, double maxDist);
}
