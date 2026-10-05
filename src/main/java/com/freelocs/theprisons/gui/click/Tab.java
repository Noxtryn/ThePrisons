package com.freelocs.theprisons.gui.click;

import com.freelocs.theprisons.core.module.Category;
import com.freelocs.theprisons.core.module.Module;

/**
 * The Click GUI's tabs: by topic, not by module. A module's settings are split over the tabs by their group (the ore
 * macro's item sorter sits under Inventory &amp; Loot, its defence under Safety &amp; Recovery, its movement under Mining
 * &amp; Pathfinding); a module's name, description, status, keybind and on/off switch sit in its home tab.
 */
public enum Tab {
    MINING("Mining & Pathfinding", "⛏", "Tunnel centring, the stone-block rule, planned routes and route memory"),
    INVENTORY("Inventory & Loot", "▤", "Item sorter, private vaults, inventory limit and auto use"),
    SAFETY("Safety & Recovery", "⛨", "Anti-stuck, combat failsafe, guard look-ahead and the guarded area"),
    HUD("HUD & Metrics", "▣", "Session HUD, its rows and the other widgets"),
    OTHER("More modules", "⚙", "The remaining modules (v1 features, core tools)");

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
            default -> module.category() == Category.HUD ? HUD : module.category() == Category.MINING ? MINING : OTHER;
        };
    }

    /** The tab a group of the module's settings belongs to. */
    public static Tab of(Module module, String group) {
        if ("ore_macro".equals(module.id())) {
            return switch (group) {
                case "Item sorter", "Auto use" -> INVENTORY;
                case "Defence", "Breaks", "Recovery" -> SAFETY;
                default -> MINING;
            };
        }
        return home(module);
    }
}
