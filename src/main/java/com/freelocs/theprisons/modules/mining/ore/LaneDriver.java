package com.freelocs.theprisons.modules.mining.ore;

import com.freelocs.theprisons.core.control.RotationMath;
import com.freelocs.theprisons.core.nav.NavigationPath;
import org.jspecify.annotations.Nullable;

/**
 * Drives the Ore Macro along a path like a person walking a way: the only steering of the macro (pure logic, one call
 * per tick).
 *
 * <ul>
 *     <li><b>Direction:</b> a point {@code lookahead} blocks ahead on the <em>straightened</em> path (from each node to
 *     the farthest node reachable in a straight line), so straight parts stay calm and corners become one curve.</li>
 *     <li><b>Keys:</b> forward only (no strafing - the view is the steering wheel), while the view points within
 *     {@value #WALK_ALIGNMENT}° of that direction; sprint on straight parts; jump at a step up (just before it, or when
 *     pressed against it).</li>
 *     <li><b>No braking</b> at the end (the next lane is usually appended before), no backing up: when there is no
 *     progress for {@value #STUCK_TICKS} ticks or the player is far off the way, it reports {@link Status#STUCK} and the
 *     macro plans anew from where it is.</li>
 * </ul>
 */
public final class LaneDriver {
    public enum Status { FOLLOWING, ARRIVED, STUCK }

    /** What the player does this tick. */
    public record Drive(float yaw, boolean forward, boolean jump, boolean sprint, Status status) {
    }

    /** Player state (feet position, view yaw). */
    public record Player(double x, double y, double z, float yaw, boolean onGround, boolean horizontalCollision) {
    }

    static final float WALK_ALIGNMENT = 75.0F;
    static final int STUCK_TICKS = 30;
    private static final double OFF_WAY = 4.5D;
    /** Look-ahead used where the way bends sharply (less corner cutting). */
    private static final double CORNER_LOOKAHEAD = 1.8D;
    private static final double JUMP_TRIGGER = 1.1D;

    private @Nullable NavigationPath path;
    private double[] cumulative = new double[0];
    private int index;
    private double progress;
    private double bestProgress;
    private int noProgressTicks;
    private final double lookahead;
    private String stuckReason = "";

    public LaneDriver(double lookahead) {
        this.lookahead = lookahead;
    }

    public void start(NavigationPath newPath) {
        path = newPath;
        cumulative = new double[newPath.size()];
        for (int i = 1; i < newPath.size(); i++) {
            cumulative[i] = cumulative[i - 1] + Math.hypot(newPath.x(i) - newPath.x(i - 1), newPath.z(i) - newPath.z(i - 1));
        }
        index = 0;
        progress = 0.0D;
        bestProgress = 0.0D;
        noProgressTicks = 0;
    }

    public void stop() {
        path = null;
    }

    public @Nullable NavigationPath path() {
        return path;
    }

    /** Segment the player is on (node index where it starts). */
    public int index() {
        return index;
    }

    /** Why the last {@link Status#STUCK} was reported. */
    public String stuckReason() {
        return stuckReason;
    }

    public double remaining() {
        return path == null ? 0.0D : cumulative[cumulative.length - 1] - progress;
    }

    public Drive tick(Player p, boolean sprintAllowed) {
        NavigationPath current = path;
        if (current == null) {
            return new Drive(p.yaw(), false, false, false, Status.ARRIVED);
        }
        int last = current.size() - 1;
        double cross = project(current, p);
        if (remaining() < 0.5D && Math.abs(p.y() - current.feet()[last]) < 1.0D) {
            return new Drive(p.yaw(), false, false, false, Status.ARRIVED);
        }
        if (progress > bestProgress + 0.15D) {
            bestProgress = progress;
            noProgressTicks = 0;
        } else {
            noProgressTicks++;
        }
        if (noProgressTicks > STUCK_TICKS || cross > OFF_WAY) {
            stuckReason = cross > OFF_WAY ? String.format(java.util.Locale.ROOT, "off the way (%.1f blocks)", cross)
                    : String.format(java.util.Locale.ROOT, "no progress at node %d/%d", index, last);
            StringBuilder around = new StringBuilder();
            for (int i = Math.max(0, index - 2); i <= Math.min(last, index + 4); i++) {
                around.append(String.format(java.util.Locale.ROOT, " [%d %d,%d,%d f%.2f %s]", i, com.freelocs.theprisons.core.nav.Pos.x(current.nodes()[i]),
                        com.freelocs.theprisons.core.nav.Pos.y(current.nodes()[i]), com.freelocs.theprisons.core.nav.Pos.z(current.nodes()[i]),
                        current.feet()[i], current.moves()[i]));
            }
            stuckReason += String.format(java.util.Locale.ROOT, " | player %.2f,%.2f,%.2f ground %s aim %.0f | %s", p.x(), p.y(), p.z(),
                    p.onGround(), aimYaw(current, p, lookahead), around);
            return new Drive(p.yaw(), false, false, false, Status.STUCK);
        }
        float along = segmentYaw(current);
        float yaw = aimYaw(current, p, lookahead);
        boolean corner = Math.abs(RotationMath.wrap(aimYaw(current, p, lookahead + 1.5D) - along)) > 30.0F;
        if (Math.abs(RotationMath.wrap(yaw - along)) > 45.0F) {
            // A sharp bend within reach: aim closer, so the corner is taken instead of cut.
            yaw = aimYaw(current, p, CORNER_LOOKAHEAD);
        }
        float rel = Math.abs(RotationMath.wrap(yaw - p.yaw()));
        boolean forward = rel < WALK_ALIGNMENT;
        boolean jump = forward && p.onGround() && (p.horizontalCollision() || stepUpAhead(current, p));
        // No sprinting into a bend: at walking speed the curve stays on the way.
        boolean sprint = sprintAllowed && forward && rel < 20.0F && !corner && remaining() > 3.0D && !jump;
        return new Drive(yaw, forward, jump, sprint, Status.FOLLOWING);
    }

