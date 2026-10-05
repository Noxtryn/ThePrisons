package com.freelocs.theprisons.modules.mining.ore;

import com.freelocs.theprisons.core.nav.NavigationPath;

import java.util.ArrayList;
import java.util.List;

/**
 * Circular areas the Ore Macro never walks into or mines in (hand-placed borders, see {@link BorderMarks}).
 *
 * <p>A player already inside an area may only walk away from its centre, never deeper in, so an area set right next
 * to the player turns the macro around instead of trapping it.</p>
 */
public final class BorderZones {
    /** An area this much higher / lower does not count (another floor of the cave). */
    static final double HEIGHT = 8.0D;

    /** {x, y, z, radius} per area. */
    private final List<double[]> zones = new ArrayList<>();

    public void add(double x, double y, double z, double radius) {
        zones.add(new double[]{x, y, z, radius});
    }

    public int size() {
        return zones.size();
    }

    /** {x, y, z, radius} per area. */
    public List<double[]> zones() {
        return zones;
    }

    /**
     * Whether a step to {@code (x, y, z)} coming from {@code (fromX, fromZ)} goes into an area: inside the radius and
     * closer to the centre than the start point.
     */
    public boolean blocks(double x, double y, double z, double fromX, double fromZ) {
        for (double[] c : zones) {
            if (Math.abs(c[1] - y) > HEIGHT) {
                continue;
            }
            double d = Math.hypot(c[0] - x, c[2] - z);
            if (d < c[3] && d < Math.hypot(c[0] - fromX, c[2] - fromZ)) {
                return true;
            }
        }
        return false;
    }

    public boolean inside(double x, double y, double z) {
        for (double[] c : zones) {
            if (Math.abs(c[1] - y) <= HEIGHT && Math.hypot(c[0] - x, c[2] - z) < c[3]) {
                return true;
            }
        }
        return false;
    }

    /** Whether a path leads into an area (deeper than {@code (fromX, fromZ)}). */
    public boolean crosses(NavigationPath path, double fromY, double fromX, double fromZ) {
        for (int i = 0; i < path.size(); i++) {
            if (blocks(path.x(i), fromY, path.z(i), fromX, fromZ)) {
                return true;
            }
        }
        return false;
    }
}
