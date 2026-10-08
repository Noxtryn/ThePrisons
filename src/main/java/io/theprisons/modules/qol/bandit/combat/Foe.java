package io.theprisons.modules.qol.bandit.combat;

/**
 * A bandit or a player as the combat controller sees it (read from the Cosmic state store, never from the world).
 *
 * @param kind   the classifier's name ("ORE_BANDIT", "BOSS", "SPECIAL", "ELITE" ...) or "PLAYER"
 * @param health current health, NaN when not known
 * @param sight  whether there is a free line to it (computed by the module for the few foes that matter; UNKNOWN otherwise)
 */
public record Foe(String id, String kind, double x, double y, double z, double vx, double vz, double health, double maxHealth, Sight sight) {
    public enum Sight { YES, NO, UNKNOWN }

    public double distanceTo(double px, double pz) {
        return Math.hypot(x - px, z - pz);
    }

    public boolean player() {
        return "PLAYER".equals(kind);
    }

    public Foe withSight(Sight s) {
        return new Foe(id, kind, x, y, z, vx, vz, health, maxHealth, s);
    }
}
