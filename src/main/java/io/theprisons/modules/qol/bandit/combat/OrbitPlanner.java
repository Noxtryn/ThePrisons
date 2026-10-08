package io.theprisons.modules.qol.bandit.combat;

import java.util.List;

/**
 * Moves the player AROUND the target (never in a circle around itself): the direction of travel is the tangent of the circle around the
 * target, corrected towards the orbit radius, pushed away from other bandits and players. The side (left / right of the facing target)
 * flips when the current side is blocked by a wall, a drop, a hazard or a body - with a minimum time between flips so it cannot
 * flutter, and only to a side that is really better.
 */
public final class OrbitPlanner {
    public enum Dir {
        LEFT, RIGHT;

        Dir other() {
            return this == LEFT ? RIGHT : LEFT;
        }
    }

    /**
     * @param dx / dz unit world direction to move in ((0, 0) when blocked)
     * @param blocked both sides are blocked: no orbit space here
     * @param free probe length on the chosen side
     */
    public record Step(Dir dir, double dx, double dz, boolean blocked, double free, boolean flipped, String note) {
    }

    private Dir dir;
    private long lastFlipMs = Long.MIN_VALUE / 2L;
    private int flips;
    private double lastAngle = Double.NaN;
    private double angleAccumulated;

    public Dir dir() {
        return dir;
    }

    public int flips() {
        return flips;
    }

    /** Degrees travelled around the target since the last {@link #reset()} (absolute sum). */
    public double progressDegrees() {
        return Math.abs(angleAccumulated);
    }

    public void reset() {
        dir = null;
        lastFlipMs = Long.MIN_VALUE / 2L;
        flips = 0;
        lastAngle = Double.NaN;
        angleAccumulated = 0.0D;
    }

    /** The unit tangent for a side: LEFT = to the player's left while facing the target. */
    static double[] tangent(Dir d, double rx, double rz) {
        return d == Dir.LEFT ? new double[]{-rz, rx} : new double[]{rz, -rx};
    }

    private boolean sideBlocked(Terrain terrain, CombatConfig cfg, double x, double y, double z, double[] t, List<Foe> avoid, Terrain.Ray ray) {
        if (ray.blocked(cfg.orbitBlockedFree)) {
            return true;
        }
        double px = x + t[0] * 2.0D;
        double pz = z + t[1] * 2.0D;
        for (Foe f : avoid) {
            if (f.distanceTo(px, pz) < 1.8D) {
                return true;
            }
        }
        return false;
    }

    /**
     * @param ring   the distance to keep
     * @param avoid  other bandits and players (bodies and threats to stay clear of)
     */
    public Step plan(CombatConfig cfg, double x, double y, double z, double tx, double tz, double ring, List<Foe> avoid, Terrain terrain,
                     long nowMs) {
        double[] r = Geo.unit(x - tx, z - tz);
        if (r[0] == 0.0D && r[1] == 0.0D) {
            r = new double[]{1.0D, 0.0D}; // standing exactly on the target: any way out
        }
        double angle = Math.atan2(z - tz, x - tx);
        if (!Double.isNaN(lastAngle)) {
            double d = angle - lastAngle;
            while (d > Math.PI) {
                d -= 2.0D * Math.PI;
            }
            while (d < -Math.PI) {
                d += 2.0D * Math.PI;
            }
            angleAccumulated += Math.toDegrees(d);
        }
        lastAngle = angle;

        double[] tl = tangent(Dir.LEFT, r[0], r[1]);
        double[] tr = tangent(Dir.RIGHT, r[0], r[1]);
        Terrain.Ray rayL = terrain.cast(x, y, z, tl[0], tl[1], cfg.orbitStep);
        Terrain.Ray rayR = terrain.cast(x, y, z, tr[0], tr[1], cfg.orbitStep);
        boolean blockedL = sideBlocked(terrain, cfg, x, y, z, tl, avoid, rayL);
        boolean blockedR = sideBlocked(terrain, cfg, x, y, z, tr, avoid, rayR);
        boolean flipped = false;
        String note = "";
        if (dir == null) {
            // First step: the side with more room (the one away from other bodies when equal), LEFT when nothing decides.
            if (blockedL != blockedR) {
                dir = blockedL ? Dir.RIGHT : Dir.LEFT;
            } else if (Math.abs(rayL.free() - rayR.free()) > 0.5D) {
                dir = rayL.free() >= rayR.free() ? Dir.LEFT : Dir.RIGHT;
            } else {
                dir = nearerBodies(avoid, x + tl[0] * 3.0D, z + tl[1] * 3.0D) <= nearerBodies(avoid, x + tr[0] * 3.0D, z + tr[1] * 3.0D) ? Dir.RIGHT : Dir.LEFT;
            }
            note = "first side";
        } else {
            boolean currentBlocked = dir == Dir.LEFT ? blockedL : blockedR;
            boolean otherBlocked = dir == Dir.LEFT ? blockedR : blockedL;
            double currentFree = dir == Dir.LEFT ? rayL.free() : rayR.free();
            double otherFree = dir == Dir.LEFT ? rayR.free() : rayL.free();
            boolean fullyStuck = currentFree < 0.8D;
            if (currentBlocked && !otherBlocked && otherFree > currentFree + 1.0D
                    && (fullyStuck || nowMs - lastFlipMs >= cfg.flipMinMs)) {
                dir = dir.other();
                lastFlipMs = nowMs;
                flips++;
                flipped = true;
                note = "side blocked, flipped";
            }
        }
        boolean blocked = blockedL && blockedR;
        double[] t = dir == Dir.LEFT ? tl : tr;
        Terrain.Ray ray = dir == Dir.LEFT ? rayL : rayR;
        if (blocked) {
            return new Step(dir, 0.0D, 0.0D, true, Math.max(rayL.free(), rayR.free()), flipped, "both sides blocked");
        }
        boolean chosenBlocked = dir == Dir.LEFT ? blockedL : blockedR;
        if (chosenBlocked) {
            // The other side was not good enough to flip to: stay put rather than walk into a wall.
            return new Step(dir, 0.0D, 0.0D, false, ray.free(), flipped, "side blocked, waiting to flip");
        }

        double vx = t[0];
        double vz = t[1];
        // Keep the radius: towards the target when too far, away when too close (probed first).
        double error = Math.hypot(x - tx, z - tz) - ring;
        if (Math.abs(error) > cfg.ringTolerance) {
            double k = Math.max(-1.0D, Math.min(1.0D, error / 6.0D));
            double rx = -r[0] * k;
            double rz = -r[1] * k;
            Terrain.Ray radial = terrain.cast(x, y, z, rx, rz, 2.0D);
            if (!radial.blocked(1.0D)) {
                vx += rx;
                vz += rz;
            }
        }
        // Stay clear of other bandits and players close by.
        for (Foe f : avoid) {
            double dx = x - f.x();
            double dz = z - f.z();
            double d = Math.hypot(dx, dz);
            if (d < 8.0D && d > 0.01D) {
                double w = Math.min(1.5D, 3.0D / d);
                vx += dx / d * w;
                vz += dz / d * w;
            }
        }
        double[] v = Geo.unit(vx, vz);
        return new Step(dir, v[0], v[1], false, ray.free(), flipped, note);
    }

    private static double nearerBodies(List<Foe> foes, double px, double pz) {
        double nearest = Double.MAX_VALUE;
        for (Foe f : foes) {
            nearest = Math.min(nearest, f.distanceTo(px, pz));
        }
        return nearest;
    }
}
