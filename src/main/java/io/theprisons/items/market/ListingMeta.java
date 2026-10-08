package io.theprisons.items.market;

import io.theprisons.modules.qol.market.MarketParser;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The stable facts of an auction line that the real menus print: "Seller: Name", "Expires: 23h 58m 58s" (a listing), "Buyer: Name" and "Item sold 2m ago" (a
 * sale). Nothing is invented: a line that is not there is unknown (null / 0).
 */
public record ListingMeta(@Nullable String seller, @Nullable String buyer, long expiresInMs, long soldAgoMs) {
    private static final Pattern SELLER = Pattern.compile("^Seller:\\s*(.+)$");
    private static final Pattern BUYER = Pattern.compile("^Buyer:\\s*(.+)$");
    private static final Pattern EXPIRES = Pattern.compile("^Expires:\\s*(.+)$");
    private static final Pattern SOLD = Pattern.compile("^Item sold (.+) ago");

    public static ListingMeta parse(List<String> lore) {
        String seller = null;
        String buyer = null;
        long expires = 0L;
        long sold = 0L;
        for (String raw : lore) {
            String line = raw.strip();
            Matcher m;
            if ((m = SELLER.matcher(line)).find()) {
                seller = m.group(1).strip();
            } else if ((m = BUYER.matcher(line)).find()) {
                buyer = m.group(1).strip();
            } else if ((m = EXPIRES.matcher(line)).find()) {
                expires = MarketParser.durationMs(m.group(1));
            } else if ((m = SOLD.matcher(line)).find()) {
                sold = MarketParser.durationMs(m.group(1));
            }
        }
        return new ListingMeta(seller, buyer, expires, sold);
    }
}
