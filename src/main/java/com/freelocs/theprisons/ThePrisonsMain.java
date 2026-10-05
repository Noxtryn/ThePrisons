package com.freelocs.theprisons;

import net.fabricmc.api.ModInitializer;
import net.minecraft.item.Item;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.util.Identifier;

/**
 * Registers the mod's logo as an item of its own, {@code minecraft:theprisons}, and an icon item for each of the menus
 * {@code /ah}, {@code /ee}, {@code /tinker} and {@code /skilltree} (texture
 * {@code assets/minecraft/textures/item/theprisons.png}, the mod icon): usable as an icon id anywhere an item id is
 * accepted and with {@code /give @s minecraft:theprisons} in a world that knows it. Items can only be registered before
 * the registries freeze, so this is a main entrypoint, not part of the client initializer.
 */
public final class ThePrisonsMain implements ModInitializer {
    public static final Identifier LOGO_ID = Identifier.ofVanilla("theprisons");
    public static Item logo;
    /** The menu icons: minecraft:ah, minecraft:ee, minecraft:tinker, minecraft:skilltree. */
    public static final String[] MENU_ICONS = {"ah", "ee", "tinker", "skilltree"};

    @Override
    public void onInitialize() {
        logo = register(LOGO_ID);
        for (String name : MENU_ICONS) {
            register(Identifier.ofVanilla(name));
        }
    }

    private static Item register(Identifier id) {
        try {
            RegistryKey<Item> key = RegistryKey.of(RegistryKeys.ITEM, id);
            return Registry.register(Registries.ITEM, id, new Item(new Item.Settings().registryKey(key).maxCount(1)));
        } catch (RuntimeException e) {
            ThePrisonsClient.LOGGER.warn("Could not register the item {}", id, e);
            return null;
        }
    }
}
