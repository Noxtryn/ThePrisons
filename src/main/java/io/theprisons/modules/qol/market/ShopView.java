package io.theprisons.modules.qol.market;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * What the /gz and /pb overlay shows for a page (pure logic, built on the background thread): the points balance and
 * the shop's reset clock, and per offer its price in points, its stock, what it is worth in money and per point - with
 * the best offers marked.
 */
public final class ShopView {
    /** The menu titles of the two events and their shops. */
    public static boolean handles(String title) {
        return title.equals("Ground Zero") || title.equals("Prison Break") || isShop(title);
    }

    public static boolean isShop(String title) {
        return title.equals("Ground Zero Shop") || title.equals("Prison Break Shop");
    }

    /** @param worth money the offer is worth (-1 = unknown), @param perPoint money per shop point (-1 = unknown) */
    public record Entry(int slot, String name, long points, String stock, double worth, double perPoint, boolean best) {
    }

    /** @param balance "Points: 0/10,000,000" ("" = none shown), @param resetMs shop reset clock (-1 = none) */
    public record Model(String title, boolean shop, String balance, long resetMs, Map<Integer, Entry> bySlot, String bestName) {
        public static final Model EMPTY = new Model("", false, "", -1L, Map.of(), "");
    }

    /** How many offers (by money per point) are marked as the best. */
    static final int BEST = 3;

    private ShopView() {
    }

    public static Model build(String title, List<MarketParser.Item> items, PriceBook book) {
        boolean shop = isShop(title);
        String balance = "";
        for (MarketParser.Item it : items) {
            if (it.name().startsWith("Points:")) {
                balance = it.name();
                break;
            }
            for (String line : it.lore()) {
                if (line.strip().startsWith("Points:")) {
                    balance = line.strip();
                    break;
                }
            }
            if (!balance.isEmpty()) {
                break;
            }
        }
        Map<Integer, Entry> bySlot = new HashMap<>();
        String bestName = "";
        if (shop) {
            Map<String, MarketParser.Item> slotOf = new HashMap<>();
            for (MarketParser.Item it : items) {
                slotOf.put(it.name(), it);
            }
            List<MarketParser.ShopOffer> offers = MarketParser.shop(items);
            double[] per = new double[offers.size()];
            for (int i = 0; i < offers.size(); i++) {
                per[i] = ShopValue.perPoint(offers.get(i), book);
            }
            double[] sorted = per.clone();
            java.util.Arrays.sort(sorted);
            double threshold = sorted.length == 0 ? -1.0D : sorted[Math.max(0, sorted.length - BEST)];
            double top = sorted.length == 0 ? -1.0D : sorted[sorted.length - 1];
            for (int i = 0; i < offers.size(); i++) {
                MarketParser.ShopOffer o = offers.get(i);
                MarketParser.Item it = slotOf.get(o.name());
                if (it == null) {
                    continue;
                }
                double worth = ShopValue.offer(o, book);
                boolean best = per[i] > 0.0D && per[i] >= threshold;
                if (per[i] > 0.0D && per[i] == top && bestName.isEmpty()) {
                    bestName = o.name();
                }
                bySlot.put(it.slot(), new Entry(it.slot(), o.name(), o.points(), stock(it), worth, per[i], best));
            }
        }
        return new Model(title, shop, balance, MarketParser.shopResetMs(items), Map.copyOf(bySlot), bestName);
    }

    /** "1 Available", "Unlimited" - the line under STOCK in the offer's lore. */
    static String stock(MarketParser.Item item) {
        List<String> lore = item.lore();
        for (int i = 0; i + 1 < lore.size(); i++) {
            if (lore.get(i).strip().toUpperCase(Locale.ROOT).equals("STOCK")) {
                return lore.get(i + 1).strip();
            }
        }
        return "";
    }

    /** A glass pane: the menu's decoration, not shown in the overlay. */
    public static boolean decoration(String itemId, String name) {
        return itemId.endsWith("_stained_glass_pane") && name.isBlank();
    }

    /** "1h 05m" / "42m 15s" for the reset clock. */
    public static String clock(long ms) {
        long s = Math.max(0L, ms / 1000L);
        long h = s / 3600L;
        long m = (s % 3600L) / 60L;
        long sec = s % 60L;
        if (h > 0L) {
            return String.format(Locale.ROOT, "%dh %02dm", h, m);
        }
        return String.format(Locale.ROOT, "%dm %02ds", m, sec);
    }
}
