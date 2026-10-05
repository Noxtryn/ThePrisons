package io.theprisons.core.movement;

import io.theprisons.core.control.InputController;
import io.theprisons.core.control.RotationController;
import io.theprisons.core.control.RotationMode;
import io.theprisons.core.nav.NavigationPath;
import io.theprisons.core.nav.PathValidator;
import io.theprisons.core.nav.VoxelView;
import io.theprisons.core.nav.Walkability;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.util.math.Vec3d;
import org.jspecify.annotations.Nullable;

/**
 * Walks a {@link NavigationPath} with the movement keys. Each tick it re-validates the next few edges against the
 * live world, lets {@link SteeringLogic} choose keys and a view, and forwards both to the control services.
 *
 * <p>Anti-stuck, in stages: (1) stop and back off, jumping when pressed against a wall; (2) side-step to the
 * alternating side while jumping forward; then a re-plan is requested and {@link #stuckNodes()} names the path nodes
 * the caller should penalise so the next path avoids the spot. After {@value #MAX_RECOVERIES} recoveries on one
 * path the follower gives up ({@link Result#FAILED}).
 */
public final class PathFollower {
    public enum Result { MOVING, ARRIVED, REPLAN, FAILED }

    private static final int MAX_RECOVERIES = 3;
    private static final int OFF_PATH_LIMIT = 20;
    private static final int RECOVERY_TICKS = 16;
    /** Highest drop a path may use; also the fall height the validator accepts. */
    public static final int MAX_DROP = 3;

    private final SteeringLogic steering = new SteeringLogic();
    private @Nullable NavigationPath path;
    private SteeringLogic.@Nullable Output last;
    private SteeringLogic.Settings settings = SteeringLogic.Settings.DEFAULT;
    private int maxDrop = MAX_DROP;
    private int recoveries;
    private int recoverTicks;
    private int offPathTicks;
    private int ticks;
    private boolean strafeLeft;
    private String reason = "";
    private final LongArrayList stuckNodes = new LongArrayList();
    private float cruisePitch = Float.NaN;
    private boolean sprintAllowed = true;
    private boolean brake;

    public void start(NavigationPath newPath, SteeringLogic.Settings steeringSettings, int newMaxDrop) {
        this.path = newPath;
        this.settings = steeringSettings;
        this.maxDrop = newMaxDrop;
        steering.setPath(newPath);
        recoveries = 0;
        recoverTicks = 0;
        brake = false;
        offPathTicks = 0;
        ticks = 0;
        reason = "";
        last = null;
        stuckNodes.clear();
    }

    public void stop(InputController input) {
        path = null;
        last = null;
        input.clear();
    }

    public @Nullable NavigationPath path() {
        return path;
    }

    public String reason() {
        return reason;
    }

    public boolean recovering() {
        return recoverTicks > 0;
    }

    public double remaining() {
        return path == null ? 0.0D : steering.remaining();
    }

    public int index() {
        return steering.index();
    }

    /** Path nodes right after the spot where the player got stuck (for penalties), filled on a stuck re-plan. */
    public LongArrayList stuckNodes() {
        return stuckNodes;
    }

    /**
     * Pitch to hold while simply walking (navigation / turning), e.g. looking down at the ground ahead while mining
     * on the move. {@link Float#NaN} = look along the path.
     */
    public void setCruisePitch(float pitch) {
        this.cruisePitch = pitch;
    }

    /** The owner may forbid sprinting temporarily (e.g. while blocks are in reach). */
    public void setSprintAllowed(boolean allowed) {
        this.sprintAllowed = allowed;
    }

    /**
     * Short brake requested by the owner (forward released, no sprint). Braking is not counted as being stuck; the
     * owner must only brake for a few ticks.
     */
    public void setBrake(boolean value) {
        this.brake = value;
    }

    /** Movement direction the steering chose last tick (degrees), NaN when idle. */
    public double moveYaw() {
        return last == null ? Double.NaN : last.moveYaw();
    }

