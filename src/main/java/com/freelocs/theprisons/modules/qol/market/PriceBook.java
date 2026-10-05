package com.freelocs.theprisons.modules.qol.market;

import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Predicate;

/**
 * Every Cosmic item's last seen price (pure logic): the lowest BIN of the auction house when it was last looked at,
 * with the time it was seen - an item that is not in the AH now keeps its last price. Plus the energy rate: the
 * cheapest /ee offer (money per 1 energy), so a price in money is also shown in energy
 * ({@code 20m money at $2 per energy = 10m energy}).
 */
public final class PriceBook {
    /**
     * One item: its key (the server's item id, else the plain name), name, menu category, last price and when, and the
     * Minecraft item it is drawn with ({@code icon}, e.g. "minecraft:player_head").
     */
    public record Entry(String key, String name, String category, double price, long seenMs, String source, String icon) {
    }

    private final Map<String, Entry> entries = new LinkedHashMap<>();
    /** Money per 1 energy of the cheapest /ee offer ({@code <= 0} = never seen) and when it was seen. */
    private double moneyPerEnergy;
    private long rateSeenMs;

    public static String key(@Nullable String itemId, String name) {
        return itemId != null && !itemId.isBlank() ? itemId : name.toLowerCase(Locale.ROOT).strip();
    }

    /** Remember an item even when it currently has no price (for the Cosmic item browser). */
    public void remember(String key, String name, String category, long nowMs, String source, String icon) {
        Entry old = entries.get(key);
        if (old == null) {
            entries.put(key, new Entry(key, name, category, -1.0D, nowMs, source, icon));
        } else if ((old.category().isBlank() && !category.isBlank()) || (old.icon().isBlank() && !icon.isBlank())) {
            entries.put(key, new Entry(key, name, category.isBlank() ? old.category() : category, old.price(), old.seenMs(), old.source(),
                    icon.isBlank() ? old.icon() : icon));
        }
    }

    /** A listing seen now; freshScan replaces the old scan value, otherwise the lower price wins. */
    public void seen(String key, String name, String category, double price, long nowMs, String source, boolean freshScan) {
        seen(key, name, category, price, nowMs, source, freshScan, "");
    }

    public void seen(String key, String name, String category, double price, long nowMs, String source, boolean freshScan,
                     String icon) {
        if (price <= 0.0D) {
            return;
        }
        Entry old = entries.get(key);
        if (old == null || freshScan || price < old.price()) {
            entries.put(key, new Entry(key, name, category.isEmpty() && old != null ? old.category() : category, price, nowMs,
                    source, icon.isEmpty() && old != null ? old.icon() : icon));
        }
    }

    public @Nullable Entry get(String key) {
        return entries.get(key);
    }

    public Collection<Entry> all() {
        return entries.values();
    }

    /** The cheapest /ee offer of a scan: {@code money} for {@code energy}. Every scan replaces the rate. */
    public void energyOffer(double money, double energy, long nowMs) {
        if (money <= 0.0D || energy <= 0.0D) {
            return;
        }
        moneyPerEnergy = money / energy;
        rateSeenMs = nowMs;
    }

    public double moneyPerEnergy() {
        return moneyPerEnergy;
    }

    public long rateSeenMs() {
        return rateSeenMs;
    }

    /** {@code price} in energy at the last /ee rate; -1 = no rate known. */
    public double inEnergy(double price) {
        return moneyPerEnergy > 0.0D ? price / moneyPerEnergy : -1.0D;
    }

    /**
     * A random reward ("Random Mask" in the /gz or /pb shop): the average last price of every item of its kind
     * ({@code member}), {@code -1} when none was ever seen.
     */
    public double average(Predicate<Entry> member) {
        double sum = 0.0D;
        int n = 0;
        for (Entry e : entries.values()) {
            if (member.test(e)) {
                sum += e.price();
                n++;
            }
        }
        return n == 0 ? -1.0D : sum / n;
    }

    /** "Random Mask" → every entry whose name contains "mask" (the word after "random"). */
    public static Predicate<Entry> kindOf(String randomName) {
        String kind = randomName.toLowerCase(Locale.ROOT).replaceAll("(?i)\\b(random|mystery|any)\\b", "")
                .replaceAll("[^a-z ]", " ").strip();
        String[] words = kind.split("\\s+");
        String last = words.length == 0 ? "" : words[words.length - 1].replaceAll("s$", "");
        return e -> !last.isEmpty() && e.name().toLowerCase(Locale.ROOT).contains(last)
                && !e.name().toLowerCase(Locale.ROOT).contains("random");
    }

    /** Items whose name contains every word of {@code query} (case ignored); empty query = all. */
    public List<Entry> search(String query) {
        String[] words = query.toLowerCase(Locale.ROOT).strip().split("\\s+");
        List<Entry> out = new ArrayList<>();
        for (Entry e : entries.values()) {
            String name = e.name().toLowerCase(Locale.ROOT);
            boolean all = true;
            for (String w : words) {
                if (!w.isEmpty() && !name.contains(w)) {
                    all = false;
                    break;
                }
            }
            if (all) {
                out.add(e);
            }
        }
        return out;
    }

    void restore(Entry e) {
        entries.put(e.key(), e);
    }

    void restoreRate(double moneyPerEnergy, long seenMs) {
        this.moneyPerEnergy = moneyPerEnergy;
        this.rateSeenMs = seenMs;
    }
}
