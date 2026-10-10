package io.theprisons.gui.config;

import io.theprisons.core.module.Category;
import io.theprisons.core.module.Module;
import io.theprisons.core.setting.Setting;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * The categories of the one config GUI. Every module has exactly one home category (its name, status, keybind and
 * on/off switch); a module whose settings belong to several topics (the ore macro) shows each group in the category it
 * fits, see {@link #of}. Categories without a module stay visible with an explanation instead of invented features.
 */
public enum ConfigCategory {
    OVERVIEW("Overview", "◆", "All ThePrisons systems and their current state"),
    MINING("Mining", "⛏", "Ore macro, routes, waypoints and tunnel tools"),
    BANDIT("Bandit", "⚑", "Bandit macro and spear helper"),
    METEOR_MINING("Meteor Mining", "☄", "Meteor and meteorite shower mining"),
    PVP_COMBAT("PvP & Combat", "⚔", "Safety, recovery and combat warnings"),
    MARKET("Market", "◈", "Auction House, Energy Exchange, item list and price tracking"),
    HUD_OVERLAYS("HUD & Overlays", "▣", "On-screen widgets, layout and container overlays"),
    UTILITIES("Utilities", "✿", "Players, inventory, notifications and background services"),
    SETTINGS("Settings", "⚙", "Language, design and client preferences");

    private final String label;
    private final String icon;
    private final String description;

    ConfigCategory(String label, String icon, String description) {
        this.label = label;
        this.icon = icon;
        this.description = description;
    }

    public String label() {
        return label;
    }

    public String icon() {
        return icon;
    }

    public String description() {
        return description;
    }

    /** The category with the module's name, status, keybind and on/off switch. */
    public static ConfigCategory home(Module module) {
        return switch (module.id()) {
            case "ore_macro", "waypoint_editor", "tunnel_vision" -> MINING;
            case "bandit_macro", "spear_helper", "bandit_dodge_test" -> BANDIT;
            case "safety", "peaceful_mining", "vitals_warnings" -> PVP_COMBAT;
            case "market", "ah_overlay", "ee_overlay", "energy_overlay", "item_list" -> MARKET;
            case "session_hud", "session_stats", "scoreboard", "better_tab", "pet_hud", "command_cooldowns", "satchel_hud",
                 "armor_hud", "hud_layout", "tunnel_actionbar", "player_cards", "storage_overlay", "performance" -> HUD_OVERLAYS;
            case "click_gui", "design" -> SETTINGS;
            case "friends", "sneak_trade", "message_notifications", "ready_announcements", "cooldown_cache", "update_checker",
                 "item_insights" -> UTILITIES;
            default -> byEngineCategory(module.category());
        };
    }

    /** Fallback for a module without an explicit entry: its engine category. */
    private static ConfigCategory byEngineCategory(Category category) {
        return switch (category) {
            case MINING -> MINING;
            case METEOR_MINING -> METEOR_MINING;
            case BANDIT -> BANDIT;
            case PVP, COMBAT -> PVP_COMBAT;
            case HUD -> HUD_OVERLAYS;
            case QOL -> UTILITIES;
            case GENERAL -> SETTINGS;
        };
    }

    /** The category a group of the module's settings belongs to. */
    public static ConfigCategory of(Module module, String group) {
        if ("ore_macro".equals(module.id())) {
            return switch (group) {
                case "Item sorter", "Auto use" -> UTILITIES;
                case "Defence", "Breaks", "Recovery" -> PVP_COMBAT;
                default -> MINING;
            };
        }
        return home(module);
    }

    /** True when the category lists the setting (Overview lists every setting of every module). */
    public static boolean shows(Module module, Setting<?> setting, ConfigCategory category) {
        return category == OVERVIEW || of(module, setting.group()) == category;
    }

    /** The modules a category lists, in registration order: its home modules and those with settings in it. */
    public static List<Module> modulesIn(Collection<Module> all, ConfigCategory category) {
        if (category == OVERVIEW) {
            return new ArrayList<>(all);
        }
        List<Module> list = new ArrayList<>();
        for (Module module : all) {
            if (home(module) == category) {
                list.add(module);
                continue;
            }
            for (Setting<?> setting : module.settings()) {
                if (setting != module.keybind() && setting.visible() && of(module, setting.group()) == category) {
                    list.add(module);
                    break;
                }
            }
        }
        return list;
    }
}
