package com.freelocs.theprisons.modules.qol.market;

import com.freelocs.theprisons.ThePrisonsClient;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.LoreComponent;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.RegistryOps;
import net.minecraft.text.Text;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The real item of every market entry, as the server sent it (name, colours, lore, custom data): what the item list
 * draws with the mod's own textures and shows on hover, exactly like the item in the game. Kept in
 * {@code config/theprisons/market/catalog.json}; the server's own market lines (price, seller, "click to buy") are cut
 * off, so only the item stays.
 */
public final class ItemCatalog {
    private final Map<String, JsonElement> json = new LinkedHashMap<>();
    private final Map<String, ItemStack> decoded = new HashMap<>();
    private boolean dirty;

    public boolean dirty() {
        return dirty;
    }

    public int size() {
        return json.size();
    }

    /** A listing / sale item of a menu: its stack without the market lines, one piece. */
    public void put(String key, ItemStack menuStack) {
        ClientPlayNetworkHandler handler = MinecraftClient.getInstance().getNetworkHandler();
        if (key.isEmpty() || handler == null || menuStack.isEmpty()) {
            return;
        }
        ItemStack clean = trimmed(menuStack);
        try {
            RegistryOps<JsonElement> ops = RegistryOps.of(JsonOps.INSTANCE, handler.getRegistryManager());
            JsonElement encoded = ItemStack.CODEC.encodeStart(ops, clean).result().orElse(null);
            if (encoded != null && !encoded.equals(json.get(key))) {
                json.put(key, encoded);
                decoded.remove(key);
                dirty = true;
            }
        } catch (RuntimeException e) {
            ThePrisonsClient.LOGGER.warn("[market] could not store the item of {}", key, e);
        }
    }

    /** The stack of an entry; null = none stored (or not readable yet). */
    public @Nullable ItemStack stack(String key) {
        ItemStack cached = decoded.get(key);
        if (cached != null) {
            return cached;
        }
        JsonElement stored = json.get(key);
        ClientPlayNetworkHandler handler = MinecraftClient.getInstance().getNetworkHandler();
        if (stored == null || handler == null) {
            return null;
        }
        try {
            RegistryOps<JsonElement> ops = RegistryOps.of(JsonOps.INSTANCE, handler.getRegistryManager());
            ItemStack stack = ItemStack.CODEC.parse(ops, stored).result().orElse(null);
            if (stack != null) {
                decoded.put(key, stack);
            }
            return stack;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** The menu item without the lines the server's market added at the end (separator / "Price:" / seller / expiry). */
    static ItemStack trimmed(ItemStack menuStack) {
        ItemStack copy = menuStack.copy();
        copy.setCount(1);
        LoreComponent lore = copy.get(DataComponentTypes.LORE);
        if (lore != null) {
            List<Text> lines = lore.lines();
            int cut = -1;
            for (int i = 0; i < lines.size(); i++) {
                String plain = lines.get(i).getString().strip();
                if (plain.startsWith("Price:")) {
                    cut = i;
                    break;
                }
            }
            if (cut >= 0) {
                while (cut > 0) {
                    String prev = lines.get(cut - 1).getString().strip();
                    if (prev.isEmpty() || prev.startsWith("---") || prev.startsWith("Click item") || prev.equalsIgnoreCase("CLICK TO BUY")) {
                        cut--;
                    } else {
                        break;
                    }
                }
                copy.set(DataComponentTypes.LORE, new LoreComponent(new ArrayList<>(lines.subList(0, cut))));
            }
        }
        return copy;
    }

    // ── File ─────────────────────────────────────────────────────────────────

    public void load(Path file) {
        if (!Files.isRegularFile(file)) {
            return;
        }
        try {
            JsonObject root = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
            for (Map.Entry<String, JsonElement> e : root.entrySet()) {
                json.put(e.getKey(), e.getValue());
            }
        } catch (IOException | RuntimeException e) {
            ThePrisonsClient.LOGGER.warn("[market] could not read {}", file, e);
        }
    }

    public void save(Path file) {
        JsonObject root = new JsonObject();
        json.forEach(root::add);
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, new GsonBuilder().create().toJson(root));
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            dirty = false;
        } catch (IOException e) {
            ThePrisonsClient.LOGGER.warn("[market] could not write {}", file, e);
        }
    }
}
