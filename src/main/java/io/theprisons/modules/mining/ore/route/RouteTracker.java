package io.theprisons.modules.mining.ore.route;

import io.theprisons.core.control.RotationMath;

import java.util.ArrayList;
import java.util.List;

/**
 * Turns the player's walk into waypoints - only the corner points, not every step. A new waypoint is set where the
 * player is when, since the last waypoint,
 * <ul>
 *     <li>the view turned more than {@value #TURN_DEGREES}°,</li>
 *     <li>the player is {@value #SIDE_BLOCKS} blocks left or right of the line walked so far,</li>
 *     <li>the player went {@value #HEIGHT_BLOCKS} blocks up or down, or</li>
 *     <li>{@value #MAX_SEGMENT} blocks were walked (so the pathfinder always has a near goal).</li>
 * </ul>
 * Nothing is set before the player moved {@value #MIN_SPACING} block away from the last waypoint; turning on the spot
 * only updates the view direction of that waypoint.
 */
public final class RouteTracker {
    static final double TURN_DEGREES = 20.0D;
    static final double SIDE_BLOCKS = 2.0D;
    static final double HEIGHT_BLOCKS = 2.0D;
    static final double MAX_SEGMENT = 24.0D;
    static final double MIN_SPACING = 1.0D;
    /** The line of a segment is known once the player is this far from its waypoint. */
    private static final double LINE_AFTER = 2.0D;

    private final List<int[]> waypoints = new ArrayList<>();
    private double ax;
    private double ay;
    private double az;
    private float yaw;
    private double dirX = Double.NaN;
    private double dirZ = Double.NaN;

    public void start(double x, double y, double z, float viewYaw) {
        waypoints.clear();
        add(x, y, z, viewYaw);
    }

    /** @return whether a waypoint was set. */
    public boolean update(double x, double y, double z, float viewYaw, boolean onGround) {
        if (waypoints.isEmpty() || !onGround) {
            return false;
        }
        double dx = x - ax;
        double dz = z - az;
        double dist = Math.hypot(dx, dz);
        if (dist < MIN_SPACING && Math.abs(y - ay) < HEIGHT_BLOCKS) {
            yaw = viewYaw;
            return false;
        }
        if (Double.isNaN(dirX) && dist >= LINE_AFTER) {
            dirX = dx / dist;
            dirZ = dz / dist;
        }
        boolean turned = Math.abs(RotationMath.wrap(viewYaw - yaw)) > TURN_DEGREES;
        boolean aside = !Double.isNaN(dirX) && Math.abs(dx * dirZ - dz * dirX) >= SIDE_BLOCKS;
        boolean height = Math.abs(y - ay) >= HEIGHT_BLOCKS;
        if (turned || aside || height || dist >= MAX_SEGMENT) {
            add(x, y, z, viewYaw);
            return true;
        }
        return false;
    }

    /** The end point, if the player moved on from the last waypoint. */
    public void finish(double x, double y, double z) {
        if (!waypoints.isEmpty() && (Math.hypot(x - ax, z - az) >= MIN_SPACING || Math.abs(y - ay) >= 1.0D)) {
            add(x, y, z, yaw);
        }
    }

    public List<int[]> waypoints() {
        return waypoints;
    }

    private void add(double x, double y, double z, float viewYaw) {
        waypoints.add(new int[]{(int) Math.floor(x), (int) Math.floor(y + 0.01D), (int) Math.floor(z)});
        ax = x;
        ay = y;
        az = z;
        yaw = viewYaw;
        dirX = Double.NaN;
        dirZ = Double.NaN;
    }
}
