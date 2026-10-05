package io.theprisons.modules.mining.ore.route;

import io.theprisons.core.nav.NavigationPath;

/**
 * The corridor around one route segment (waypoint a → waypoint b): the tunnel steering may move up to {@link #NEAR}
 * blocks left or right of the line where there is ore, and up to {@link #WIDTH} blocks when the way on the line has no
 * or little ore - freedom, not a duty; it never goes further.
 */
public final class RouteCorridor {
    /** Free movement beside the route line (blocks): the most it ever goes aside. */
    public static final double WIDTH = 10.0D;
    /** Lanes this close to the line are always looked at; further out only when the way has little ore. */
    public static final double NEAR = 5.0D;
    /** A path may cut this much wider than the corridor (corners, stairs) before it counts as an own way. */
    static final double PATH_SLACK = 1.5D;

    private RouteCorridor() {
    }

    /** {progress along a→b, sideways distance, segment length} of the point (x, z); block centres for a and b. */
    public static double[] project(int[] a, int[] b, double x, double z) {
        double ax = a[0] + 0.5D;
        double az = a[2] + 0.5D;
        double dx = b[0] + 0.5D - ax;
        double dz = b[2] + 0.5D - az;
        double len = Math.hypot(dx, dz);
        if (len < 1.0E-6D) {
            return new double[]{0.0D, Math.hypot(x - ax, z - az), 0.0D};
        }
        double ux = dx / len;
        double uz = dz / len;
        double px = x - ax;
        double pz = z - az;
        return new double[]{px * ux + pz * uz, Math.abs(px * uz - pz * ux), len};
    }

    /** Sideways offset of (x, z) from the line a→b: positive = left of the walking direction, negative = right. */
    public static double side(int[] a, int[] b, double x, double z) {
        double ax = a[0] + 0.5D;
        double az = a[2] + 0.5D;
        double dx = b[0] + 0.5D - ax;
        double dz = b[2] + 0.5D - az;
        double len = Math.hypot(dx, dz);
        if (len < 1.0E-6D) {
            return 0.0D;
        }
        return ((x - ax) * dz - (z - az) * dx) / len;
    }

    /** Whether the path leaves the corridor of a→b (an own way the macro must not take). */
    public static boolean leaves(NavigationPath path, int[] a, int[] b) {
        for (int i = 0; i < path.size(); i++) {
            double[] q = project(a, b, path.x(i), path.z(i));
            if (q[1] > WIDTH + PATH_SLACK || q[0] < -WIDTH || q[0] > q[2] + WIDTH) {
                return true;
            }
        }
        return false;
    }
}
