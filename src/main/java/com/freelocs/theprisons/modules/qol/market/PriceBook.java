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
    private int version;
    /** Money per 1 energy of the cheapest /ee offer ({@code <= 0} = never seen) and when it was seen. */
    private double moneyPerEnergy;
    private long rateSeenMs;
    /** Money per 1k energy of the completed sales (Price Analytics): today and the week; {@code <= 0} = unknown. */
    private double avgTodayPerK;
    private double avgWeekPerK;

    /** The identity key of an item ({@link ItemIdentity}); "" = not an item to track. */
    public static String key(@Nullable String itemId, String name) {
        ItemIdentity.Id id = ItemIdentity.of(name, itemId);
        return id == null ? "" : id.key();
    }

    /** Remember an item even when it currently has no price (for the Cosmic item browser). */
    public void remember(String key, String name, String category, long nowMs, String source, String icon) {
        ItemIdentity.Id id = ItemIdentity.of(name, null);
        if (key.isEmpty() || id == null) {
            return;
        }
        name = id.name();
        Entry old = entries.get(key);
        if (old == null) {
            version++;
        entries.put(key, new Entry(key, name, category, -1.0D, nowMs, source, icon));
        } else if ((old.category().isBlank() && !category.isBlank()) || (old.icon().isBlank() && !icon.isBlank())) {
            version++;
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
        ItemIdentity.Id id = ItemIdentity.of(name, null);
        if (price <= 0.0D || key.isEmpty() || id == null) {
            return;
        }
        name = id.name();
        Entry old = entries.get(key);
        if (old == null || freshScan || price < old.price()) {
            version++;
        entries.put(key, new Entry(key, name, category.isEmpty() && old != null ? old.category() : category, price, nowMs,
                    source, icon.isEmpty() && old != null ? old.icon() : icon));
        }
    }

    /**
     * An independent copy (client thread): a background computation reads it while the scan keeps writing to the
     * original.
     */
    public PriceBook snapshot() {
        PriceBook copy = new PriceBook();
        copy.entries.putAll(entries);
        copy.version = version;
        copy.moneyPerEnergy = moneyPerEnergy;
        copy.rateSeenMs = rateSeenMs;
        copy.avgTodayPerK = avgTodayPerK;
        copy.avgWeekPerK = avgWeekPerK;
        return copy;
    }

    /** Something the lists are built from changed without an entry (the G-Kit order). */
    public void touch() {
        version++;
    }

    /** Changes with every entry that is added or replaced (for caches built from the book). */
    public int version() {
        return version;
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

    public void energyAverages(double todayPerK, double weekPerK) {
        if (todayPerK > 0.0D && weekPerK > 0.0D) {
            avgTodayPerK = todayPerK;
            avgWeekPerK = weekPerK;
        }
    }

    public double avgTodayPerK() {
        return avgTodayPerK;
    }

    public double avgWeekPerK() {
        return avgWeekPerK;
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

    /** An entry of the file: re-keyed by identity (older files used the server's item id), merged with one already there. */
    void restore(Entry e) {
        ItemIdentity.Id id = ItemIdentity.of(e.name(), null);
        if (id == null) {
            return;
        }
        Entry fixed = new Entry(id.key(), id.name(), e.category(), e.price(), e.seenMs(), e.source(), e.icon());
        Entry old = entries.get(fixed.key());
        if (old == null || better(fixed, old)) {
            version++;
        entries.put(fixed.key(), old != null && fixed.icon().isBlank() ? new Entry(fixed.key(), fixed.name(), fixed.category(),
                    fixed.price(), fixed.seenMs(), fixed.source(), old.icon()) : fixed);
        }
    }

    /** A real price beats a guess ("kind") beats none; then the newer one. */
    private static boolean better(Entry a, Entry b) {
        int ra = rank(a);
        int rb = rank(b);
        return ra != rb ? ra > rb : a.seenMs() > b.seenMs();
    }

    private static int rank(Entry e) {
        return e.price() <= 0.0D ? 0 : e.source().equals("kind") ? 1 : 2;
    }

    void restoreAverages(double todayPerK, double weekPerK) {
        avgTodayPerK = todayPerK;
        avgWeekPerK = weekPerK;
    }

    void restoreRate(double moneyPerEnergy, long seenMs) {
        this.moneyPerEnergy = moneyPerEnergy;
        this.rateSeenMs = seenMs;
    }
}
