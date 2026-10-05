package com.freelocs.theprisons.modules;

import com.freelocs.theprisons.core.module.Module;
import org.jspecify.annotations.Nullable;

import java.util.Set;

/**
 * <b>What every user gets.</b> The mod ships finished: the features listed in {@link #ON} are always on, everything
 * else is off, and users cannot switch features - the dashboard only lets them change the design, the HUD layout
 * (HUD editor) and their keybinds. Edit this file to change the shipped feature set.
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
            "storage_overlay", "item_look",
            // HUD
            "scoreboard", "better_tab", "session_hud", "pet_hud", "command_cooldowns", "satchel_hud", "armor_hud", "item_insights",
            // Quality of life
            "sneak_trade", "player_cards", "friends", "message_notifications", "peaceful_mining", "vitals_warnings", "ready_announcements",
            // Background services
            "cooldown_cache", "update_checker");

    /** Modules that are user-configurable even though the shipped feature profile is otherwise fixed. */
    public static final Set<String> FREE = Set.of("ore_macro", "waypoint_editor");

    /** Not part of the user mod (off and invisible; their code stays for the developer build). */
    public static final Set<String> REMOVED = Set.of("safety", "performance", "session_stats");

    /** Settings-only modules the dashboard shows on its own pages (not as features). */
    public static final Set<String> SYSTEM = Set.of("click_gui", "design", "hud_layout");

    private FeatureProfile() {
    }

    /** The state a module must have, or {@code null} when it is free (developer build). */
    public static @Nullable Boolean forced(Module module) {
        if (DEV) {
            return null;
        }
        if (SYSTEM.contains(module.id()) || FREE.contains(module.id())) {
            return null;
        }
        return ON.contains(module.id());
    }

    /** Shown as a feature tile on the dashboard. */
    public static boolean feature(Module module) {
        return ON.contains(module.id()) || FREE.contains(module.id());
    }
}
