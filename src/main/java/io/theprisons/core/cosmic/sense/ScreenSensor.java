package io.theprisons.core.cosmic.sense;

import io.theprisons.core.client.TextStrip;
import io.theprisons.core.cosmic.data.Raw;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.screen.PlayerScreenHandler;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;

import java.util.ArrayList;
import java.util.List;

/** The open screen: its title and, for containers, the visible slots (the opened container, not the player's inventory). */
public final class ScreenSensor {
    public static final int MAX_SLOTS = 100;
    private final StackReader.Cache stacks = new StackReader.Cache();

    public Raw.Screen sample(MinecraftClient client, boolean withSlots) {
        Screen screen = client.currentScreen;
        if (screen == null) {
            stacks.clear();
            return Raw.Screen.NONE;
        }
        String title = TextStrip.strip(screen.getTitle().getString());
        if (!(screen instanceof HandledScreen<?> handled)) {
            return new Raw.Screen(title, screen.getClass().getSimpleName(), false, List.of());
        }
        List<Raw.Slot> slots = new ArrayList<>();
        if (withSlots && client.player != null) {
            ScreenHandler handler = handled.getScreenHandler();
            boolean ownInventory = handler instanceof PlayerScreenHandler;
            for (Slot slot : handler.slots) {
                if (slots.size() >= MAX_SLOTS) {
                    break;
                }
                if (!ownInventory && slot.inventory == client.player.getInventory()) {
                    continue;
                }
                if (slot.hasStack()) {
                    slots.add(new Raw.Slot(slot.id, stacks.read(200 + slot.id, slot.getStack())));
                }
            }
        }
        return new Raw.Screen(title, screen.getClass().getSimpleName(), true, slots);
    }

    public void clear() {
        stacks.clear();
    }
}
