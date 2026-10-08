package io.theprisons.core.cosmic.parse;

/** What counts as a spear. The spear helper used this exact rule on its own; it now delegates here. */
public final class SpearRule {
    private SpearRule() {
    }

    /** @param path the item id's path without namespace ("netherite_spear", "trident") */
    public static boolean isSpearPath(String path) {
        return path.contains("spear") || path.equals("trident");
    }

    /** @param itemId the full id ("minecraft:trident") */
    public static boolean isSpear(String itemId) {
        int colon = itemId.indexOf(':');
        return isSpearPath(colon >= 0 ? itemId.substring(colon + 1) : itemId);
    }
}
