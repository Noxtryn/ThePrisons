package io.theprisons.items.client;

import io.theprisons.core.module.Category;
import io.theprisons.core.module.Module;
import io.theprisons.items.ItemsService;
import net.minecraft.client.MinecraftClient;

/** The keybind (and {@code /prisons items}) that opens the item list. */
public final class ItemListModule extends Module {
    public ItemListModule() {
        super("item_list", "Item List", Category.QOL, "Items",
                "All Cosmic Prisons items with their textures: categories, search, tiers and details. Opens with the I key (rebindable) and with /prisons items.", org.lwjgl.glfw.GLFW.GLFW_KEY_I);
    }

    @Override
    public boolean toggleable() {
        return false;
    }

    @Override
    public boolean onKeybind() {
        open();
        return true;
    }

    public static void open() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (ItemsService.get() != null) {
            client.execute(() -> client.setScreen(new ItemListScreen()));
        }
    }
}
