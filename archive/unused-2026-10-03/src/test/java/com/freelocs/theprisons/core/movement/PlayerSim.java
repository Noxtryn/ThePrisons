package com.freelocs.theprisons.core.movement;

import com.freelocs.theprisons.core.nav.Cell;
import com.freelocs.theprisons.core.nav.VoxelView;
import com.freelocs.theprisons.core.control.HumanRotation;
import com.freelocs.theprisons.core.control.RotationMode;

/**
 * Tiny kinematic stand-in for vanilla player movement, good enough to exercise the steering logic: the 0.6 x 1.8
 * box, input vector relative to yaw (vanilla formula), 0.6 step height, vanilla jump arc and gravity, and
 * axis-separated collision against a {@link VoxelView}. Position only changes through this integration - the
 * controller under test never sets coordinates.
 */
final class PlayerSim {
    private static final double HALF = 0.3D;
    private static final double HEIGHT = 1.8D;
    private static final double WALK = 0.2D;
    private static final double SPRINT = 0.26D;

    private final VoxelView world;
    private final HumanRotation rotation = new HumanRotation();
    private int ticks;
    double x;
    double y;
    double z;
    float yaw;
    float pitch;
    double vx;
    double vy;
    double vz;
    boolean onGround = true;
    boolean horizontalCollision;
    double jumpVelocity = JumpPhysics.JUMP_VELOCITY;
    int jumps;
    double lastJumpGap = Double.NaN;

    PlayerSim(VoxelView world, double x, double y, double z, float yaw) {
        this.world = world;
        this.x = x;
        this.y = y;
        this.z = z;
        this.yaw = yaw;
        rotation.reset(yaw, 0.0F);
    }

    SteeringLogic.PlayerState state() {
        return new SteeringLogic.PlayerState(x, y, z, yaw, pitch, vx, vz, onGround, horizontalCollision);
    }

    /** Applies the view the controller asked for through the core view motion (same factors as in game, 20 Hz). */
    void rotate(SteeringLogic.Output out) {
        float speed = out.mode() == RotationMode.NAVIGATION ? 0.75F : out.mode() == RotationMode.JUMPING ? 1.3F : 1.0F;
        double now = ++ticks * 0.05D;
        rotation.retarget(now, out.yaw(), out.pitch(), 12.0D, HumanRotation.Profile.DEFAULT.scaled(speed));
        float[] next = rotation.sample(now + 0.05D);
        yaw = next[0];
        pitch = next[1];
    }

    void step(SteeringLogic.Output out, double obstacleX) {
        double forward = (out.forward() ? 1 : 0) - (out.back() ? 1 : 0);
        double strafe = (out.left() ? 1 : 0) - (out.right() ? 1 : 0);
        double length = Math.sqrt(forward * forward + strafe * strafe);
        if (length > 1.0D) {
            forward /= length;
            strafe /= length;
        }
        double speed = out.sprint() ? SPRINT : WALK;
        double rad = Math.toRadians(yaw);
        vx = (strafe * Math.cos(rad) - forward * Math.sin(rad)) * speed;
        vz = (forward * Math.cos(rad) + strafe * Math.sin(rad)) * speed;

        if (out.jump() && onGround) {
            vy = jumpVelocity;
            onGround = false;
            jumps++;
            lastJumpGap = obstacleX - (x + HALF);
        }

        horizontalCollision = false;
        moveHorizontal(vx, 0.0D);
        moveHorizontal(0.0D, vz);

        if (!onGround) {
            double next = y + vy;
            double floor = supportTop(x, z, y);
            if (vy < 0 && next <= floor) {
                y = floor;
                vy = 0.0D;
                onGround = true;
            } else if (vy > 0 && collides(x, z, next)) {
                vy = 0.0D;
            } else {
                y = next;
                vy = (vy - 0.08D) * 0.98D;
            }
        } else if (supportTop(x, z, y) < y - 1.0E-6D) {
            onGround = false;
            vy = 0.0D;
        }
    }

    private void moveHorizontal(double dx, double dz) {
        if (dx == 0.0D && dz == 0.0D) {
            return;
        }
        double nx = x + dx;
        double nz = z + dz;
        if (!collides(nx, nz, y)) {
            x = nx;
            z = nz;
            return;
        }
        // Vanilla step-up: small ledges are climbed without jumping.
        if (onGround) {
            double top = highestObstacle(nx, nz, y);
            if (top - y <= 0.6D + 1.0E-6D && !collides(nx, nz, top)) {
                x = nx;
                z = nz;
                y = top;
                return;
            }
        }
        horizontalCollision = true;
    }

    private boolean collides(double px, double pz, double py) {
        return highestObstacle(px, pz, py) > Double.NEGATIVE_INFINITY;
    }

    /** Highest collision top that intersects the player box at the given position, or -inf. */
    private double highestObstacle(double px, double pz, double py) {
        double best = Double.NEGATIVE_INFINITY;
        for (int cx = (int) Math.floor(px - HALF + 1.0E-6D); cx <= (int) Math.floor(px + HALF - 1.0E-6D); cx++) {
            for (int cz = (int) Math.floor(pz - HALF + 1.0E-6D); cz <= (int) Math.floor(pz + HALF - 1.0E-6D); cz++) {
                for (int cy = (int) Math.floor(py) - 1; cy <= (int) Math.floor(py + HEIGHT); cy++) {
                    int cell = world.cell(cx, cy, cz);
                    if (cell == Cell.UNKNOWN || !Cell.hasCollision(cell)) {
                        continue;
                    }
                    double lo = cy + Cell.minY16(cell) / 16.0D;
                    double hi = cy + Cell.maxY16(cell) / 16.0D;
                    if (hi > py + 1.0E-6D && lo < py + HEIGHT - 1.0E-6D) {
                        best = Math.max(best, hi);
                    }
                }
            }
        }
        return best;
    }

    /** Top of the highest collision below the feet within the box footprint. */
    private double supportTop(double px, double pz, double py) {
        double best = Double.NEGATIVE_INFINITY;
        for (int cx = (int) Math.floor(px - HALF + 1.0E-6D); cx <= (int) Math.floor(px + HALF - 1.0E-6D); cx++) {
            for (int cz = (int) Math.floor(pz - HALF + 1.0E-6D); cz <= (int) Math.floor(pz + HALF - 1.0E-6D); cz++) {
                for (int cy = (int) Math.floor(py + 1.0E-6D); cy >= (int) Math.floor(py) - 4; cy--) {
                    int cell = world.cell(cx, cy, cz);
                    if (Cell.hasCollision(cell)) {
                        double hi = cy + Cell.maxY16(cell) / 16.0D;
                        if (hi <= py + 1.0E-6D) {
                            best = Math.max(best, hi);
                            break;
                        }
                    }
                }
            }
        }
        return best;
    }
}
