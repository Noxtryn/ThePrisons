package com.freelocs.theprisons.modules.qol.market;

import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenKeyboardEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;

/**
 * Wires the market search (inventory) and the AH / /ee cards (server menus) into the screens with Fabric's screen
 * events, so no mixin has to target input methods (their owners moved between Minecraft versions).
 */
public final class MarketScreenHooks {
    private MarketScreenHooks() {
    }

    public static void register() {
        ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
            if (!(screen instanceof HandledScreen<?> handled)) {
                return;
            }
            ScreenEvents.afterRender(screen).register((s, context, mouseX, mouseY, delta) -> {
                if (s instanceof InventoryScreen inventory) {
                    MarketSearch.render(context, MinecraftClient.getInstance().textRenderer, inventory.width, inventory.height);
                }
                MarketOverlay.render(context, handled);
            });
            if (screen instanceof InventoryScreen inventory) {
                ScreenKeyboardEvents.allowKeyPress(screen).register((s, input) -> !enabled()
                        || !MarketSearch.keyPressed(input.key(), input.scancode()));
                ScreenMouseEvents.allowMouseClick(screen).register((s, click) -> !enabled()
                        || !MarketSearch.click(click.x(), click.y(), inventory.width, inventory.height));
                ScreenMouseEvents.allowMouseScroll(screen).register((s, x, y, horizontal, vertical) -> !enabled()
                        || !MarketSearch.scroll(vertical));
            } else {
                ScreenMouseEvents.allowMouseClick(screen).register((s, click) -> !enabled()
                        || !MarketOverlay.click(click.x(), click.y()));
            }
        });
    }

    private static boolean enabled() {
        MarketModule m = MarketModule.get();
        return m != null && m.enabled();
    }
}
