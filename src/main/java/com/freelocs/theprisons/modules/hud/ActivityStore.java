package com.freelocs.theprisons.modules.hud;

import com.freelocs.theprisons.ThePrisonsClient;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ServerInfo;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.Map;

/**
 * Keeps the session stats of "Ore Mining" and "Bandit" ({@link SessionMode}) across sessions
 * ({@code config/theprisons/activities.json}, per server and account): uptime, ores, bandit and player kills, and which
 * one was shown last. Saved on leaving a world, every 30 s and when the module stops; loaded on joining.
 */
final class ActivityStore {
    /** Key of the activity shown last. */
    private static final String CURRENT = "_current";

    private ActivityStore() {
    }

    private static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve("theprisons").resolve("activities.json");
    }

    static @Nullable String key(MinecraftClient client) {
        if (client.player == null) {
            return null;
        }
        ServerInfo server = client.getCurrentServerEntry();
        String host = server != null ? server.address : "singleplayer";
        return (host + "_" + client.player.getUuidAsString()).toLowerCase(Locale.ROOT);
    }

    static void load(@Nullable String key, ActivityLog log) {
        if (key == null || !Files.isRegularFile(file())) {
            return;
        }
        try {
            JsonObject root = JsonParser.parseString(Files.readString(file())).getAsJsonObject();
            JsonObject mine = root.getAsJsonObject(key);
            if (mine == null) {
                return;
            }
            for (String name : mine.keySet()) {
                if (name.equals(CURRENT)) {
                    continue;
                }
                JsonObject a = mine.getAsJsonObject(name);
                log.restore(SessionMode.migrate(name), a.get("ms").getAsLong(), a.get("ores").getAsLong(),
                        a.has("kills") ? a.get("kills").getAsLong() : 0L,
                        a.has("player_kills") ? a.get("player_kills").getAsLong() : 0L);
            }
            if (mine.has(CURRENT)) {
                log.restoreCurrent(SessionMode.migrate(mine.get(CURRENT).getAsString()));
            }
        } catch (IOException | RuntimeException e) {
            ThePrisonsClient.LOGGER.warn("[session_hud] could not read {}", file(), e);
        }
    }

    static void save(@Nullable String key, ActivityLog log) {
        if (key == null || log.all().isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        JsonObject mine = new JsonObject();
        for (Map.Entry<String, ActivityLog.Activity> e : log.all().entrySet()) {
            JsonObject a = new JsonObject();
            a.addProperty("ms", e.getValue().clock(now));
            a.addProperty("ores", e.getValue().stats.snapshot(e.getValue().clock(now), 0, "").ores());
            a.addProperty("kills", e.getValue().kills);
            a.addProperty("player_kills", e.getValue().playerKills);
            mine.add(e.getKey(), a);
        }
        ActivityLog.Activity current = log.current();
        if (current != null) {
            mine.addProperty(CURRENT, current.name);
        }
        try {
            JsonObject root = Files.isRegularFile(file())
                    ? JsonParser.parseString(Files.readString(file())).getAsJsonObject() : new JsonObject();
            root.add(key, mine);
            Files.createDirectories(file().getParent());
            Path tmp = file().resolveSibling("activities.json.tmp");
            Files.writeString(tmp, root.toString());
            Files.move(tmp, file(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException | RuntimeException e) {
            ThePrisonsClient.LOGGER.warn("[session_hud] could not write {}", file(), e);
        }
    }
}
