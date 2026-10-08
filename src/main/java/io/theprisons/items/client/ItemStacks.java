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
 * The {@link ItemStack} of a registry entry, built ONCE and kept (keyed by the catalog key). The mod's item look reads the custom name to pick the texture,
 * so a stack with the vanilla carrier and the entry's name draws with the new textures. Never built per frame.
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
        if (entry.render().modelId() != null) {
            stack.set(DataComponentTypes.ITEM_MODEL, Identifier.of("theprisons", entry.render().modelId()));
        }
        return stack;
    }

    public static int cached() {
        return CACHE.size();
    }
}
