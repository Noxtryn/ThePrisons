package com.freelocs.theprisons.core.control;

import net.minecraft.client.network.ClientPlayerEntity;
import org.jspecify.annotations.Nullable;

/**
 * Single owner of the player's yaw and pitch while a module holds the control lease. Modules and the path follower
 * only <em>request</em> a direction; once per tick the highest-priority request (see {@link RotationMode}) becomes the
 * target of the {@link HumanRotation} motion.
 *
 * <p>The motion is written to the player on <b>every rendered frame</b> ({@link #frame}, called right after vanilla
 * applied the mouse) and once more at the end of the tick ({@link #apply}), the same way mouse input is applied: yaw
 * and the previous-tick yaw move together, so the camera shows the exact current angle without the one-tick
 * interpolation delay. At 60+ fps the view therefore moves continuously instead of in 20 Hz steps.
 *
 * <p>Requests made after this tick's {@link #apply} are kept for the next one, so a module can pick its next target
 * right after breaking a block and the turn starts on the following tick.
 */
public final class RotationController {
    private record Request(RotationMode mode, float yaw, float pitch, float width) {
    }

    private record Follow(float yaw, float pitch, float yawOmega, float pitchOmega) {
    }

    /** Default target size (Fitts' W) when the requester does not know it: a block a few metres away. */
    public static final float DEFAULT_WIDTH = 12.0F;
    /** Larger than this between two writes means someone else (player, server) turned the view. */
    private static final float EXTERNAL_CHANGE = 0.5F;

    private final HumanRotation motion = new HumanRotation();
    private final FollowMotion follow = new FollowMotion();
    private @Nullable Follow pendingFollow;
    private @Nullable Follow activeFollow;
    private double lastStep = Double.NaN;
    private HumanRotation.Profile profile = HumanRotation.Profile.DEFAULT;
    private @Nullable Request pending;
    private RotationMode activeMode = RotationMode.NAVIGATION;
    private float remaining;
    private boolean hasTarget;
    private boolean synced;
    private float writtenYaw;
    private float writtenPitch;


    public HumanRotation.Profile profile() {
        return profile;
    }

    /** Keeps the request with the highest priority; the first of equal priority wins. */
    public void request(RotationMode mode, float yaw, float pitch) {
        request(mode, yaw, pitch, DEFAULT_WIDTH);
    }

    /** @param width angular size of what is aimed at in degrees; smaller targets are approached more carefully */
    public void request(RotationMode mode, float yaw, float pitch, float width) {
        Request current = pending;
        if (current == null || mode.priority() > current.mode().priority()) {
            pending = new Request(mode, yaw, pitch, width);
        }
    }

    /**
     * Continuous following instead of aimed movements: the view is pulled towards this direction by critically damped
     * springs, integrated every frame ({@link FollowMotion}). For a target that moves all the time (steering along a
     * path). Wins over {@link #request} in the same tick; request it every tick while wanted.
     *
     * @param yawOmega   yaw stiffness in 1/s (≈ 4.7 / settle time)
     * @param pitchOmega pitch stiffness in 1/s
     */
    public void follow(float yaw, float pitch, float yawOmega, float pitchOmega) {
        pendingFollow = new Follow(yaw, pitch, yawOmega, pitchOmega);
    }

    /** End of tick: this tick's winning request becomes the motion target. @return remaining angle to it */
    public float apply(ClientPlayerEntity player) {
        return apply(player, now());
    }

