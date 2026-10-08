package io.theprisons.items;

import org.jspecify.annotations.Nullable;

/**
 * What the client needs to draw an item: the vanilla item that carries it and the name that the item look reads its texture from (plus a model of its own
 * where the mod has one, e.g. a mask). Plain data - the actual {@code ItemStack} is built once per entry by the client cache, never per frame.
 */
public record ItemRenderData(String vanillaIcon, String displayName, @Nullable String modelId) {
}
