package com.freelocs.theprisons.modules.qol.market;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What a shop reward is worth in money (pure logic), from the price book: a plain item its last price; "3x Rabbit's
 * Foot" three times that; a random one ("Random Cell Door Upgrade", "Mystery Godly Enchant", "Random Tool Prestige
 * Token I-III") the average of every item of its kind; a lootbox the average of its loot times the items it gives.
 */
public final class ShopValue {
    private static final Pattern TIMES = Pattern.compile("^(\\d+)x\\s+(.+)$");
    private static final Pattern RANDOM = Pattern.compile("(?i)\\b(random|mystery)\\b");

    private ShopValue() {
    }

    /** -1 = nothing known about it. */
    public static double item(String name, PriceBook book) {
        String n = name.strip();
        int times = 1;
        Matcher t = TIMES.matcher(n);
        if (t.find()) {
            times = Integer.parseInt(t.group(1));
            n = t.group(2);
        }
        double one;
        if (RANDOM.matcher(n).find()) {
            // "Random Cell Door Upgrade (Standard)" → kind "Cell Door Upgrade": the average of every one seen.
            String kind = n.replaceAll("(?i)\\b(random|mystery)\\b", "").replaceAll("\\(.*?\\)", "")
                    .replaceAll("\\b[IVX]+(-[IVX]+)?\\b", "").replaceAll("\\s+", " ").strip().toLowerCase(Locale.ROOT);
            one = kind.isEmpty() ? -1.0D : book.average(e -> containsAll(e.name().toLowerCase(Locale.ROOT), kind)
                    && !RANDOM.matcher(e.name()).find());
        } else {
            one = exact(n, book);
        }
        return one < 0.0D ? -1.0D : one * times;
    }

    /** A shop offer: a lootbox its average loot value times the items it gives, else the item itself. */
    public static double offer(MarketParser.ShopOffer offer, PriceBook book) {
        if (offer.loot().isEmpty()) {
            return item(offer.name(), book);
        }
        double sum = 0.0D;
        int known = 0;
        for (String loot : offer.loot()) {
            double v = item(loot, book);
            if (v >= 0.0D) {
                sum += v;
                known++;
            }
        }
        return known == 0 ? -1.0D : sum / known * Math.max(1, offer.picks());
    }

    /** Money per shop point of an offer (what a point is worth there); -1 = unknown. */
    public static double perPoint(MarketParser.ShopOffer offer, PriceBook book) {
        double v = offer(offer, book);
        return v < 0.0D || offer.points() <= 0L ? -1.0D : v / offer.points();
    }

    private static double exact(String name, PriceBook book) {
        String want = plain(name);
        for (PriceBook.Entry e : book.all()) {
            if (plain(e.name()).equals(want)) {
                return e.price();
            }
        }
        return -1.0D;
    }

    /** "XP Booster (Right Click)" and "XP Booster" are the same item. */
    private static String plain(String name) {
        return name.toLowerCase(Locale.ROOT).replace("(right click)", "").replaceAll("\\s+", " ").strip();
    }

    private static boolean containsAll(String name, String kind) {
        for (String w : kind.split(" ")) {
            if (!w.isEmpty() && !name.contains(w.replaceAll("s$", ""))) {
                return false;
            }
        }
        return true;
    }
}
