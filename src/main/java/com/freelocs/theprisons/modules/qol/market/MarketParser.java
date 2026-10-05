package com.freelocs.theprisons.modules.qol.market;

import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads Cosmic's market menus (pure logic, menu recordings of 2026-10-05):
 * <ul>
 *     <li>"Market" (slots 0-44 listings): "Price: $8,000,000 ($125,000 / item)", "Seller: X", "Expires: ...".</li>
 *     <li>"Market Categories" (every item kind on the market, 3 pages): "Listings: 15", "Lowest Price: $8,000,000",
 *     "Contains:" with "5x Uncommon Cell Door Upgrade" lines.</li>
 *     <li>"Auction House History" (real sales): "Price: $4,000,000 (1x)", "Item sold 2m ago".</li>
 *     <li>"Buy Cosmic Energy" (/ee): slot 4 "From: $2,299 /1k"; offers "Price: $43,700,000 (2,300 /1k)".</li>
 *     <li>"Ground Zero Shop" / "Prison Break Shop": "PRICE" / "1,000,000 Points", lootboxes with "* item" lines.</li>
 * </ul>
 */
public final class MarketParser {
    /** One menu slot as recorded: the slot, item id, count, plain name and lore, the server's custom item id. */
    public record Item(int slot, String itemId, int count, String name, List<String> lore, @Nullable String customId) {
    }

    public record Listing(String name, @Nullable String customId, String icon, double unitPrice, int count) {
    }

    public record Kind(String name, @Nullable String customId, String icon, double lowest, int listings, List<String> contains) {
    }

    /** One /ee offer: the seller, the energy amount, the whole price and the price per 1k energy. */
    public record EnergyOffer(String seller, double amount, double price, double perK) {
    }

    public record Sale(String name, @Nullable String customId, String icon, double unitPrice, long agoMs) {
    }

    /** @param picks how many of the loot a lootbox gives ("Random Loot (2 items)"), 1 for a single item */
    public record ShopOffer(String name, String icon, long points, List<String> loot, int picks) {
    }

    public static final String MARKET = "Market";
    public static final String CATEGORIES = "Market Categories";
    public static final String HISTORY = "Auction House History";
    public static final String ENERGY = "Buy Cosmic Energy";
    public static final int LISTING_SLOTS = 45;

    private static final Pattern PRICE = Pattern.compile("^Price:\\s*\\$([\\d,.]+)(?:\\s*\\(\\$([\\d,.]+)\\s*/\\s*item\\))?");
    private static final Pattern SOLD_PRICE = Pattern.compile("^Price:\\s*\\$([\\d,.]+)\\s*\\(([\\d,.]+)x\\)");
    private static final Pattern AGO = Pattern.compile("^Item sold (.+) ago");
    private static final Pattern LOWEST = Pattern.compile("^Lowest Price:\\s*\\$([\\d,.]+)");
    private static final Pattern LISTINGS = Pattern.compile("^Listings:\\s*([\\d,]+)");
    private static final Pattern OFFER_PRICE = Pattern.compile("^Price:\\s*\\$([\\d,.]+)\\s*\\(([\\d,.]+)\\s*/\\s*1k\\)");
    private static final Pattern OFFER_AMOUNT = Pattern.compile("^Amount:\\s*([\\d,.]+)");
    private static final Pattern FROM = Pattern.compile("^From:\\s*\\$([\\d,.]+)\\s*/\\s*1k");
    private static final Pattern POINTS = Pattern.compile("^([\\d,]+) Points$");
    private static final Pattern PICKS = Pattern.compile("(?i)(?:Random |Normal )?Loot \\((\\d+) items?\\)");
    private static final Pattern DURATION = Pattern.compile("(\\d+)\\s*(d|h|hr|hrs|m|min|mins|s|sec|secs)\\b");
    /** Next-page arrows: "Next Page", "Next Page (2/3)", "Next Page ->". */
    private static final Pattern NEXT = Pattern.compile("^Next Page");

    private MarketParser() {
    }

    private static double number(String s) {
        return Double.parseDouble(s.replace(",", ""));
    }

    /** The listings of a "Market" page (slots 0-44 with a price). */
    public static List<Listing> listings(List<Item> items) {
        List<Listing> out = new ArrayList<>();
        for (Item it : items) {
            if (it.slot() >= LISTING_SLOTS) {
                continue;
            }
            for (String line : it.lore()) {
                Matcher m = PRICE.matcher(line.strip());
                if (m.find()) {
                    double unit = m.group(2) != null ? number(m.group(2)) : number(m.group(1)) / Math.max(1, it.count());
                    out.add(new Listing(it.name(), it.customId(), it.itemId(), unit, it.count()));
                    break;
                }
            }
        }
        return out;
    }

    /** The item kinds of a "Market Categories" page with their lowest price. */
    public static List<Kind> kinds(List<Item> items) {
        List<Kind> out = new ArrayList<>();
        for (Item it : items) {
            if (it.slot() >= LISTING_SLOTS) {
                continue;
            }
            double lowest = -1.0D;
            int listings = 0;
            List<String> contains = new ArrayList<>();
            boolean inContains = false;
            for (String raw : it.lore()) {
                String line = raw.strip();
                Matcher m = LOWEST.matcher(line);
                if (m.find()) {
                    lowest = number(m.group(1));
                    continue;
                }
                m = LISTINGS.matcher(line);
                if (m.find()) {
                    listings = (int) number(m.group(1));
                    continue;
                }
                if (line.equals("Contains:")) {
                    inContains = true;
                    continue;
                }
                if (inContains) {
                    if (line.isEmpty() || line.startsWith("and ") || line.equals("CLICK TO VIEW")) {
                        inContains = false;
                        continue;
                    }
                    contains.add(line.replaceFirst("^\\d+x\\s+", ""));
                }
            }
            if (lowest > 0.0D) {
                out.add(new Kind(it.name(), it.customId(), it.itemId(), lowest, listings, contains));
            }
        }
        return out;
    }

