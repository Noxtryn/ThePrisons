package com.freelocs.theprisons.core.safety;

import com.freelocs.theprisons.core.control.ControlService;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.math.Vec3d;
import org.jspecify.annotations.Nullable;

import java.util.function.BiConsumer;

/**
 * Safety stops for whatever module holds the control lease. Purely about safety and control: the player is moved
 * somewhere else (world change, teleport / mine reset), gets hurt, the inventory is full, a run limit is reached, or
 * the player takes over the keyboard. A violation stops the leasing module with a reason.
 *
 * <p>Runs only while a lease is held; costs a handful of field reads per tick.
 */
public final class SafetyMonitor {
    /** Current rule set, owned by the General › Safety module. */
    /**
     * @param resumeAfterTeleport a teleport / mine reset hands the macro a new start instead of stopping it
     * @param restartAfterError   a macro that crashed is started again after a short delay (see ThePrisonsCore)
     */
    public record Rules(boolean enabled, boolean stopOnDamage, int lowHealth, boolean stopWhenInventoryFull,
                        int maxRuntimeMinutes, double teleportDistance, boolean manualOverride, boolean resumeAfterTeleport,
                        boolean restartAfterError) {
        public static final Rules DEFAULT = new Rules(true, true, 8, false, 0, 8.0D, true, true, true);
    }

    /** What the lease holder is told when the player was moved (teleport, mine reset). */
    public interface RelocationHandler {
        /** @return true when the holder continues, false to stop it */
        boolean relocated(Object owner, String reason);
    }

    private static final String MOVED = "Moved ";

    private final ControlService control;
    private final BiConsumer<Object, String> stop;
    private RelocationHandler relocation = (owner, reason) -> false;
    /** Whether the lease owner is teleporting on purpose (a world change is then a teleport, not a stop). */
    private java.util.function.Predicate<Object> travelling = owner -> false;
    private java.util.function.Predicate<Object> survivesDeath = owner -> false;
    private long relocations;
    private Rules rules = Rules.DEFAULT;
    private @Nullable Object watched;
    private @Nullable ClientWorld lastWorld;
    private @Nullable Vec3d lastPos;
    private float lastHealth = -1.0F;
    private long startedAtMs;
    private int ticks;
    private long violations;

    /**
     * @param stop called with the lease owner and a reason when a rule is violated
     */
    public SafetyMonitor(ControlService control, BiConsumer<Object, String> stop) {
        this.control = control;
        this.stop = stop;
    }

    public void setRelocationHandler(RelocationHandler handler) {
        this.relocation = handler;
    }

    public void setTravelling(java.util.function.Predicate<Object> travelling) {
        this.travelling = travelling;
    }

    public void setSurvivesDeath(java.util.function.Predicate<Object> survivesDeath) {
        this.survivesDeath = survivesDeath;
    }

    public long relocations() {
        return relocations;
    }

    public void setRules(Rules newRules) {
        this.rules = newRules;
    }

    public Rules rules() {
        return rules;
    }

    public long violations() {
        return violations;
    }

    /** Milliseconds since the current lease was taken, 0 when idle. */
    public long runtimeMs() {
        return watched == null ? 0L : System.currentTimeMillis() - startedAtMs;
    }

    public void tick(MinecraftClient client) {
        Object owner = control.owner();
        if (owner == null) {
            watched = null;
            return;
        }
        ClientPlayerEntity player = client.player;
        if (owner != watched) {
            watched = owner;
            reset(client);
        }
        if (player == null || client.world == null) {
            violate(owner, "Left the world.");
            return;
        }
        ticks++;
        String reason = check(client, player, owner);
        boolean moved = reason != null && reason.startsWith(MOVED);
        lastWorld = client.world;
        lastPos = player.getEntityPos();
        lastHealth = player.getHealth();
        if (moved && rules.resumeAfterTeleport() && relocation.relocated(owner, reason)) {
            relocations++;
            return;
        }
        if (reason != null) {
            violate(owner, reason);
        }
    }

    private @Nullable String check(MinecraftClient client, ClientPlayerEntity player, Object owner) {
        if (player.isDead()) {
            // A module that respawns and carries on handles its death itself.
            return survivesDeath.test(owner) ? null : "You died.";
        }
        if (lastWorld != null && lastWorld != client.world) {
            return travelling.test(owner) || survivesDeath.test(owner) ? MOVED + "to another world (own teleport)." : "World changed.";
        }
        if (!rules.enabled()) {
            return null;
        }
        Vec3d pos = player.getEntityPos();
        if (lastPos != null && rules.teleportDistance() > 0 && lastPos.squaredDistanceTo(pos) > rules.teleportDistance() * rules.teleportDistance()) {
            return MOVED + Math.round(lastPos.distanceTo(pos)) + " blocks at once (teleport / mine reset).";
        }
        if (rules.stopOnDamage() && lastHealth >= 0.0F && player.getHealth() < lastHealth - 0.01F) {
            return "Took damage.";
        }
        if (rules.lowHealth() > 0 && player.getHealth() <= rules.lowHealth()) {
            return "Health is low.";
        }
        if (rules.stopWhenInventoryFull() && ticks % 20 == 0 && player.getInventory().getEmptySlot() == -1) {
            return "Inventory is full.";
        }
        if (rules.maxRuntimeMinutes() > 0 && runtimeMs() >= rules.maxRuntimeMinutes() * 60_000L) {
            return "Run limit of " + rules.maxRuntimeMinutes() + " min reached.";
        }
        if (rules.manualOverride() && client.currentScreen == null && physicallyPressed(client)) {
            return "Manual input (movement key pressed).";
        }
        return null;
    }

    /** True when the player is holding a movement key on the real keyboard. */
    private static boolean physicallyPressed(MinecraftClient client) {
        KeyBinding[] keys = {client.options.forwardKey, client.options.backKey, client.options.leftKey, client.options.rightKey};
        for (KeyBinding key : keys) {
            InputUtil.Key bound = InputUtil.fromTranslationKey(key.getBoundKeyTranslationKey());
            if (bound.getCategory() == InputUtil.Type.KEYSYM && bound.getCode() > 0
                    && InputUtil.isKeyPressed(client.getWindow(), bound.getCode())) {
                return true;
            }
        }
        return false;
    }

    private void violate(Object owner, String reason) {
        violations++;
        watched = null;
        stop.accept(owner, reason);
    }

    private void reset(MinecraftClient client) {
        ClientPlayerEntity player = client.player;
        lastWorld = client.world;
        lastPos = player == null ? null : player.getEntityPos();
        lastHealth = player == null ? -1.0F : player.getHealth();
        startedAtMs = System.currentTimeMillis();
        ticks = 0;
    }
}
