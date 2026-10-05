package com.freelocs.theprisons.core.module;

/** Top level GUI categories, tailored to Cosmic Prisons. Order = sidebar order. */
public enum Category {
    MINING("Mining", "⛏", "Ore macros and mining utilities"),
    METEOR_MINING("Meteor Mining", "☄", "Meteor and meteorite shower mining"),
    BANDIT("Bandit", "⚑", "Bandit rush helpers"),
    PVP("PvP", "⚔", "Player versus player utilities"),
    COMBAT("Combat", "✦", "Combat overlays and helpers"),
    QOL("QoL", "✿", "Quality of life"),
    HUD("HUD", "▣", "On-screen widgets"),
    GENERAL("General", "⚙", "Core, safety and performance");

    private final String label;
    private final String icon;
    private final String description;

    Category(String label, String icon, String description) {
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
}
