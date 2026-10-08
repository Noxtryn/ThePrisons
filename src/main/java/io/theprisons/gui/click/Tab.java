package io.theprisons.gui.click;

import io.theprisons.core.module.Category;
import io.theprisons.core.module.Module;

/**
 * The Click GUI's tabs: by topic, not by module. A module's settings are split over the tabs by their group (the ore
 * macro's item sorter sits under Inventory &amp; Loot, its defence under Safety &amp; Recovery, its movement under Mining
 * &amp; Pathfinding); a module's name, description, status, keybind and on/off switch sit in its home tab.
 */
public enum Tab {
    OVERVIEW("Overview", "◆", "All available ThePrisons systems and their current state"),
    MINING("Mining", "⛏", "Ore routes, tunnel centring and mining utilities"),
    BANDITS("Bandit", "⚑", "Bandit and spear-assistant controls"),
    MARKET("Market", "◈", "Auction House, Energy Exchange and price observations"),
    ITEMS("Item Tools", "▤", "Item list, tooltips, inventory and loot utilities"),
    HUD("HUD", "▣", "Session HUD, widgets and display controls"),
    SAFETY("PvP & Safety", "⛨", "Combat safety, recovery and guarded movement"),
    SETTINGS("Settings", "⚙", "Core tools and client preferences");

    private final String label;
    private final String icon;
    private final String description;

    Tab(String label, String icon, String description) {
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

    /** The tab with the module's name, status, keybind and on/off switch. */
    public static Tab home(Module module) {
        return switch (module.id()) {
            case "ore_macro", "waypoint_editor" -> MINING;
            case "session_hud" -> HUD;
            case "safety" -> SAFETY;
            case "spear_helper" -> BANDITS;
            case "market", "ah_overlay", "ee_overlay", "energy_overlay" -> MARKET;
            case "item_list", "item_look", "storage_overlay" -> ITEMS;
            default -> module.category() == Category.BANDIT ? BANDITS : module.category() == Category.HUD ? HUD : module.category() == Category.MINING ? MINING : SETTINGS;
        };
    }

    /** The tab a group of the module's settings belongs to. */
    public static Tab of(Module module, String group) {
        if ("ore_macro".equals(module.id())) {
            return switch (group) {
                case "Item sorter", "Auto use" -> ITEMS;
                case "Defence", "Breaks", "Recovery" -> SAFETY;
                default -> MINING;
            };
        }
        return home(module);
    }
}
