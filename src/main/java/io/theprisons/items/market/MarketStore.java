package io.theprisons.items.market;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;

/**
 * The market memory on disk ({@code items/market_history.json}): per item key the recent observations. Bounded (64 per item, 1500 items, nothing older
 * than {@link #MAX_AGE_MS}); written atomically, only when something changed. A missing or broken file is an empty memory, never an error.
 */
public final class MarketStore {
    private static final Logger LOG = LoggerFactory.getLogger("theprisons");
    public static final long MAX_AGE_MS = 14L * 24 * 3_600_000L;

    private MarketStore() {
    }

    public static void load(Path file, MarketCache cache, long now) {
        if (!Files.isRegularFile(file)) {
            return;
        }
        try {
            JsonObject root = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
            for (Map.Entry<String, JsonElement> e : root.entrySet()) {
                String[] parts = e.getKey().split("\t", 2);
                if (parts.length != 2 || !e.getValue().isJsonArray()) {
                    continue;
                }
                for (JsonElement row : e.getValue().getAsJsonArray()) {
                    JsonArray r = row.getAsJsonArray();
                    long ts = r.get(2).getAsLong();
                    if (now - ts > MAX_AGE_MS) {
                        continue;
                    }
                    cache.observe(parts[0], MarketObservation.restore(parts[1], r.get(0).getAsDouble(), r.get(1).getAsInt(), ts,
                            r.get(3).getAsInt() == 1 ? MarketObservation.Source.SALE : MarketObservation.Source.LISTING,
                            r.size() > 4 ? r.get(4).getAsInt() : 0, r.size() > 5 ? r.get(5).getAsInt() : 0, r.size() > 6 ? r.get(6).getAsLong() : 0L));
                }
            }
        } catch (IOException | RuntimeException e) {
            LOG.warn("[items] could not read {} - starting with an empty market memory", file, e);
        }
    }

    public static void save(Path file, MarketCache cache, long now) {
        JsonObject root = new JsonObject();
        for (Map.Entry<String, MarketHistory> e : cache.all().entrySet()) {
            JsonArray rows = new JsonArray();
            for (MarketObservation o : e.getValue().all()) {
                if (now - o.timestampMs() > MAX_AGE_MS) {
                    continue;
                }
                JsonArray r = new JsonArray();
                r.add(o.total());
                r.add(o.amount());
                r.add(o.timestampMs());
                r.add(o.source() == MarketObservation.Source.SALE ? 1 : 0);
                r.add(o.sellerHash());
                r.add(o.buyerHash());
                r.add(o.expiresAtMs());
                rows.add(r);
            }
            if (rows.size() > 0) {
                // key = catalog key + tab + item key (so the list can group variants again)
                String catalog = cache.catalogKeysOf(e.getKey()).stream().findFirst().orElse(e.getKey());
                root.add(catalog + "\t" + e.getKey(), rows);
            }
        }
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, new GsonBuilder().create().toJson(root));
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            LOG.warn("[items] could not write {}", file, e);
        }
    }
}