    /** The sales of an "Auction House History" page: price per item and how long ago. */
    public static List<Sale> sales(List<Item> items) {
        List<Sale> out = new ArrayList<>();
        for (Item it : items) {
            if (it.slot() >= LISTING_SLOTS) {
                continue;
            }
            double unit = -1.0D;
            long ago = -1L;
            for (String raw : it.lore()) {
                String line = raw.strip();
                Matcher m = SOLD_PRICE.matcher(line);
                if (m.find()) {
                    double amount = number(m.group(2));
                    unit = amount > 0.0D ? number(m.group(1)) / amount : -1.0D;
                    continue;
                }
                m = AGO.matcher(line);
                if (m.find()) {
                    ago = durationMs(m.group(1));
                }
            }
            if (unit > 0.0D && ago >= 0L) {
                out.add(new Sale(it.name(), it.customId(), it.itemId(), unit, ago));
            }
        }
        return out;
    }

    /** /ee: the cheapest offer, money per 1 energy ("From: $2,299 /1k" → 2.299); -1 = not on this page. */
    public static double energyRate(List<Item> items) {
        for (Item it : items) {
            for (String raw : it.lore()) {
                Matcher m = FROM.matcher(raw.strip());
                if (m.find()) {
                    return number(m.group(1)) / 1000.0D;
                }
            }
        }
        return -1.0D;
    }

    /** The offers of a "Buy Cosmic Energy" page (slots 9-44), in menu order. */
    public static List<EnergyOffer> energyOffers(List<Item> items) {
        List<EnergyOffer> out = new ArrayList<>();
        for (Item it : items) {
            if (it.slot() < 9 || it.slot() >= LISTING_SLOTS) {
                continue;
            }
            double amount = -1.0D;
            double price = -1.0D;
            double perK = -1.0D;
            for (String raw : it.lore()) {
                String line = raw.strip();
                Matcher m = OFFER_AMOUNT.matcher(line);
                if (m.find()) {
                    amount = number(m.group(1));
                    continue;
                }
                m = OFFER_PRICE.matcher(line);
                if (m.find()) {
                    price = number(m.group(1));
                    perK = number(m.group(2));
                }
            }
            if (price > 0.0D && perK > 0.0D) {
                out.add(new EnergyOffer(it.name(), amount, price, perK));
            }
        }
        return out;
    }

    /** A Ground Zero / Prison Break shop page: every offer with its points price and, for lootboxes, the loot. */
    public static List<ShopOffer> shop(List<Item> items) {
        List<ShopOffer> out = new ArrayList<>();
        for (Item it : items) {
            List<String> lore = it.lore();
            long points = -1L;
            int picks = 1;
            List<String> loot = new ArrayList<>();
            for (int i = 0; i < lore.size(); i++) {
                String line = lore.get(i).strip();
                Matcher pk = PICKS.matcher(line);
                if (pk.find()) {
                    picks = Integer.parseInt(pk.group(1));
                }
                if (line.startsWith("* ") && !line.startsWith("* *")) {
                    loot.add(line.substring(2).strip());
                } else if (line.startsWith("* *")) {
                    loot.add(line.substring(2).replace("*", "").strip());
                }
                if (line.equals("PRICE") && i + 1 < lore.size()) {
                    Matcher m = POINTS.matcher(lore.get(i + 1).strip());
                    if (m.find()) {
                        points = (long) number(m.group(1));
                    }
                }
            }
            if (points > 0L) {
                out.add(new ShopOffer(it.name(), it.itemId(), points, loot, picks));
            }
        }
        return out;
    }

    /** "Shop items reset in..." clock: the time left in ms; -1 = not on this page. */
    public static long shopResetMs(List<Item> items) {
        for (Item it : items) {
            if (it.name().startsWith("Shop items reset in") && !it.lore().isEmpty()) {
                return durationMs(it.lore().get(0));
            }
        }
        return -1L;
    }

    /** The slot of the next-page arrow; -1 = last page. */
    public static int nextPageSlot(List<Item> items) {
        for (Item it : items) {
            if (it.slot() >= LISTING_SLOTS && NEXT.matcher(it.name()).find()) {
                return it.slot();
            }
        }
        return -1;
    }

    /** The slot named {@code name} (the "Category View" button ...); -1 = none. */
    public static int slotNamed(List<Item> items, String name) {
        for (Item it : items) {
            if (it.name().equalsIgnoreCase(name)) {
                return it.slot();
            }
        }
        return -1;
    }

    /** "2m", "1d 3h 58m 29s", "22 hrs 13 mins 54 secs", "42 mins 15 secs" → ms. */
    static long durationMs(String text) {
        Matcher m = DURATION.matcher(text.toLowerCase(Locale.ROOT));
        long ms = 0L;
        boolean any = false;
        while (m.find()) {
            any = true;
            long n = Long.parseLong(m.group(1));
            ms += switch (m.group(2).charAt(0)) {
                case 'd' -> n * 86_400_000L;
                case 'h' -> n * 3_600_000L;
                case 'm' -> n * 60_000L;
                default -> n * 1_000L;
            };
        }
        return any ? ms : -1L;
    }
}
