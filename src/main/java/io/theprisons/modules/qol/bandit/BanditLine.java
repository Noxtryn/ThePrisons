package io.theprisons.modules.qol.bandit;

import java.util.List;

/**
 * Where bandits stand in a line (horizontal geometry, pure maths). A spear goes through everything on its path, so the
 * best direction is the one with the most bandits inside the corridor around the ray; the same counts for the way back
 * (the segment from the spear to the player).
 */
public final class BanditLine {
    /** A bandit's position (feet) and half width. */
    public record Body(double x, double z, double radius) {
    }

    /** The best direction found: its yaw, how many it hits, and how many the current direction hits. */
    public record Best(float yaw, int count, int currentCount) {
    }

    private BanditLine() {
    }

    /** Bandits on the ray from (ox, oz) in direction {@code yawDeg} (Minecraft yaw: 0 = +z) up to {@code range}. */
    public static int countRay(double ox, double oz, double yawDeg, List<Body> bodies, double range, double margin) {
        double rad = Math.toRadians(yawDeg);
        double dx = -Math.sin(rad);
        double dz = Math.cos(rad);
        int count = 0;
        for (Body b : bodies) {
            double vx = b.x() - ox;
            double vz = b.z() - oz;
            double t = vx * dx + vz * dz;
            if (t < 0.8D || t > range) {
                continue;
            }
            if (Math.abs(vx * dz - vz * dx) <= b.radius() + margin) {
                count++;
            }
        }
        return count;
    }

    /** Index of the nearest bandit on the ray, -1 when none. */
    public static int nearestOnRay(double ox, double oz, double yawDeg, List<Body> bodies, double range, double margin) {
        double rad = Math.toRadians(yawDeg);
        double dx = -Math.sin(rad);
        double dz = Math.cos(rad);
        int best = -1;
        double bestT = Double.MAX_VALUE;
        for (int i = 0; i < bodies.size(); i++) {
            Body b = bodies.get(i);
            double vx = b.x() - ox;
            double vz = b.z() - oz;
            double t = vx * dx + vz * dz;
            if (t >= 0.8D && t <= range && t < bestT && Math.abs(vx * dz - vz * dx) <= b.radius() + margin) {
                bestT = t;
                best = i;
            }
        }
        return best;
    }

    /** Bandits within {@code margin} of the segment a-b (the spear's way back). */
    public static int countSegment(double ax, double az, double bx, double bz, List<Body> bodies, double margin) {
        double sx = bx - ax;
        double sz = bz - az;
        double len2 = sx * sx + sz * sz;
        int count = 0;
        for (Body b : bodies) {
            double t = len2 < 1.0E-9D ? 0.0D : Math.max(0.0D, Math.min(1.0D, ((b.x() - ax) * sx + (b.z() - az) * sz) / len2));
            double px = ax + sx * t - b.x();
            double pz = az + sz * t - b.z();
            if (Math.sqrt(px * px + pz * pz) <= b.radius() + margin) {
                count++;
            }
        }
        return count;
    }

    /**
     * The direction within ±{@code window}° of {@code yawDeg} that hits the most bandits; of equally good ones the
     * middle of the plateau nearest to the current direction (the "perfect spot").
     */
    public static Best best(double ox, double oz, double yawDeg, List<Body> bodies, double range, double margin, double window) {
        double step = 0.5D;
        int n = (int) Math.round(window / step);
        int[] counts = new int[2 * n + 1];
        int max = 0;
        for (int i = -n; i <= n; i++) {
            counts[i + n] = countRay(ox, oz, yawDeg + i * step, bodies, range, margin);
            max = Math.max(max, counts[i + n]);
        }
        int current = counts[n];
        if (max == 0) {
            return new Best((float) yawDeg, 0, 0);
        }
        int bestStart = -1;
        int bestEnd = -1;
        double bestDist = Double.MAX_VALUE;
        int i = 0;
        while (i < counts.length) {
            if (counts[i] != max) {
                i++;
                continue;
            }
            int j = i;
            while (j + 1 < counts.length && counts[j + 1] == max) {
                j++;
            }
            double centre = (i + j) / 2.0D - n;
            if (Math.abs(centre) < bestDist) {
                bestDist = Math.abs(centre);
                bestStart = i;
                bestEnd = j;
            }
            i = j + 1;
        }
        double centre = (bestStart + bestEnd) / 2.0D - n;
        return new Best((float) (yawDeg + centre * step), max, current);
    }

    /**
     * The direction within ±{@code window}° of {@code baseYaw} (towards bandit {@code target}) that still hits that bandit
     * and as many others as possible - the spear pierces everything on its path. Of equally good directions the one
     * nearest to {@code baseYaw}. Returns {@code baseYaw} when nothing is better.
     */
    public static float pierceYaw(double ox, double oz, double baseYaw, List<Body> bodies, int target, double range,
                                  double margin, double window) {
        if (target < 0 || target >= bodies.size()) {
            return (float) baseYaw;
        }
        Body t = bodies.get(target);
        double step = 0.5D;
        int n = (int) Math.round(window / step);
        int bestCount = -1;
        double bestYaw = baseYaw;
        double bestDist = Double.MAX_VALUE;
        for (int i = -n; i <= n; i++) {
            double yaw = baseYaw + i * step;
            if (!hits(ox, oz, yaw, t, range, margin)) {
                continue;
            }
            int count = countRay(ox, oz, yaw, bodies, range, margin);
            double dist = Math.abs(i * step);
            if (count > bestCount || count == bestCount && dist < bestDist) {
                bestCount = count;
                bestYaw = yaw;
                bestDist = dist;
            }
        }
        return (float) bestYaw;
    }

    /** Whether the ray from (ox, oz) at {@code yawDeg} passes through bandit {@code b}. */
    public static boolean hits(double ox, double oz, double yawDeg, Body b, double range, double margin) {
        double rad = Math.toRadians(yawDeg);
        double dx = -Math.sin(rad);
        double dz = Math.cos(rad);
        double vx = b.x() - ox;
        double vz = b.z() - oz;
        double t = vx * dx + vz * dz;
        return t >= 0.8D && t <= range && Math.abs(vx * dz - vz * dx) <= b.radius() + margin;
    }
}
