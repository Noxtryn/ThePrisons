package io.theprisons.items.market;

import io.theprisons.modules.qol.market.Money;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * What the energy market menu says in numbers, computed once per page change (the renderer reads it and calculates nothing).
 *
 * <p>The rate of a listing is money per 1k energy as the menu prints it. Listings far above the others (a rate over three times the median: a scam price like
 * 100,000,000 /1k) are outliers: they are marked and never used for the lowest rate, the typical rate or the cost to buy. The cost of buying N energy is
 * what the cheapest listings of THIS page add up to - the page shows part of the market, so it says when the page cannot cover N.
 *
 * @param lowestRate    cheapest non-outlier listing, money per 1k (NaN = none)
 * @param medianRate    median rate of the non-outlier listings (NaN = none)
 * @param slots         per listing slot: premium over the cheapest and whether it is an outlier
 * @param costs         the cost of buying 10k ... 100M energy, cheapest first, from this page
 * @param affordable    energy the player's balance buys at the cheapest listings of this page (NaN = balance unknown)
 * @param heldEnergy    Cosmic Energy items in the player's inventory (the sum of their amounts)
 * @param heldValue     what the held energy would cost to buy at the cheapest listing (NaN = unknown)
 */
public record EeAnalysis(EeMenu menu, double lowestRate, double medianRate, Map<Integer, SlotInfo> slots, List<Cost> costs, double affordable, double heldEnergy,
                         double heldValue, double weekAvgPerK, double todayAvgPerK) {
    /** @param premium rate over the cheapest rate (0 = the cheapest); @param outlier far above the typical rate */
    public record SlotInfo(double rate, double premium, boolean outlier, boolean cheapest) {
    }

    /** @param complete the page has enough energy for the amount */
    public record Cost(double energy, double total, double avgRatePer1k, boolean complete) {
    }

    public static final double[] AMOUNTS = {10_000D, 100_000D, 1_000_000D, 10_000_000D, 100_000_000D};

    public EeAnalysis {
        slots = Map.copyOf(slots);
        costs = List.copyOf(costs);
    }

    public static EeAnalysis of(EeMenu menu, double heldEnergy, double weekAvgPerK, double todayAvgPerK) {
        List<EeMenu.Listing> all = menu.listings();
        double median = Double.NaN;
        if (!all.isEmpty()) {
            List<Double> rates = new ArrayList<>();
            for (EeMenu.Listing l : all) {
                rates.add(l.ratePer1k());
            }
            rates.sort(Double::compare);
            median = MarketStats.median(rates);
        }
        List<EeMenu.Listing> good = new ArrayList<>();
        for (EeMenu.Listing l : all) {
            if (all.size() < 3 || l.ratePer1k() <= 3.0D * median) {
                good.add(l);
            }
        }
        good.sort((a, b) -> Double.compare(a.ratePer1k(), b.ratePer1k()));
        double lowest = good.isEmpty() ? Double.NaN : good.get(0).ratePer1k();
        double goodMedian = Double.NaN;
        if (!good.isEmpty()) {
            List<Double> r = new ArrayList<>();
            for (EeMenu.Listing l : good) {
                r.add(l.ratePer1k());
            }
            goodMedian = MarketStats.median(r);
        }
        Map<Integer, SlotInfo> slots = new java.util.HashMap<>();
        for (EeMenu.Listing l : all) {
            boolean outlier = !good.contains(l);
            slots.put(l.slot(), new SlotInfo(l.ratePer1k(), Double.isNaN(lowest) ? Double.NaN : l.ratePer1k() / lowest - 1.0D, outlier,
                    !outlier && !Double.isNaN(lowest) && l.ratePer1k() <= lowest + 1e-9));
        }
        List<Cost> costs = new ArrayList<>();
        for (double want : AMOUNTS) {
            double left = want;
            double total = 0.0D;
            for (EeMenu.Listing l : good) {
                double take = Math.min(left, l.amount());
                total += take * l.ratePer1k() / 1000.0D;
                left -= take;
                if (left <= 0.0D) {
                    break;
                }
            }
            double got = want - Math.max(0.0D, left);
            costs.add(new Cost(want, total, got <= 0.0D ? Double.NaN : total / got * 1000.0D, left <= 0.0D));
        }
        double affordable = Double.NaN;
        if (!Double.isNaN(menu.balance())) {
            double money = menu.balance();
            double energy = 0.0D;
            for (EeMenu.Listing l : good) {
                double price = l.amount() * l.ratePer1k() / 1000.0D;
                if (money >= price) {
                    money -= price;
                    energy += l.amount();
                } else {
                    energy += money / (l.ratePer1k() / 1000.0D);
                    money = 0.0D;
                    break;
                }
            }
            affordable = energy;
        }
        double heldValue = Double.isNaN(lowest) ? Double.NaN : heldEnergy * lowest / 1000.0D;
        return new EeAnalysis(menu, lowest, goodMedian, slots, costs, affordable, heldEnergy, heldValue, weekAvgPerK, todayAvgPerK);
    }

    /** The lines of the card: only what is known. */
    public List<String> lines() {
        List<String> out = new ArrayList<>();
        out.add("COSMIC ENERGY");
        if (!Double.isNaN(lowestRate)) {
            out.add(row("Cheapest", "$" + Money.compact(lowestRate) + " /1k"));
        }
        if (!Double.isNaN(medianRate)) {
            out.add(row("Typical", "$" + Money.compact(medianRate) + " /1k"));
        }
        if (!Double.isNaN(weekAvgPerK) && weekAvgPerK > 0.0D && !Double.isNaN(lowestRate)) {
            out.add(row("7-day avg", "$" + Money.compact(weekAvgPerK) + " /1k (" + String.format(Locale.ROOT, "%+.0f%%", (lowestRate / weekAvgPerK - 1.0D) * 100.0D) + ")"));
        }
        if (menu.priceRisesInMs() > 0L) {
            out.add(row("Price rises", io.theprisons.items.energy.EnergyFormat.duration(menu.priceRisesInMs() / 1000L)));
        }
        for (Cost c : costs) {
            if (c.complete()) {
                out.add(row(Money.compact(c.energy()) + " CE", "$" + Money.compact(c.total())));
            }
        }
        if (!Double.isNaN(affordable)) {
            out.add(row("You can buy", Money.compact(affordable) + " CE"));
        }
        if (heldEnergy > 0.0D) {
            out.add(row("You hold", Money.compact(heldEnergy) + " CE" + (Double.isNaN(heldValue) ? "" : " (~$" + Money.compact(heldValue) + ")")));
        }
        long outliers = slots.values().stream().filter(SlotInfo::outlier).count();
        if (outliers > 0) {
            out.add(outliers + " absurd listing" + (outliers == 1 ? "" : "s") + " ignored");
        }
        return out;
    }

    private static String row(String label, String value) {
        return String.format(Locale.ROOT, "%-11s %s", label, value);
    }

    /** The tooltip lines of a hovered listing (empty when the slot is no listing). */
    public @Nullable List<String> hover(int slot) {
        SlotInfo s = slots.get(slot);
        if (s == null) {
            return null;
        }
        List<String> out = new ArrayList<>();
        out.add("ENERGY MARKET");
        out.add(row("Rate", "$" + Money.compact(s.rate()) + " /1k"));
        if (s.outlier()) {
            out.add("Far above every other offer: ignored");
        } else if (s.cheapest()) {
            out.add("The cheapest offer here");
        } else if (!Double.isNaN(s.premium())) {
            out.add(row("vs cheapest", String.format(Locale.ROOT, "%+.1f%%", s.premium() * 100.0D)));
        }
        return out;
    }
}