    float apply(ClientPlayerEntity player, double now) {
        Request request = pending;
        pending = null;
        Follow wanted = pendingFollow;
        pendingFollow = null;
        if (wanted != null) {
            if (activeFollow == null || !synced) {
                follow.reset(player.getYaw(), player.getPitch());
                lastStep = now;
                synced = true;
            }
            activeFollow = wanted;
            activeMode = RotationMode.MINING;
            hasTarget = true;
            writeFollow(player, now);
            return remaining;
        }
        if (activeFollow != null) {
            // Back to aimed movements: continue from where the following view is.
            activeFollow = null;
            motion.reset(player.getYaw(), player.getPitch());
        }
        sync(player);
        if (request == null) {
            // Nobody wants a direction: let a running movement come to rest naturally, then hold still.
            if (!motion.moving()) {
                hasTarget = false;
                remaining = 0.0F;
            }
            write(player, now);
            return remaining;
        }
        activeMode = request.mode();
        hasTarget = true;
        float speedFactor = switch (activeMode) {
            case JUMPING -> 1.3F;
            case MINING, TURNING, RECOVERY -> 1.0F;
            case OBSTACLE_CHECK -> 0.9F;
            case NAVIGATION -> 0.75F;
        };
        motion.retarget(now, request.yaw(), request.pitch(), request.width(), profile.scaled(speedFactor));
        write(player, now);
        return remaining;
    }

    /** Every rendered frame, after the mouse was applied: moves the view along the running movement. */
    public void frame(ClientPlayerEntity player) {
        if (activeFollow != null && synced) {
            writeFollow(player, now());
            return;
        }
        if (!synced || (!hasTarget && !motion.moving())) {
            return;
        }
        sync(player);
        write(player, now());
    }

    public void clear() {
        pending = null;
        pendingFollow = null;
        activeFollow = null;
        hasTarget = false;
        synced = false;
        remaining = 0.0F;
    }

    public RotationMode activeMode() {
        return activeMode;
    }

    /** Angle left to the current target (0 when idle). */
    public float remaining() {
        return remaining;
    }

    public boolean hasTarget() {
        return hasTarget;
    }

    /** Current angular speed of the view in °/s. */
    public double speed() {
        return motion.speed(now());
    }

    /** Starts from the player's real view when we did not write it last (first use, or turned by someone else). */
    private void sync(ClientPlayerEntity player) {
        if (!synced || Math.abs(player.getYaw() - writtenYaw) > EXTERNAL_CHANGE || Math.abs(player.getPitch() - writtenPitch) > EXTERNAL_CHANGE) {
            motion.reset(player.getYaw(), player.getPitch());
            synced = true;
        }
    }

    private void writeFollow(ClientPlayerEntity player, double now) {
        Follow target = activeFollow;
        if (target == null) {
            return;
        }
        if (Math.abs(player.getYaw() - writtenYaw) > EXTERNAL_CHANGE || Math.abs(player.getPitch() - writtenPitch) > EXTERNAL_CHANGE) {
            // Turned by someone else (player, server): continue from there.
            follow.reset(player.getYaw(), player.getPitch());
        }
        double seconds = Double.isNaN(lastStep) ? 0.0D : now - lastStep;
        lastStep = now;
        follow.step(target.yaw(), target.pitch(), seconds, target.yawOmega(), target.pitchOmega());
        set(player, follow.yaw(), follow.pitch());
        remaining = RotationMath.angleBetween(follow.yaw(), follow.pitch(), target.yaw(), target.pitch());
    }

    private void set(ClientPlayerEntity player, float yaw, float pitch) {
        float dYaw = yaw - player.getYaw();
        float dPitch = pitch - player.getPitch();
        player.setYaw(yaw);
        player.setPitch(pitch);
        player.lastYaw += dYaw;
        player.lastPitch += dPitch;
        writtenYaw = yaw;
        writtenPitch = pitch;
    }

    private void write(ClientPlayerEntity player, double now) {
        float[] view = motion.sample(now);
        float dYaw = view[0] - player.getYaw();
        float dPitch = view[1] - player.getPitch();
        player.setYaw(view[0]);
        player.setPitch(view[1]);
        // Like mouse input: move the interpolation start too, so the camera shows this angle right now.
        player.lastYaw += dYaw;
        player.lastPitch += dPitch;
        writtenYaw = view[0];
        writtenPitch = view[1];
        remaining = hasTarget ? RotationMath.angleBetween(view[0], view[1], (float) motion.targetYaw(), (float) motion.targetPitch()) : 0.0F;
    }

    private static double now() {
        return System.nanoTime() * 1.0E-9D;
    }
}
