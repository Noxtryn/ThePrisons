package com.freelocs.theprisons.modules.qol.items;

import com.freelocs.theprisons.ThePrisonsClient;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.item.Item;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Which Minecraft item a Cosmic item is built on (shards on one item, dust on another ...), so the plain item - the
 * same id without components - gets the same look. A few bases are known from the Cosmic Textures mod; the rest is
 * learned from the Cosmic items the player sees. An id is only used once one family clearly dominates it (paper, for
 * example, carries several Cosmic items and stays vanilla). Kept in {@code config/theprisons/item_bases.json}.
 */
final class VanillaBases {
    private static final int MIN_SIGHTINGS = 3;
    private static final double MIN_SHARE = 0.8D;
    private static final int KNOWN = 1_000;
    private static final ScheduledExecutorService IO = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "ThePrisons-ItemBases");
        thread.setDaemon(true);
        return thread;
    });

    /** item id -> plain model -> sightings */
    private static final Map<String, Map<String, Integer>> COUNTS = new HashMap<>();
    private static final Map<Item, Identifier> RESOLVED = new IdentityHashMap<>();
    private static final Map<Item, Boolean> NONE = new IdentityHashMap<>();
    private static boolean loaded;
    private static boolean saveQueued;

    private VanillaBases() {
    }

    private static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve("theprisons").resolve("item_bases.json");
    }

    /** The model for the plain item, or null when its id is not (clearly) a Cosmic base. */
    /**
     * Items that never get a plain look: those carrying many different Cosmic things (heads with skins: pets, helmets,
     * masks; paper; books) and every block item - a satchel built on gold ore must not turn the gold ore blocks into
     * satchels.
     */
    private static boolean shared(Item item) {
        return item == net.minecraft.item.Items.PLAYER_HEAD || item == net.minecraft.item.Items.PAPER
                || item == net.minecraft.item.Items.BOOK || item instanceof net.minecraft.item.BlockItem;
    }

    /** Families that are only ever recognised by their own name / data, never by the plain item id. */
    private static boolean ownOnly(String model) {
        return model.contains("/satchel/");
    }

    static @Nullable Identifier plain(Item item) {
        if (shared(item)) {
            return null;
        }
        load();
        Identifier hit = RESOLVED.get(item);
        if (hit != null || NONE.containsKey(item)) {
            return hit;
        }
        Identifier decided = decide(COUNTS.get(Registries.ITEM.getId(item).toString()));
        if (decided != null) {
            RESOLVED.put(item, decided);
        } else {
            NONE.put(item, Boolean.TRUE);
        }
        return decided;
    }

    static void observe(Item item, Identifier plainModel) {
        if (shared(item)) {
            return;
        }
        if (ownOnly(plainModel.toString())) {
            return;
        }
        load();
        String id = Registries.ITEM.getId(item).toString();
        Map<String, Integer> counts = COUNTS.computeIfAbsent(id, k -> new HashMap<>());
        counts.merge(plainModel.toString(), 1, Integer::sum);
        RESOLVED.remove(item);
        NONE.remove(item);
        queueSave();
    }

    static @Nullable Identifier decide(@Nullable Map<String, Integer> counts) {
        if (counts == null || counts.isEmpty()) {
            return null;
        }
        int total = 0;
        String best = null;
        int bestCount = 0;
        for (Map.Entry<String, Integer> e : counts.entrySet()) {
            if (ownOnly(e.getKey())) {
                continue; // learned by an older version
            }
            total += e.getValue();
            if (e.getValue() > bestCount) {
                bestCount = e.getValue();
                best = e.getKey();
            }
        }
        if (best == null || bestCount < MIN_SIGHTINGS || bestCount < total * MIN_SHARE) {
            return null;
        }
        return Identifier.tryParse(best);
    }

    private static void load() {
        if (loaded) {
            return;
        }
        loaded = true;
        // Known from Cosmic Textures' own checks.
        COUNTS.computeIfAbsent("minecraft:sugar", k -> new HashMap<>()).put("theprisons:prisons/dust/simple", KNOWN);
        COUNTS.computeIfAbsent("minecraft:gunpowder", k -> new HashMap<>()).put("theprisons:prisons/secret_dust/simple", KNOWN);
        COUNTS.computeIfAbsent("minecraft:magma_cream", k -> new HashMap<>()).put("theprisons:prisons/charge_orb/stage_1", KNOWN);
        Path file = file();
        if (!Files.isRegularFile(file)) {
            return;
        }
        try {
            JsonObject root = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
            for (String item : root.keySet()) {
                JsonObject models = root.getAsJsonObject(item);
                Map<String, Integer> counts = COUNTS.computeIfAbsent(item, k -> new HashMap<>());
                for (String model : models.keySet()) {
                    counts.merge(model, models.get(model).getAsInt(), Math::max);
                }
            }
        } catch (IOException | RuntimeException e) {
            ThePrisonsClient.LOGGER.warn("[item_look] could not read {}", file, e);
        }
    }

    private static void queueSave() {
        if (saveQueued) {
            return;
        }
        saveQueued = true;
        // Coalesce: many items are seen at once when an inventory opens.
        IO.schedule(() -> net.minecraft.client.MinecraftClient.getInstance().execute(VanillaBases::save), 5, TimeUnit.SECONDS);
    }

    private static void save() {
        saveQueued = false;
        JsonObject root = new JsonObject();
        for (Map.Entry<String, Map<String, Integer>> item : COUNTS.entrySet()) {
            JsonObject models = new JsonObject();
            item.getValue().forEach((model, count) -> {
                if (count < KNOWN) {
                    models.addProperty(model, count);
                }
            });
            if (!models.isEmpty()) {
                root.add(item.getKey(), models);
            }
        }
        String json = new Gson().toJson(root);
        Path file = file();
        IO.execute(() -> {
            try {
                Files.createDirectories(file.getParent());
                Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
                Files.writeString(tmp, json);
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException e) {
                ThePrisonsClient.LOGGER.warn("[item_look] could not write {}", file, e);
            }
        });
    }
}
