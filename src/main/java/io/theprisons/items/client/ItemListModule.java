package io.theprisons.items.client;

import io.theprisons.core.module.Category;
import io.theprisons.core.module.Module;
import io.theprisons.core.setting.Settings;
import org.jspecify.annotations.Nullable;

/**
 * The item list of the inventory (search bar above the hotbar, panel beside the inventory). Always on; this module only carries the switch and the place
 * of the feature in the dashboard - it has no key and opens no screen.
 */
public final class ItemListModule extends Module {
    private static @Nullable ItemListModule instance;

    public ItemListModule() {
        super("item_list", "Item List", Category.QOL, "Items",
                "Search all Cosmic Prisons items right in your inventory: type anywhere to search, double-click the bar to list everything. "
                        + "Categories, tiers and details sit in a panel beside the inventory.", Settings.KeybindSetting.NONE);
        instance = this;
    }

    public static @Nullable ItemListModule get() {
        return instance;
    }

    @Override
    protected void onEnable() {
        // the inventory closed (not by Esc): the search is forgotten with it
        on(io.theprisons.core.event.CoreEvents.TickEnd.class, event -> {
            if (!(event.client().currentScreen instanceof net.minecraft.client.gui.screen.ingame.InventoryScreen)) {
                InventoryItemList.onInventoryClosed();
            }
        });
    }

    @Override
    public boolean toggleable() {
        return false;
    }
}
