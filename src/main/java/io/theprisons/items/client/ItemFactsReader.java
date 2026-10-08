package io.theprisons.items.client;

import io.theprisons.items.ItemFacts;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.ItemEnchantmentsComponent;
import net.minecraft.component.type.LoreComponent;
import net.minecraft.component.type.NbtComponent;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.registry.Registries;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The only place that reads an {@link ItemStack} into {@link ItemFacts}. Call it when a stack appears or changes - never per render frame; the result is
 * immutable and everything else (classification, identity, market) works on it.
 */
public final class ItemFactsReader {
    private ItemFactsReader() {
    }

    public static ItemFacts read(ItemStack stack) {
        if (stack.isEmpty()) {
            return ItemFacts.ofName("minecraft:air", "");
        }
        String id = Registries.ITEM.getId(stack.getItem()).toString();
        Text nameText = stack.get(DataComponentTypes.CUSTOM_NAME);
        if (nameText == null) {
            nameText = stack.get(DataComponentTypes.ITEM_NAME);
        }
        String name = nameText == null ? "" : nameText.getString();
        List<String> lore = new ArrayList<>();
        LoreComponent loreComponent = stack.get(DataComponentTypes.LORE);
        if (loreComponent != null) {
            for (Text line : loreComponent.lines()) {
                lore.add(line.getString());
            }
        }
        Map<String, String> values = new LinkedHashMap<>();
        NbtComponent data = stack.get(DataComponentTypes.CUSTOM_DATA);
        if (data != null && !data.isEmpty()) {
            NbtCompound bukkit = data.copyNbt().getCompoundOrEmpty("PublicBukkitValues");
            for (String key : bukkit.getKeys()) {
                var element = bukkit.get(key);
                if (element != null) {
                    String k = key.startsWith("cosmicprisons:") ? key.substring("cosmicprisons:".length()) : key;
                    values.put(k, ItemFacts.plain(element.toString()));
                }
            }
        }
        List<String> enchants = new ArrayList<>();
        ItemEnchantmentsComponent ench = stack.get(DataComponentTypes.ENCHANTMENTS);
        if (ench != null) {
            for (var entry : ench.getEnchantmentEntries()) {
                enchants.add(entry.getKey().getKey().map(k -> k.getValue().getPath()).orElse("?") + " " + entry.getIntValue());
            }
        }
        return new ItemFacts(id, name, lore, values, enchants, stack.getCount());
    }
}
