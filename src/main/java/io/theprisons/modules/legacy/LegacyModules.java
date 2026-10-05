package io.theprisons.modules.legacy;

import io.theprisons.ThePrisonsClient;
import io.theprisons.config.ThePrisonsConfig;
import io.theprisons.core.module.Category;
import io.theprisons.core.module.ModuleManager;
import io.theprisons.feature.ThePrisonsFeatureManager;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Registers the v1 features as {@link LegacyModule}s so they appear in the new GUI with their existing settings.
 * Their behaviour code is untouched; they will be migrated onto the core services one by one.
 */
public final class LegacyModules {
    /** Defaults of the v1 config, used as the settings' reset values. */
    private static final ThePrisonsConfig DEFAULTS = new ThePrisonsConfig();

    private LegacyModules() {
    }

    private static ThePrisonsConfig cfg() {
        return ThePrisonsClient.CONFIG.get();
    }

    public static void register(ModuleManager modules, Runnable changed, Runnable openHudLayout) {
        // ── HUD ──────────────────────────────────────────────────────────────
        LegacyModule pets = legacy(modules, "pet_hud", "Pets & Trinkets", Category.HUD, "Widgets",
                "Cooldowns of pets and trinkets, persisted across relogs. Also shows the Session XP and Energy widgets.",
                () -> cfg().hud.petHudEnabled, v -> cfg().hud.petHudEnabled = v, changed);
        pets.boundBool("ready_status", "Show ready status", DEFAULTS.hud.showReadyStatus, () -> cfg().hud.showReadyStatus, v -> cfg().hud.showReadyStatus = v);
        pets.boundBool("countdown", "Show countdown", DEFAULTS.hud.showCountdown, () -> cfg().hud.showCountdown, v -> cfg().hud.showCountdown = v);
        pets.boundColor("color", "Text colour", DEFAULTS.hud.petHudColor, () -> cfg().hud.petHudColor, v -> cfg().hud.petHudColor = v).group("Style");
        pets.boundColor("ready_color", "Ready colour", DEFAULTS.hud.readyColor, () -> cfg().hud.readyColor, v -> cfg().hud.readyColor = v).group("Style");
        pets.boundDecimal("scale", "Scale", DEFAULTS.gui.petHudScale, 0.5D, 2.5D, 0.05D, () -> cfg().gui.petHudScale, v -> cfg().gui.petHudScale = v.floatValue()).group("Style");

        legacy(modules, "command_cooldowns", "Command Cooldowns", Category.HUD, "Widgets",
                "Cooldowns of /jet, /fix, /feed, /near and other commands.",
                () -> cfg().hud.showCommandCooldowns, v -> cfg().hud.showCommandCooldowns = v, changed);

        LegacyModule satchels = legacy(modules, "satchel_hud", "Satchels", Category.HUD, "Widgets",
                "Satchel fill levels with combined same-type satchels.",
                () -> cfg().hud.showSatchelHud, v -> cfg().hud.showSatchelHud = v, changed);
        satchels.boundBool("warnings", "Fill warnings", DEFAULTS.hud.showSatchelWarnings, () -> cfg().hud.showSatchelWarnings, v -> cfg().hud.showSatchelWarnings = v);
        satchels.boundInt("warning_percent", "Warn at", DEFAULTS.hud.satchelWarningPercent, 50, 100, 5, () -> cfg().hud.satchelWarningPercent, v -> cfg().hud.satchelWarningPercent = v).suffix("%");

        LegacyModule session = legacy(modules, "session_stats", "Session Stats", Category.HUD, "Widgets",
                "Session XP, Cosmic Energy and per-hour rates.",
                () -> cfg().hud.showStatsHud, v -> cfg().hud.showStatsHud = v, changed);
        session.boundBool("paused", "Paused", DEFAULTS.hud.pauseStatsHud, () -> cfg().hud.pauseStatsHud, ThePrisonsFeatureManager::setStatsPaused);
        session.button("reset", "Session totals", "Reset", ThePrisonsFeatureManager::resetStats);
        session.boundDecimal("xp_scale", "XP widget scale", DEFAULTS.gui.sessionXpHudScale, 0.5D, 2.5D, 0.05D, () -> cfg().gui.sessionXpHudScale, v -> cfg().gui.sessionXpHudScale = v.floatValue()).group("Style");
        session.boundDecimal("energy_scale", "Energy widget scale", DEFAULTS.gui.energyHudScale, 0.5D, 2.5D, 0.05D, () -> cfg().gui.energyHudScale, v -> cfg().gui.energyHudScale = v.floatValue()).group("Style");

        LegacyModule armor = legacy(modules, "armor_hud", "Armor", Category.HUD, "Widgets",
                "Armor durability with critical alerts.",
                () -> cfg().hud.showArmorHud, v -> cfg().hud.showArmorHud = v, changed);
        armor.boundBool("warnings", "Durability warnings", DEFAULTS.hud.showArmorWarnings, () -> cfg().hud.showArmorWarnings, v -> cfg().hud.showArmorWarnings = v);
        armor.boundDecimal("scale", "Scale", DEFAULTS.hud.armorHudScale, 0.75D, 1.35D, 0.05D, () -> cfg().hud.armorHudScale, v -> cfg().hud.armorHudScale = v.floatValue()).group("Style");
        armor.boundInt("icon_size", "Icon size", DEFAULTS.hud.armorHudIconSize, 10, 20, 1, () -> cfg().hud.armorHudIconSize, v -> cfg().hud.armorHudIconSize = v).group("Style");
        armor.boundInt("gap", "Gap", DEFAULTS.hud.armorHudGap, 0, 8, 1, () -> cfg().hud.armorHudGap, v -> cfg().hud.armorHudGap = v).group("Style");

        LegacyModule insights = legacy(modules, "item_insights", "Item Insights", Category.HUD, "Items",
                "Extra tooltip lines and clue scroll step overlays.",
                () -> cfg().hud.showItemInsightTooltips, v -> cfg().hud.showItemInsightTooltips = v, changed);
        insights.boundBool("clue_steps", "Clue scroll steps", DEFAULTS.hud.showClueScrollSteps, () -> cfg().hud.showClueScrollSteps, v -> cfg().hud.showClueScrollSteps = v);

        LegacyModule layout = legacy(modules, "hud_layout", "HUD Layout", Category.HUD, "Layout",
                "Drag and resize the HUD widgets.", null, null, changed);
        layout.button("open", "Layout editor", "Open", openHudLayout);
        layout.boundBool("snap", "Snap to grid", DEFAULTS.gui.snapToGrid, () -> cfg().gui.snapToGrid, v -> cfg().gui.snapToGrid = v);
        layout.boundInt("grid", "Grid size", DEFAULTS.gui.gridSize, 6, 32, 1, () -> cfg().gui.gridSize, v -> cfg().gui.gridSize = v).suffix(" px");
        layout.boundDecimal("module_hud_scale", "Module widget scale", DEFAULTS.gui.miningHudScale, 0.5D, 2.5D, 0.05D, () -> cfg().gui.miningHudScale, v -> cfg().gui.miningHudScale = v.floatValue());

        // ── QoL ──────────────────────────────────────────────────────────────
        LegacyModule messages = legacy(modules, "message_notifications", "Message Notifications", Category.QOL, "Chat",
                "Notification (and sound) when you are mentioned or receive a private message.",
                () -> cfg().qol.messageNotifications, v -> cfg().qol.messageNotifications = v, changed);
        messages.boundBool("sound", "Sound", DEFAULTS.qol.messageNotificationSound, () -> cfg().qol.messageNotificationSound, v -> cfg().qol.messageNotificationSound = v);
        messages.boundDecimal("volume", "Volume", DEFAULTS.qol.messageNotificationVolume, 0.0D, 2.0D, 0.05D, () -> cfg().qol.messageNotificationVolume, v -> cfg().qol.messageNotificationVolume = v.floatValue());

        legacy(modules, "peaceful_mining", "Peaceful Mining Safety", Category.QOL, "Safety",
                "Blocks hitting players while holding a mining tool.",
                () -> cfg().qol.peacefulMiningSafety, v -> cfg().qol.peacefulMiningSafety = v, changed);

        legacy(modules, "vitals_warnings", "Low Vitals Warnings", Category.QOL, "Safety",
                "Warns when health or hunger get low.",
                () -> cfg().hud.lowVitalsWarnings, v -> cfg().hud.lowVitalsWarnings = v, changed);

        // ── General ──────────────────────────────────────────────────────────
        LegacyModule announcements = legacy(modules, "ready_announcements", "Ready Announcements", Category.GENERAL, "Notifications",
                "Announces pets, trinkets and commands when they are ready again.",
                () -> cfg().general.showReadyAnnouncements, v -> cfg().general.showReadyAnnouncements = v, changed);
        announcements.boundBool("powerball", "Powerball ready alert", DEFAULTS.hud.powerballReadyAlert, () -> cfg().hud.powerballReadyAlert, v -> cfg().hud.powerballReadyAlert = v);
        announcements.boundColor("title_color", "Alert title colour", DEFAULTS.hud.alertTitleColor, () -> cfg().hud.alertTitleColor, v -> cfg().hud.alertTitleColor = v).group("Style");
        announcements.boundColor("body_color", "Alert text colour", DEFAULTS.hud.alertBodyColor, () -> cfg().hud.alertBodyColor, v -> cfg().hud.alertBodyColor = v).group("Style");

        legacy(modules, "cooldown_cache", "Cooldown Cache", Category.GENERAL, "Core",
                "Remembers pet and trinket cooldowns across relogs and restarts.",
                () -> cfg().general.persistCooldownCache, v -> cfg().general.persistCooldownCache = v, changed);

        LegacyModule updates = legacy(modules, "update_checker", "Update Checker", Category.GENERAL, "Core",
                "Checks Modrinth for new versions.",
                () -> cfg().general.autoUpdaterEnabled, v -> cfg().general.autoUpdaterEnabled = v, changed);
        updates.boundInt("interval", "Check every", DEFAULTS.general.updateCheckIntervalHours, 1, 168, 1,
                () -> cfg().general.updateCheckIntervalHours, v -> cfg().general.updateCheckIntervalHours = v).suffix(" h");
    }

    private static LegacyModule legacy(ModuleManager modules, String id, String name, Category category, String group, String description,
                                       BooleanSupplier flag, Consumer<Boolean> setFlag, Runnable changed) {
        return modules.register(new LegacyModule(id, name, category, group, description, flag, setFlag, changed));
    }
}
