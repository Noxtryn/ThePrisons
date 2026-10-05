package io.theprisons.modules.qol.market;

import io.theprisons.ThePrisonsClient;
import io.theprisons.core.client.ClientReadouts;
import io.theprisons.core.client.TextStrip;
import io.theprisons.core.event.CoreEvents;
import io.theprisons.core.event.EventBus;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.NbtComponent;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Records the server's menus (chest screens: /ah, /ee, /gz, /pb, ...) to learn their layout before parsers are built
 * on them: the title and every slot of the menu part (not the player's inventory) with item id, count, name, lore and
 * the server's custom data, once the contents stood still for {@value #STABLE_TICKS} ticks. Every distinct page is
 * written once to {@code config/theprisons/menu_dumps/<date>.txt}.
 */
public final class MenuRecorder {
    private static final int STABLE_TICKS = 6;
    private static final int PLAYER_SLOTS = 36;

    private final Set<Integer> written = new HashSet<>();
    private int lastHash;
    private int stableTicks;
    private long checkedMs;
    private boolean on;

    public void register(EventBus bus) {
        bus.subscribe(CoreEvents.TickEnd.class, this, e -> tick(e.client()));
    }

    /** Only while the file {@code config/theprisons/record_menus} exists (a developer / debugging aid, off for users). */
    private boolean active() {
        long now = System.currentTimeMillis();
        if (now - checkedMs > 2_000L) {
            checkedMs = now;
            on = Files.exists(FabricLoader.getInstance().getConfigDir().resolve("theprisons").resolve("record_menus"));
        }
        return on;
    }

    private void tick(MinecraftClient client) {
        if (!active()) {
            return;
        }
        if (!(client.currentScreen instanceof HandledScreen<?> screen) || client.player == null) {
            stableTicks = 0;
            lastHash = 0;
            return;
        }
        ScreenHandler handler = screen.getScreenHandler();
        if (handler == client.player.playerScreenHandler) {
            return;
        }
        int menu = Math.max(0, handler.slots.size() - PLAYER_SLOTS);
        String title = TextStrip.strip(screen.getTitle().getString());
        int hash = title.hashCode();
        for (int i = 0; i < menu; i++) {
            ItemStack stack = handler.slots.get(i).getStack();
            hash = hash * 31 + (stack.isEmpty() ? 0 : stack.getName().getString().hashCode() * 7 + stack.getCount()
                    + ClientReadouts.lore(stack).hashCode());
        }
        if (hash != lastHash) {
            lastHash = hash;
            stableTicks = 0;
            return;
        }
        if (++stableTicks != STABLE_TICKS || !written.add(hash)) {
            return;
        }
        StringBuilder out = new StringBuilder();
        out.append("=== ").append(LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME))
                .append("  title='").append(title).append("'  type=").append(handler.getClass().getSimpleName())
                .append("  slots=").append(menu).append('\n');
        for (int i = 0; i < menu; i++) {
            Slot slot = handler.slots.get(i);
            ItemStack stack = slot.getStack();
            if (stack.isEmpty()) {
                continue;
            }
            out.append("[").append(i).append("] ").append(Registries.ITEM.getId(stack.getItem())).append(" x")
                    .append(stack.getCount()).append("  name='").append(TextStrip.strip(stack.getName().getString())).append("'\n");
            List<String> lore = ClientReadouts.lore(stack);
            for (String line : lore) {
                out.append("      | ").append(line).append('\n');
            }
            NbtComponent data = stack.get(DataComponentTypes.CUSTOM_DATA);
            if (data != null) {
                String nbt = data.copyNbt().toString();
                out.append("      data=").append(nbt.length() > 600 ? nbt.substring(0, 600) + "…" : nbt).append('\n');
            }
        }
        ThePrisonsClient.LOGGER.info("[menu_recorder] recorded '{}' ({} slots)", title, menu);
        Path dir = FabricLoader.getInstance().getConfigDir().resolve("theprisons").resolve("menu_dumps");
        Path file = dir.resolve(LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE) + ".txt");
        try {
            Files.createDirectories(dir);
            Files.writeString(file, out.toString(), StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            ThePrisonsClient.LOGGER.warn("[menu_recorder] could not write {}", file, e);
        }
    }
}
