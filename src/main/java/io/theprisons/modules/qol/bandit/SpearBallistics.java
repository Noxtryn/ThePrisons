package io.theprisons.modules.qol.bandit;

import org.jspecify.annotations.Nullable;

/**
 * Flight of a thrown spear (trident-like: per tick move, drag, then gravity) and the aim that hits a moving target.
 * Pure maths, no Minecraft classes.
 */
public final class SpearBallistics {
    /** The direction to throw in and how long the spear is in the air. Pitch is Minecraft's (positive = down). */
    public record Solution(float yaw, float pitch, double ticks, double aimX, double aimY, double aimZ) {
    }

    private static final int MAX_TICKS = 200;

    private SpearBallistics() {
    }

    /** Height of the spear when it has flown {@code dist} blocks horizontally; NaN when it never gets there. */
    static double heightAt(double speed, double elevationDeg, double dist, double gravity, double drag, double[] ticksOut) {
        double rad = Math.toRadians(elevationDeg);
        double vx = speed * Math.cos(rad);
        double vy = speed * Math.sin(rad);
        double x = 0.0D;
        double y = 0.0D;
        for (int t = 1; t <= MAX_TICKS; t++) {
            double nx = x + vx;
            double ny = y + vy;
            if (nx >= dist) {
                double f = vx <= 1.0E-9D ? 0.0D : (dist - x) / (nx - x);
                if (ticksOut != null) {
                    ticksOut[0] = t - 1 + f;
                }
                return y + (ny - y) * f;
            }
            x = nx;
            y = ny;
            vx *= drag;
            vy = vy * drag - gravity;
            if (vx < 1.0E-4D) {
                break;
            }
        }
        return Double.NaN;
    }

    /**
     * @param origin throw position (eye); @param target target's aim point; @param targetVel its velocity in blocks/tick
     * @return null when the target is out of reach
     */
    public static @Nullable Solution solve(double ox, double oy, double oz, double tx, double ty, double tz,
                                           double tvx, double tvy, double tvz, double speed, double gravity, double drag) {
        double t = 0.0D;
        double ax = tx;
        double ay = ty;
        double az = tz;
        double[] flight = new double[1];
        double elevation = 0.0D;
        for (int i = 0; i < 5; i++) {
            ax = tx + tvx * t;
            ay = ty + tvy * t;
            az = tz + tvz * t;
            double dx = ax - ox;
            double dz = az - oz;
            double dist = Math.sqrt(dx * dx + dz * dz);
            double dy = ay - oy;
            if (dist < 0.5D) {
                return null;
            }
            double lo = -80.0D;
            double hi = 55.0D;
            double fl = heightAt(speed, lo, dist, gravity, drag, null);
            double fh = heightAt(speed, hi, dist, gravity, drag, null);
            if (Double.isNaN(fh) || (!Double.isNaN(fl) && fl > dy) || fh < dy) {
                return null;
            }
            for (int k = 0; k < 40; k++) {
                double mid = (lo + hi) * 0.5D;
                double h = heightAt(speed, mid, dist, gravity, drag, null);
                if (Double.isNaN(h) || h < dy) {
                    lo = mid;
                } else {
                    hi = mid;
                }
            }
            elevation = (lo + hi) * 0.5D;
            if (Double.isNaN(heightAt(speed, elevation, dist, gravity, drag, flight))) {
                return null;
            }
            t = flight[0];
        }
        double yaw = Math.toDegrees(Math.atan2(-(ax - ox), az - oz));
        return new Solution((float) yaw, (float) -elevation, t, ax, ay, az);
    }
}
