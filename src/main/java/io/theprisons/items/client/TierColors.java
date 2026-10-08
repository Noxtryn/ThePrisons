package io.theprisons.items.client;

import io.theprisons.items.ItemRarity;

/** The colour a tier is drawn in (the mod's own UI choice, matching the item textures where the tier exists there). */
public final class TierColors {
    private TierColors() {
    }

    public static int rgb(String tier) {
        return ItemRarity.rgb(tier);
    }
}
