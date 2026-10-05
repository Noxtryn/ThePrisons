package io.theprisons.core.control;

import net.minecraft.client.MinecraftClient;
import org.jspecify.annotations.Nullable;

/**
 * The control lease: at most one module drives the player (movement keys, view, block breaking) at a time. The
 * lease is taken when a macro starts and released when it stops for any reason, which releases every key and
 * cancels block breaking, so a crashed or stopped macro can never leave the player walking.
 */
public final class ControlService {
    private final InputController input = new InputController();
    private final RotationController rotation = new RotationController();
    private @Nullable Object owner;
    private @Nullable String ownerName;

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
        if (client.player != null && client.currentScreen == null) {
            rotation.apply(client.player);
        } else {
            rotation.clear();
        }
        input.apply(client, true);
    }
}
