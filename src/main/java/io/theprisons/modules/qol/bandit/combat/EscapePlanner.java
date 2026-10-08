package io.theprisons.modules.qol.bandit.combat;

import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 * Where to go to get out of danger: 16 directions around the player are probed on the ground, directions into walls, drops and hazards
 * are dropped, and the best is the one with the most free space that leads away from the threats. Cornered = no direction.
 */
public final class EscapePlanner {
    public static final int DIRECTIONS = 16;
    public static final double LOOK = 8.0D;
    public static final double MIN_FREE = 2.0D;

    /** @param dx / dz unit direction; @param free blocks of free walk; @param score for logs */
    public record Escape(double dx, double dz, double free, double score, Terrain.Stop stop) {
    }

    private EscapePlanner() {
    }

    /** The 16 probes (index i = direction i * 22.5 degrees from +x), also used as the "free space" summary. */
    public static Terrain.Ray[] scan(Terrain terrain, double x, double y, double z, double look) {
        Terrain.Ray[] rays = new Terrain.Ray[DIRECTIONS];
        for (int i = 0; i < DIRECTIONS; i++) {
            double a = Math.toRadians(i * 360.0D / DIRECTIONS);
            rays[i] = terrain.cast(x, y, z, Math.cos(a), Math.sin(a), look);
        }
        return rays;
    }

    public static @Nullable Escape best(Terrain terrain, double x, double y, double z, List<Foe> threats, double look) {
        double awayX = 0.0D;
        double awayZ = 0.0D;
        for (Foe f : threats) {
            double dx = x - f.x();
            double dz = z - f.z();
            double d2 = Math.max(1.0D, dx * dx + dz * dz);
            awayX += dx / d2;
            awayZ += dz / d2;
        }
        double[] away = Geo.unit(awayX, awayZ);
        Escape best = null;
        Terrain.Ray[] rays = scan(terrain, x, y, z, look);
        for (int i = 0; i < DIRECTIONS; i++) {
            Terrain.Ray ray = rays[i];
            if (ray.free() < MIN_FREE) {
                continue;
            }
            double a = Math.toRadians(i * 360.0D / DIRECTIONS);
            double dx = Math.cos(a);
            double dz = Math.sin(a);
            double score = Math.min(ray.free(), look) + 6.0D * (dx * away[0] + dz * away[1]);
            if (ray.stop() == Terrain.Stop.DROP) {
                score -= 3.0D;
            } else if (ray.stop() == Terrain.Stop.HAZARD) {
                score -= 6.0D;
            } else if (ray.stop() == Terrain.Stop.UNKNOWN) {
                score -= 4.0D;
            }
            if (best == null || score > best.score() + 1e-9) {
                best = new Escape(dx, dz, ray.free(), score, ray.stop());
            }
        }
        return best;
    }

    /** How many of the 16 directions are blocked within {@code needed} blocks (wall / corner pressure). */
    public static int blockedDirections(Terrain.Ray[] rays, double needed) {
        int n = 0;
        for (Terrain.Ray ray : rays) {
            if (ray.blocked(needed)) {
                n++;
            }
        }
        return n;
    }
}
