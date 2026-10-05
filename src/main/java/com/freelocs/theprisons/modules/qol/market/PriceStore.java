package com.freelocs.theprisons.modules.qol.market;

import com.freelocs.theprisons.ThePrisonsClient;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * The price book on disk ({@code config/theprisons/market/prices.json}): per item its name, category, last price, the
 * date it was seen (readable, and in ms) and where (ah / gz / pb); plus the last /ee rate.
 */
public final class PriceStore {
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

    private PriceStore() {
    }

    public static void load(Path file, PriceBook book) {
        if (!Files.isRegularFile(file)) {
            return;
        }
        try {
            JsonObject root = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
            if (root.has("energy_rate")) {
                JsonObject r = root.getAsJsonObject("energy_rate");
                book.restoreRate(r.get("money_per_energy").getAsDouble(), r.get("seen_ms").getAsLong());
            }
            if (root.has("gkit_order")) {
                java.util.List<String> kits = new java.util.ArrayList<>();
                for (JsonElement k : root.getAsJsonArray("gkit_order")) {
                    kits.add(k.getAsString());
                }
                ItemIdentity.setGkitOrder(kits);
            }
            if (root.has("energy_sales")) {
                JsonObject a = root.getAsJsonObject("energy_sales");
                book.restoreAverages(a.get("today_per_1k").getAsDouble(), a.get("week_per_1k").getAsDouble());
            }
            for (JsonElement el : root.getAsJsonArray("items")) {
                JsonObject o = el.getAsJsonObject();
                book.restore(new PriceBook.Entry(o.get("key").getAsString(), o.get("name").getAsString(),
                        o.has("category") ? o.get("category").getAsString() : "", o.get("price").getAsDouble(),
                        o.get("seen_ms").getAsLong(), o.has("source") ? o.get("source").getAsString() : "",
                        o.has("icon") ? o.get("icon").getAsString() : ""));
            }
        } catch (IOException | RuntimeException e) {
            ThePrisonsClient.LOGGER.warn("[market] could not read {}", file, e);
        }
    }

    public static void save(Path file, PriceBook book) {
        JsonObject root = new JsonObject();
        if (book.moneyPerEnergy() > 0.0D) {
            JsonObject r = new JsonObject();
            r.addProperty("money_per_energy", book.moneyPerEnergy());
            r.addProperty("seen", DATE.format(Instant.ofEpochMilli(book.rateSeenMs())));
            r.addProperty("seen_ms", book.rateSeenMs());
            root.add("energy_rate", r);
        }
        if (book.avgWeekPerK() > 0.0D) {
            JsonObject a = new JsonObject();
            a.addProperty("today_per_1k", book.avgTodayPerK());
            a.addProperty("week_per_1k", book.avgWeekPerK());
            root.add("energy_sales", a);
        }
        if (!ItemIdentity.gkitOrder().equals(ItemIdentity.GKIT_DEFAULT)) {
            JsonArray kits = new JsonArray();
            ItemIdentity.gkitOrder().forEach(kits::add);
            root.add("gkit_order", kits);
        }
        JsonArray items = new JsonArray();
        for (PriceBook.Entry e : book.all()) {
            JsonObject o = new JsonObject();
            o.addProperty("key", e.key());
            o.addProperty("name", e.name());
            o.addProperty("category", e.category());
            o.addProperty("price", e.price());
            o.addProperty("seen", DATE.format(Instant.ofEpochMilli(e.seenMs())));
            o.addProperty("seen_ms", e.seenMs());
            o.addProperty("source", e.source());
            o.addProperty("icon", e.icon());
            items.add(o);
        }
        root.add("items", items);
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(root));
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            ThePrisonsClient.LOGGER.warn("[market] could not write {}", file, e);
        }
    }
}
