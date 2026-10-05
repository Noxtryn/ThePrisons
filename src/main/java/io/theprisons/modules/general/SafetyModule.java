package io.theprisons.modules.general;

import io.theprisons.core.module.Category;
import io.theprisons.core.module.Module;
import io.theprisons.core.safety.SafetyMonitor;
import io.theprisons.core.setting.Settings;

/**
 * Settings of the core {@link SafetyMonitor}, which guards every macro. Enabled by default; disabling it keeps only
 * the unconditional stops (death, world change).
 */
public final class SafetyModule extends Module {
    private final SafetyMonitor monitor;
    private final Settings.BoolSetting stopOnDamage;
    private final Settings.IntSetting lowHealth;
    private final Settings.BoolSetting inventoryFull;
    private final Settings.IntSetting maxRuntime;
    private final Settings.IntSetting teleportDistance;
    private final Settings.BoolSetting manualOverride;
    private final Settings.BoolSetting resumeAfterTeleport;
    private final Settings.BoolSetting restartAfterError;

    public SafetyModule(SafetyMonitor monitor) {
        super("safety", "Safety", Category.GENERAL, "Core",
                "Stops a running macro when something needs your attention: damage, low health, a run limit or when you "
                        + "press a movement key. Teleports / mine resets and internal errors do not end a long run.",
                Settings.KeybindSetting.NONE);
        this.monitor = monitor;
        stopOnDamage = bool("stop_on_damage", "Stop on damage", true).group("Health");
        lowHealth = integer("low_health", "Stop at health", 8, 0, 20, 1).suffix(" HP")
                .description("0 disables the check.").group("Health");
        inventoryFull = bool("inventory_full", "Stop when inventory is full", false)
                .description("Off: keep mining (further drops stay on the ground / go to satchels).").group("Inventory");
        maxRuntime = integer("max_runtime", "Run limit", 0, 0, 600, 5).suffix(" min")
                .description("Stop a macro after this many minutes; 0 = unlimited.").group("Limits");
        teleportDistance = integer("teleport_distance", "Teleport distance", 8, 0, 64, 1).suffix(" blocks")
                .description("Moving further than this within one tick counts as a teleport / mine reset; 0 disables the check.").group("Limits");
        manualOverride = bool("manual_override", "Stop on manual input", true)
                .description("Pressing a movement key hands control back to you.").group("Limits");
        resumeAfterTeleport = bool("resume_after_teleport", "Continue after teleport", true)
                .description("A teleport / mine reset restarts the planning at the new position instead of stopping. "
                        + "It does not explore until it has mined something there again.").group("Continuous operation");
        restartAfterError = bool("restart_after_error", "Restart after errors", true)
                .description("A macro that stopped because of an internal error is started again after 5 s "
                        + "(at most 3 times in 10 minutes).").group("Continuous operation");
        for (var setting : settings()) {
            setting.onChange(value -> push());
        }
        push();
    }

    @Override
    public boolean enabledByDefault() {
        return true;
    }

    @Override
    protected void onEnable() {
        push();
    }

    @Override
    protected void onDisable() {
        push();
    }

    private void push() {
        monitor.setRules(new SafetyMonitor.Rules(enabled(), stopOnDamage.on(), lowHealth.value(), inventoryFull.on(),
                maxRuntime.value(), teleportDistance.value(), manualOverride.on(), resumeAfterTeleport.on(), restartAfterError.on()));
    }
}
