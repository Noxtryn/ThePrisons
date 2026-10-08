package io.theprisons.items;

/** What the player sees in the sidebar of the item list (the technical subcategories are finer and stay inside the entries). */
public enum ItemCategory {
    LOOT("Loot"), ENCHANTS("Enchants"), PROGRESSION("Progression"), MINING("Mining"), COMBAT("Combat"), PETS("Pets"), MASKS("Masks"),
    POWERUPS("Powerups"), SPECIAL("Special"), MISC("Misc");

    private final String label;

    ItemCategory(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
