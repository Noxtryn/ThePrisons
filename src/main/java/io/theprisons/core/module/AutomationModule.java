package io.theprisons.core.module;

import io.theprisons.core.control.ControlService;
import io.theprisons.core.world.WorldCache;
import net.minecraft.client.MinecraftClient;
import org.jspecify.annotations.Nullable;

/**
 * Base of modules that drive the player (macros). Enabling takes the control lease and a world-cache interest;
 * disabling, for whatever reason, releases both, so keys are always released. Never enabled on start-up.
 */
public abstract class AutomationModule extends Module {
    protected final ControlService control;
    protected final WorldCache world;
    private final int scanRadius;
    private final int scanVertical;

    protected AutomationModule(String id, String name, Category category, String group, String description, int defaultKey,
                               ControlService control, WorldCache world, int scanRadius, int scanVertical) {
        super(id, name, category, group, description, defaultKey);
        this.control = control;
        this.world = world;
        this.scanRadius = scanRadius;
        this.scanVertical = scanVertical;
    }

    @Override
    public final boolean persistEnabled() {
        return false;
    }

    @Override
    public @Nullable String canEnable() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || client.world == null) {
            return "Join a world first.";
        }
        Object holder = control.owner();
        if (holder != null && holder != this) {
            return "Another macro is running (" + control.ownerName() + ").";
        }
        String setup = io.theprisons.core.setup.SetupGate.check(this);
        if (setup != null) {
            return setup;
        }
        return canStart(client);
    }

    /** Module specific start checks. */
    protected @Nullable String canStart(MinecraftClient client) {
        return null;
    }

    @Override
    protected final void onEnable() {
        if (!control.acquire(this, name())) {
            disableSelf("Another macro is running.");
            return;
        }
        world.acquire(this, scanRadius, scanVertical);
        onStart(MinecraftClient.getInstance());
    }

    @Override
    protected final void onDisable() {
        MinecraftClient client = MinecraftClient.getInstance();
        try {
            onStop(client);
        } finally {
            world.release(this);
            control.release(this, client);
        }
    }

    /**
     * The player was moved far in one tick (teleport, mine reset) while this module drives.
     *
     * @return true to keep running (the module re-plans from the new position), false to be stopped
     */
    public boolean onRelocated(String reason) {
        return false;
    }

    /**
     * The control layer saw the player turn and turn without getting anywhere (spin guard). Movement and view are already
     * stopped for a moment; drop the current path / target so the next plan starts fresh. A second spin shortly after stops the
     * module. Default: nothing to drop.
     */
    public void onSpinLoop() {
    }

    /**
     * The module is teleporting the player on purpose right now (e.g. /spawn and back home): a world change then counts
     * as a teleport ({@link #onRelocated}) instead of stopping it.
     */
    public boolean travelling() {
        return false;
    }

    /**
     * The module copes with a death (respawns and carries on) and with the server swapping the world (zone changes): the
     * safety monitor then neither stops it on "You died." nor on "World changed." (the latter counts as a teleport).
     */
    public boolean survivesDeath() {
        return false;
    }

    /** Fighting modules get hurt by design: the safety monitor then ignores "Took damage." (low health still stops). */
    public boolean toleratesDamage() {
        return false;
    }

    protected abstract void onStart(MinecraftClient client);

    protected abstract void onStop(MinecraftClient client);
}
