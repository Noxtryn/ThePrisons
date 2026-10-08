package io.theprisons.core.control;

import net.minecraft.client.MinecraftClient;
import org.jspecify.annotations.Nullable;

/**
 * The control lease: at most one module drives the player (movement keys, view, block breaking) at a time. The
 * lease is taken when a macro starts and released when it stops for any reason, which releases every key and
 * cancels block breaking, so a crashed or stopped macro can never leave the player walking.
 */
public final class ControlService {
    /** Told when the spin guard fires (the core stabilises, re-plans once, or stops the macro). */
    public interface SpinHandler {
        void spin(Object owner, SpinGuard.Verdict verdict, SpinGuard.Metrics metrics);
    }

    /** Ticks of standing still after a spin, so the next plan starts from a calm state. */
    public static final int STABILISE_TICKS = 10;

    private final InputController input = new InputController();
    private final RotationController rotation = new RotationController();
    private final SpinGuard spin = new SpinGuard();
    private final ControlTelemetry telemetry = new ControlTelemetry();
    private @Nullable Object owner;
    private @Nullable String ownerName;
    private @Nullable SpinHandler spinHandler;
    private int stabiliseTicks;
    private long tick;
    private @Nullable RotationIntent rotationWinner;
    private @Nullable RotationIntent lastRotationWinner;
    private boolean logOwners = Boolean.getBoolean("theprisons.dev") || Boolean.getBoolean("theprisons.control.log");

    public SpinGuard spinGuard() {
        return spin;
    }

    public ControlTelemetry telemetry() {
        return telemetry;
    }

    public void setSpinHandler(@Nullable SpinHandler handler) {
        this.spinHandler = handler;
    }

    /**
     * The system's wish for the keys this tick. One winner per tick: the highest priority, the later among equals. The loser is
     * counted ({@link InputController#rejectedRequests()}) and never reaches the keys.
     */
    public boolean submit(MovementIntent intent) {
        return input.request(intent.priority(), intent.source(), intent.keys());
    }

    /**
     * The system's wish for the view this tick. One winner per tick: the highest {@link IntentPriority}; among equals the higher
     * {@link RotationMode} priority; the later among those. Systems that use this must not also call
     * {@code rotation().request / follow} in the same tick.
     */
    public boolean submit(RotationIntent intent) {
        RotationIntent held = rotationWinner;
        if (held != null) {
            int byIntent = Integer.compare(intent.priority().rank(), held.priority().rank());
            int byMode = Integer.compare(intent.mode().priority(), held.mode().priority());
            if (byIntent < 0 || byIntent == 0 && byMode < 0) {
                return false;
            }
        }
        rotationWinner = intent;
        rotation.clearPending();
        if (intent.follow()) {
            rotation.follow(intent.yaw(), intent.pitch(), intent.yawOmega(), intent.pitchOmega());
        } else {
            rotation.request(intent.mode(), intent.yaw(), intent.pitch(), intent.width());
        }
        return true;
    }

    /** The view intent that is winning right now (this tick, before it is applied); null = none yet. */
    public @Nullable RotationIntent pendingRotation() {
        return rotationWinner;
    }

    /** Who won the view in the last tick that had an intent ({@code null} = only legacy requests, or nothing). */
    public @Nullable RotationIntent rotationWinner() {
        return lastRotationWinner;
    }

    /** Something real changed in the world (a block): feeds the spin guard's "progress". */
    public void noteWorldProgress() {
        spin.progress();
    }

    /** Stands still (no keys, no view motion) for {@link #STABILISE_TICKS} ticks. */
    public void stabilise() {
        stabiliseTicks = STABILISE_TICKS;
    }

    public InputController input() {
        return input;
    }

    public RotationController rotation() {
        return rotation;
    }

    /** @return false when another owner holds the lease */
    public boolean acquire(Object newOwner, String name) {
        if (owner != null && owner != newOwner) {
            return false;
        }
        owner = newOwner;
        ownerName = name;
        rotation.clear();
        input.clear();
        spin.reset();
        stabiliseTicks = 0;
        return true;
    }

    public void release(Object currentOwner, MinecraftClient client) {
        if (owner != currentOwner) {
            return;
        }
        owner = null;
        ownerName = null;
        input.release(client);
        rotation.clear();
        if (client.interactionManager != null) {
            client.interactionManager.cancelBlockBreaking();
        }
    }

    public boolean holds(Object candidate) {
        return owner == candidate;
    }

    public @Nullable Object owner() {
        return owner;
    }

    public @Nullable String ownerName() {
        return ownerName;
    }

    /** True while a module drives the player; vanilla block breaking is suppressed then (see mixin). */
    public boolean automating() {
        return owner != null;
    }

    /** Every rendered frame (after vanilla applied the mouse): continue the view motion at frame rate. */
    public void frame(MinecraftClient client) {
        if (owner == null || client.player == null || client.currentScreen != null) {
            return;
        }
        rotation.frame(client.player);
    }

    /** End of tick, after all modules: apply view and keys. */
    public void apply(MinecraftClient client) {
        if (owner == null) {
            return;
        }
        tick++;
        if (stabiliseTicks > 0) {
            // After a spin: nothing moves or turns for a moment, whatever the module asked for.
            stabiliseTicks--;
            input.clear();
            rotation.clear();
        }
        lastRotationWinner = rotationWinner;
        rotationWinner = null;
        if (client.player != null && client.currentScreen == null) {
            rotation.apply(client.player);
        } else {
            rotation.clear();
        }
        input.apply(client, true);
        if (client.player != null && client.currentScreen == null) {
            observe(client.player);
        }
    }

    private String rotationSource() {
        RotationIntent winner = lastRotationWinner;
        return winner != null ? winner.source() : "";
    }

    private IntentPriority rotationOwner(RotationMode mode) {
        RotationIntent winner = lastRotationWinner;
        return winner != null ? winner.priority() : mode.intent();
    }

    /** After keys and view were applied: telemetry sample and spin check. */
    private void observe(net.minecraft.client.network.ClientPlayerEntity player) {
        long now = System.currentTimeMillis();
        RotationMode mode = rotation.activeMode();
        SpinGuard.Verdict verdict = stabiliseTicks > 0 ? SpinGuard.Verdict.NONE : spin.feed(now, player.getX(), player.getZ(), player.getYaw());
        if (stabiliseTicks > 0) {
            spin.reset();
        }
        telemetry.record(new ControlTelemetry.Sample(tick, player.getX(), player.getY(), player.getZ(), player.getYaw(), player.getPitch(),
                rotation.requestedYaw(), rotation.requestedPitch(), mode, rotationOwner(mode), rotationSource(), input.winnerSource(), input.winnerPriority(),
                ControlTelemetry.keysText(input.wanted()), rotation.remaining()), spin.metrics(), now, logOwners);
        SpinHandler handler = spinHandler;
        if (verdict != SpinGuard.Verdict.NONE && handler != null && owner != null) {
            handler.spin(owner, verdict, spin.metrics());
        }
    }
}
