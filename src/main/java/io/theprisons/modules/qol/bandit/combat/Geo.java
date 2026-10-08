package io.theprisons.modules.qol.bandit.combat;

import io.theprisons.core.control.InputController.Keys;
import io.theprisons.core.control.RotationMath;

/** Small horizontal geometry shared by the combat logic. Minecraft's yaw: 0 looks towards +z, 90 towards -x. */
public final class Geo {
    private Geo() {
    }

    public static double len(double x, double z) {
        return Math.hypot(x, z);
    }

    /** The unit vector of (x, z); (0, 0) stays (0, 0). */
    public static double[] unit(double x, double z) {
        double l = Math.hypot(x, z);
        return l < 1e-9 ? new double[]{0.0D, 0.0D} : new double[]{x / l, z / l};
    }

    /** The horizontal direction a view yaw looks along. */
    public static double[] forward(double yawDegrees) {
        double r = Math.toRadians(yawDegrees);
        return new double[]{-Math.sin(r), Math.cos(r)};
    }

    /** The direction of the player's right hand for a view yaw. */
    public static double[] right(double yawDegrees) {
        double r = Math.toRadians(yawDegrees);
        return new double[]{-Math.cos(r), -Math.sin(r)};
    }

    public static float yawOf(double dx, double dz) {
        return RotationMath.yawOf(dx, dz);
    }

    /** Rotates (x, z) by {@code degrees} (positive = from +x towards +z). */
    public static double[] rotate(double x, double z, double degrees) {
        double r = Math.toRadians(degrees);
        double c = Math.cos(r);
        double s = Math.sin(r);
        return new double[]{x * c - z * s, x * s + z * c};
    }

    /** Smallest angle between two directions in degrees (0..180). */
    public static double angleBetween(double ax, double az, double bx, double bz) {
        double la = Math.hypot(ax, az);
        double lb = Math.hypot(bx, bz);
        if (la < 1e-9 || lb < 1e-9) {
            return 0.0D;
        }
        double dot = (ax * bx + az * bz) / (la * lb);
        return Math.toDegrees(Math.acos(Math.max(-1.0D, Math.min(1.0D, dot))));
    }

    /** The movement keys that move the player along the world direction (dx, dz) given the real view yaw (8 directions). */
    public static Keys keysFor(double dx, double dz, double yawDegrees, boolean sprint, boolean jump) {
        double[] d = unit(dx, dz);
        if (d[0] == 0.0D && d[1] == 0.0D) {
            return new Keys(false, false, false, false, jump, false, false);
        }
        double[] f = forward(yawDegrees);
        double[] r = right(yawDegrees);
        double fwd = d[0] * f[0] + d[1] * f[1];
        double rgt = d[0] * r[0] + d[1] * r[1];
        final double threshold = 0.38D; // 22.5 degrees either side of an axis: 8 directions
        boolean forward = fwd > threshold;
        boolean back = fwd < -threshold;
        boolean right = rgt > threshold;
        boolean left = rgt < -threshold;
        return new Keys(forward, back, left, right, jump, sprint && forward && !right && !left, false);
    }
}
