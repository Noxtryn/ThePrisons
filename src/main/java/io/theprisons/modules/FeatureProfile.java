package io.theprisons.modules;

import io.theprisons.core.module.Module;
import org.jspecify.annotations.Nullable;

import java.util.Set;

/**
 * <b>What every user gets.</b> The mod ships with the features listed in {@link #ON} switched on. Users may switch
 * each of them off (and on again) in the config GUI, except the {@link #CORE} services, which are always active.
 * Everything not listed is off and not part of the user build. Edit this file to change the shipped feature set.
 *
 * <p>Start the game with {@code -Dtheprisons.dev=true} for the developer build: the profile is not enforced.
 * Mining macros remain normal user-configurable modules, while developer-only legacy features stay locked out.
 */
public final class FeatureProfile {
    /** Developer build (JVM flag {@code -Dtheprisons.dev=true}). */
    public static final boolean DEV = Boolean.getBoolean("theprisons.dev");

    /** Features that are always on for users. */
    public static final Set<String> ON = Set.of(
            // Storage & items
            "storage_overlay", "item_list", "ah_overlay", "ee_overlay", "energy_overlay",
            // HUD
            "scoreboard", "better_tab", "session_hud", "pet_hud", "command_cooldowns", "satchel_hud", "armor_hud", "item_insights",
            // Quality of life
            "tunnel_vision", "tunnel_actionbar", "sneak_trade", "player_cards", "friends", "message_notifications", "peaceful_mining", "vitals_warnings", "ready_announcements", "market",
            // Background services
            "cooldown_cache", "update_checker");

    /** Always-on background services: no switch in the GUI (their settings stay editable). */
    public static final Set<String> CORE = Set.of("cooldown_cache", "update_checker");

    /** Modules that are user-configurable even though the shipped feature profile is otherwise fixed. */
    public static final Set<String> FREE = Set.of("ore_macro", "waypoint_editor", "spear_helper", "bandit_macro");

    /** Not part of the user mod (off and invisible; their code stays for the developer build). */
    public static final Set<String> REMOVED = Set.of("safety", "performance", "session_stats");

    /** Settings-only modules the dashboard shows on its own pages (not as features). */
    public static final Set<String> SYSTEM = Set.of("click_gui", "design", "hud_layout");

    private FeatureProfile() {
    }

    /** What the config GUI offers for a module. */
    public enum Kind {
        /** A real on/off switch (lifecycle, event registration and persistence behind it). */
        SWITCHABLE,
        /** Always active core service: no switch. */
        CORE,
        /** Settings only, nothing to switch (design, GUI, HUD layout). */
        SETTINGS_ONLY,
        /** Not part of this build (removed or developer-only): not listed in the GUI. */
        NOT_IN_BUILD
    }

    /** The state a module must have, or {@code null} when the player may switch it. */
    public static @Nullable Boolean forced(Module module) {
        if (DEV || SYSTEM.contains(module.id()) || FREE.contains(module.id()) || ON.contains(module.id()) && !CORE.contains(module.id())) {
            return null;
        }
        return CORE.contains(module.id());
    }

    public static Kind kind(Module module) {
        if (SYSTEM.contains(module.id()) || !module.toggleable()) {
            return Kind.SETTINGS_ONLY;
        }
        if (DEV) {
            return Kind.SWITCHABLE;
        }
        if (CORE.contains(module.id())) {
            return Kind.CORE;
        }
        return feature(module) ? Kind.SWITCHABLE : Kind.NOT_IN_BUILD;
    }

    /** Shown as a feature tile on the dashboard. */
    public static boolean feature(Module module) {
        return ON.contains(module.id()) || FREE.contains(module.id());
    }
}
