package io.theprisons.items.market;

import io.theprisons.modules.qol.market.MarketParser;
import io.theprisons.modules.qol.market.Money;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Reads the formats of the energy market menu that were recorded from the real server (see the menu dump of 2026-10-05). Anything else is left unknown. */
public final class EeParser {
    private static final Pattern AMOUNT = Pattern.compile("^Amount:\\s*([\\d,.]+\\s*[kmbtKMBT]?)\\s*$");
    private static final Pattern PRICE = Pattern.compile("^Price:\\s*\\$([\\d,.]+)(?:\\s*\\(\\s*([\\d,.]+)\\s*/\\s*1k\\s*\\))?");
    private static final Pattern AVAILABLE = Pattern.compile("^Available:\\s*(.+)$");
    private static final Pattern FROM = Pattern.compile("^From:\\s*\\$([\\d,.]+)\\s*/\\s*1k");
    private static final Pattern SELLERS = Pattern.compile("^Sellers:\\s*(\\d+)");
    private static final Pattern RISES = Pattern.compile("^Price increases in:\\s*(.+)$");
    private static final Pattern LOWEST = Pattern.compile("^Lowest price:\\s*\\$([\\d,.]+)\\s*/\\s*1k");
    private static final Pattern AVG10K = Pattern.compile("^Avg price/10k:\\s*\\$([\\d,.]+)");
    private static final Pattern BALANCE = Pattern.compile("^Your balance:\\s*\\$([\\d,.]+)");

    private EeParser() {
    }

    private static double num(String s) {
        return Double.parseDouble(s.replace(",", ""));
    }

    public static EeMenu parse(List<MarketParser.Item> items) {
        List<EeMenu.Listing> listings = new ArrayList<>();
        double available = Double.NaN;
        double from = Double.NaN;
        int sellers = -1;
        long rises = -1L;
        double lowest = Double.NaN;
        double avg10k = Double.NaN;
        double balance = Double.NaN;
        for (MarketParser.Item it : items) {
            double amount = Double.NaN;
            double total = Double.NaN;
            double rate = Double.NaN;
            for (String raw : it.lore()) {
                String line = raw.strip();
                Matcher m;
                if ((m = AMOUNT.matcher(line)).find()) {
                    amount = Money.parse(m.group(1));
                } else if ((m = PRICE.matcher(line)).find()) {
                    total = num(m.group(1));
                    rate = m.group(2) != null ? num(m.group(2)) : Double.NaN;
                } else if ((m = AVAILABLE.matcher(line)).find()) {
                    available = Money.parse(m.group(1));
                } else if ((m = FROM.matcher(line)).find()) {
                    from = num(m.group(1));
                } else if ((m = SELLERS.matcher(line)).find()) {
                    sellers = Integer.parseInt(m.group(1));
                } else if ((m = RISES.matcher(line)).find()) {
                    rises = MarketParser.durationMs(m.group(1));
                } else if ((m = LOWEST.matcher(line)).find()) {
                    lowest = num(m.group(1));
                } else if ((m = AVG10K.matcher(line)).find()) {
                    avg10k = num(m.group(1));
                } else if ((m = BALANCE.matcher(line)).find()) {
                    balance = num(m.group(1));
                }
            }
            if (it.slot() < MarketParser.LISTING_SLOTS && amount > 0.0D && total > 0.0D) {
                // the printed "(2,300 /1k)" is the rate the server shows; without it the rate is total over amount
                listings.add(new EeMenu.Listing(it.slot(), it.name(), amount, total, Double.isNaN(rate) ? total / amount * 1000.0D : rate));
            }
        }
        return new EeMenu(listings, available, from, sellers, rises, lowest, avg10k, balance);
    }
}
