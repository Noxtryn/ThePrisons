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

    public static void load(Path file, PriceBook book, java.util.Map<String, MarketModule.ShopRecord> shops) {
        if (!Files.isRegularFile(file)) {
            return;
        }
        try {
            JsonObject root = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
            if (root.has("energy_rate")) {
                JsonObject r = root.getAsJsonObject("energy_rate");
                book.restoreRate(r.get("money_per_energy").getAsDouble(), r.get("seen_ms").getAsLong());
            }
            if (root.has("shops")) {
                for (JsonElement el : root.getAsJsonArray("shops")) {
                    JsonObject o = el.getAsJsonObject();
                    java.util.List<MarketParser.ShopOffer> offers = new java.util.ArrayList<>();
                    for (JsonElement oe : o.getAsJsonArray("offers")) {
                        JsonObject f = oe.getAsJsonObject();
                        java.util.List<String> loot = new java.util.ArrayList<>();
                        for (JsonElement l : f.getAsJsonArray("loot")) {
                            loot.add(l.getAsString());
                        }
                        offers.add(new MarketParser.ShopOffer(f.get("name").getAsString(), f.get("icon").getAsString(),
                                f.get("points").getAsLong(), loot, f.get("picks").getAsInt()));
                    }
                    String title = o.get("title").getAsString();
                    shops.put(title, new MarketModule.ShopRecord(title, o.get("seen_ms").getAsLong(),
                            o.get("reset_at_ms").getAsLong(), offers));
                }
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

    public static void save(Path file, PriceBook book, java.util.Map<String, MarketModule.ShopRecord> shops) {
        JsonObject root = new JsonObject();
        if (book.moneyPerEnergy() > 0.0D) {
            JsonObject r = new JsonObject();
            r.addProperty("money_per_energy", book.moneyPerEnergy());
            r.addProperty("seen", DATE.format(Instant.ofEpochMilli(book.rateSeenMs())));
            r.addProperty("seen_ms", book.rateSeenMs());
            root.add("energy_rate", r);
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
        JsonArray shopArray = new JsonArray();
        for (MarketModule.ShopRecord r : shops.values()) {
            JsonObject o = new JsonObject();
            o.addProperty("title", r.title());
            o.addProperty("seen", DATE.format(Instant.ofEpochMilli(r.seenMs())));
            o.addProperty("seen_ms", r.seenMs());
            o.addProperty("reset_at_ms", r.resetAtMs());
            JsonArray offers = new JsonArray();
            for (MarketParser.ShopOffer f : r.offers()) {
                JsonObject fo = new JsonObject();
                fo.addProperty("name", f.name());
                fo.addProperty("icon", f.icon());
                fo.addProperty("points", f.points());
                fo.addProperty("picks", f.picks());
                JsonArray loot = new JsonArray();
                f.loot().forEach(loot::add);
                fo.add("loot", loot);
                offers.add(fo);
            }
            o.add("offers", offers);
            shopArray.add(o);
        }
        root.add("shops", shopArray);
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