    /** Updates {@link #index} / {@link #progress} from the nearest segment ahead; returns the distance to the way. */
    private double project(NavigationPath current, Player p) {
        int last = current.size() - 1;
        if (last == 0) {
            return Math.hypot(p.x() - current.x(0), p.z() - current.z(0));
        }
        double best = Double.POSITIVE_INFINITY;
        int bestIndex = index;
        double bestAt = progress;
        for (int i = index; i < Math.min(last, index + 10); i++) {
            double ax = current.x(i);
            double az = current.z(i);
            double sx = current.x(i + 1) - ax;
            double sz = current.z(i + 1) - az;
            double lengthSq = sx * sx + sz * sz;
            double t = lengthSq == 0.0D ? 0.0D : Math.max(0.0D, Math.min(1.0D, ((p.x() - ax) * sx + (p.z() - az) * sz) / lengthSq));
            double distance = Math.hypot(p.x() - (ax + sx * t), p.z() - (az + sz * t));
            double at = cumulative[i] + Math.sqrt(lengthSq) * t;
            // Only forward along the way (a way that runs back over itself must not jump or fall back).
            if (distance < best - 1.0E-6D && at >= progress - 0.75D && at <= progress + 2.5D) {
                best = distance;
                bestIndex = i;
                bestAt = at;
            }
        }
        if (best == Double.POSITIVE_INFINITY) {
            // Nothing within the forward window: measure the distance to the current segment only.
            int i = Math.min(index, last - 1);
            double sx = current.x(i + 1) - current.x(i);
            double sz = current.z(i + 1) - current.z(i);
            double lengthSq = sx * sx + sz * sz;
            double t = lengthSq == 0.0D ? 0.0D : Math.max(0.0D, Math.min(1.0D, ((p.x() - current.x(i)) * sx + (p.z() - current.z(i)) * sz) / lengthSq));
            return Math.hypot(p.x() - (current.x(i) + sx * t), p.z() - (current.z(i) + sz * t));
        }
        index = bestIndex;
        progress = Math.max(progress, bestAt);
        return best;
    }

    /** Direction of the segment the player is on. */
    private float segmentYaw(NavigationPath current) {
        int last = current.size() - 1;
        int i = Math.min(index, Math.max(0, last - 1));
        return last == 0 ? 0.0F : RotationMath.yawOf(current.x(i + 1) - current.x(i), current.z(i + 1) - current.z(i));
    }

    /** Direction to the point {@code distance} ahead along the straightened path. */
    private float aimYaw(NavigationPath current, Player p, double distance) {
        int[] straight = current.straight();
        int last = current.size() - 1;
        double fromX = p.x();
        double fromZ = p.z();
        double left = distance;
        int node = index;
        while (true) {
            int next = straight == null ? node + 1 : Math.max(node + 1, straight[node]);
            next = Math.min(next, last);
            double tx = current.x(next);
            double tz = current.z(next);
            double length = Math.hypot(tx - fromX, tz - fromZ);
            if (length >= left || next == last) {
                double f = length <= 1.0E-6D ? 0.0D : Math.min(1.0D, left / length);
                double ax = fromX + (tx - fromX) * f - p.x();
                double az = fromZ + (tz - fromZ) * f - p.z();
                if (Math.hypot(ax, az) < 0.3D) {
                    // Practically at the end: keep the direction of the last segment.
                    return last > 0 ? RotationMath.yawOf(current.x(last) - current.x(last - 1), current.z(last) - current.z(last - 1)) : p.yaw();
                }
                return RotationMath.yawOf(ax, az);
            }
            left -= length;
            fromX = tx;
            fromZ = tz;
            node = next;
        }
    }

    /** A step up (jump node) starts within {@value #JUMP_TRIGGER} blocks ahead. */
    private boolean stepUpAhead(NavigationPath current, Player p) {
        for (int j = index + 1; j < current.size(); j++) {
            double ahead = cumulative[j] - progress;
            if (ahead > JUMP_TRIGGER) {
                return false;
            }
            // By height, not by move type: once the player is up there, it must not keep hopping.
            if (current.feet()[j] > p.y() + 0.6D) {
                return true;
            }
        }
        return false;
    }
}
