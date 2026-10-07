package io.theprisons.modules.qol.bandit;

import java.util.List;

/**
 * How dangerous the other players around are (pure logic). Another real player is a PvP risk: far away nothing, inside the
 * safety radius or closing in fast a reason to back off, very close (or health critical) a reason to leave.
 */
public final class BanditDanger {
    public enum Level { NONE, CAUTION, CRITICAL }

    /** @param closing blocks per tick the player gets closer (negative = moving away) */
    public record Contact(String name, double distance, double closing) {
    }

    public record Verdict(Level level, String why, String name) {
        static final Verdict SAFE = new Verdict(Level.NONE, "", "");
    }

    private BanditDanger() {
    }

    public static Verdict evaluate(List<Contact> contacts, double safetyRadius, double health, double criticalHealth) {
        if (health <= criticalHealth) {
            return new Verdict(Level.CRITICAL, "HEALTH_CRITICAL", "");
        }
        Contact nearest = null;
        int inside = 0;
        for (Contact c : contacts) {
            if (c.distance() <= safetyRadius) {
                inside++;
            }
            if (nearest == null || c.distance() < nearest.distance()) {
                nearest = c;
            }
        }
        if (nearest == null) {
            return Verdict.SAFE;
        }
        if (nearest.distance() <= safetyRadius * 0.4D) {
            return new Verdict(Level.CRITICAL, "PLAYER_VERY_CLOSE", nearest.name());
        }
        if (inside >= 3) {
            return new Verdict(Level.CRITICAL, "CROWD", nearest.name());
        }
        if (nearest.distance() <= safetyRadius) {
            return new Verdict(Level.CAUTION, "PLAYER_IN_RADIUS", nearest.name());
        }
        for (Contact c : contacts) {
            // Coming fast: closer than 1.5 radii and gaining 0.2+ blocks per tick (sprinting is 0.28).
            if (c.distance() <= safetyRadius * 1.5D && c.closing() >= 0.2D) {
                return new Verdict(Level.CAUTION, "PLAYER_APPROACHING", c.name());
            }
        }
        return Verdict.SAFE;
    }
}
