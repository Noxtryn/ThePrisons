package io.theprisons.items.client;

import io.theprisons.items.ItemEntry;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

import java.util.HashMap;
import java.util.Map;

/**
 * The {@link ItemStack} of a registry entry, built ONCE and kept (keyed by the catalog key): the vanilla carrier with the entry's name. Never built per
 * frame. {@code ItemRenderData.modelId} is not applied while the item look is paused (src/paused/cosmic-items) - its models are not shipped.
 */
public final class ItemStacks {
    private static final Map<String, ItemStack> CACHE = new HashMap<>();

    private ItemStacks() {
    }

    public static ItemStack of(ItemEntry entry) {
        return CACHE.computeIfAbsent(entry.key(), k -> build(entry));
    }

    private static ItemStack build(ItemEntry entry) {
        Item item = Registries.ITEM.get(Identifier.of(entry.render().vanillaIcon()));
        ItemStack stack = new ItemStack(item == Items.AIR ? Items.PAPER : item);
        stack.set(DataComponentTypes.CUSTOM_NAME, Text.literal(entry.render().displayName()));
        return stack;
    }

    public static int cached() {
        return CACHE.size();
    }
}