    public Result tick(ClientPlayerEntity player, InputController input, RotationController rotation, VoxelView live) {
        NavigationPath current = path;
        if (current == null) {
            return Result.ARRIVED;
        }
        ticks++;
        if (recoverTicks > 0) {
            return tickRecovery(player, input, rotation);
        }
        if (ticks % 5 == 1) {
            String invalid = PathValidator.firstProblem(current, new Walkability(live, maxDrop), steering.index(), 3);
            if (invalid != null) {
                reason = invalid;
                input.clear();
                return Result.REPLAN;
            }
        }

        Vec3d velocity = player.getVelocity();
        SteeringLogic.PlayerState state = new SteeringLogic.PlayerState(player.getX(), player.getY(), player.getZ(),
                player.getYaw(), player.getPitch(), velocity.x, velocity.z, player.isOnGround(), player.horizontalCollision);
        SteeringLogic.Output out = steering.tick(state, settings);
        last = out;

        switch (out.status()) {
            case ARRIVED -> {
                input.clear();
                return Result.ARRIVED;
            }
            case STUCK -> {
                if (++recoveries > MAX_RECOVERIES) {
                    reason = "stuck at " + player.getBlockPos().toShortString();
                    rememberStuck(current);
                    input.clear();
                    return Result.FAILED;
                }
                reason = "stuck, recovering (" + recoveries + "/" + MAX_RECOVERIES + ")";
                recoverTicks = RECOVERY_TICKS;
                strafeLeft = !strafeLeft;
                return tickRecovery(player, input, rotation);
            }
            case JUMP_FAILED -> {
                reason = "jump landed off the path";
                input.clear();
                return Result.REPLAN;
            }
            case OFF_PATH -> {
                if (++offPathTicks > OFF_PATH_LIMIT || out.crossTrack() > 2.5D) {
                    reason = "left the path";
                    input.clear();
                    return Result.REPLAN;
                }
            }
            default -> offPathTicks = 0;
        }

        boolean braking = brake && player.isOnGround() && !out.jump();
        if (braking) {
            steering.resetStuck();
        }
        input.set(new InputController.Keys(out.forward() && !braking, out.back(), out.left() && !braking, out.right() && !braking,
                out.jump(), out.sprint() && sprintAllowed && !braking, false));
        float pitch = out.pitch();
        if (!Float.isNaN(cruisePitch) && (out.mode() == RotationMode.NAVIGATION || out.mode() == RotationMode.TURNING)) {
            pitch = cruisePitch;
        }
        rotation.request(out.mode(), out.yaw(), pitch);
        return Result.MOVING;
    }

    /**
     * Stage 1 (first recovery): pause, then back off (jumping when blocked). Stage 2+: side-step to alternating
     * sides while pressing forward and jump. Ends with a re-plan from wherever the player is now.
     */
    private Result tickRecovery(ClientPlayerEntity player, InputController input, RotationController rotation) {
        recoverTicks--;
        boolean blocked = player.horizontalCollision && player.isOnGround();
        if (recoverTicks >= RECOVERY_TICKS - 3) {
            input.clear();
        } else if (recoveries <= 1) {
            input.set(new InputController.Keys(false, true, false, false, blocked, false, false));
        } else {
            input.set(new InputController.Keys(recoverTicks < RECOVERY_TICKS / 2, false, strafeLeft, !strafeLeft,
                    player.isOnGround(), false, false));
        }
        if (last != null) {
            rotation.request(RotationMode.RECOVERY, (float) last.moveYaw(), 10.0F);
        }
        if (recoverTicks == 0) {
            input.clear();
            steering.resetStuck();
            NavigationPath current = path;
            if (current != null) {
                rememberStuck(current);
            }
            return Result.REPLAN;
        }
        return Result.MOVING;
    }

    private void rememberStuck(NavigationPath current) {
        stuckNodes.clear();
        int from = steering.index() + 1;
        for (int i = from; i < Math.min(current.size(), from + 2); i++) {
            stuckNodes.add(current.nodes()[i]);
        }
    }
}
