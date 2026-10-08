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
        double mad = Double.NaN;
        if (!all.isEmpty()) {
            List<Double> rates = new ArrayList<>();
            for (EeMenu.Listing l : all) {
                rates.add(l.ratePer1k());
            }
            rates.sort(Double::compare);
            median = MarketStats.median(rates);
            List<Double> dev = new ArrayList<>();
            for (double r : rates) {
                dev.add(Math.abs(r - median));
            }
            dev.sort(Double::compare);
            mad = MarketStats.median(dev);
        }
        // High-side outliers only (a scam or a typo with too many zeros): three times the median, or - with six listings or more - far beyond the spread.
        // A listing that is far BELOW the others is a real offer and stays the cheapest.
        List<EeMenu.Listing> good = new ArrayList<>();
        for (EeMenu.Listing l : all) {
            boolean scam = all.size() >= 3 && l.ratePer1k() > 3.0D * median
                    || all.size() >= 6 && l.ratePer1k() > median + 6.0D * Math.max(mad, 0.05D * median);
            if (!scam) {
                good.add(l);
            }
        }
        good.sort((a, b) -> Double.compare(a.ratePer1k(), b.ratePer1k()));
        double lowest = good.isEmpty() ? Double.NaN : good.get(0).ratePer1k();
        // "Typical" is the median rate of the valid listings that are a real quantity (a listing of 0.013 energy says nothing about the market; it still
        // counts for the cheapest rate and the buy simulation, as the menu's own "From" does)
        List<Double> typical = new ArrayList<>();
        for (EeMenu.Listing l : good) {
            if (l.amount() >= 1.0D) {
                typical.add(l.ratePer1k());
            }
        }
        if (typical.isEmpty()) {
            for (EeMenu.Listing l : good) {
                typical.add(l.ratePer1k());
            }
        }
        typical.sort(Double::compare);
        double goodMedian = typical.isEmpty() ? Double.NaN : MarketStats.median(typical);
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

    // ── the panel model: sections with rows, built once per page; the renderer only lays them out ─────────

    public enum Tone { NEUTRAL, GOOD, WARN, BAD, MUTED }

    /** One label / value pair of a section. */
    public record Row(String label, String value, Tone tone) {
    }

    /** A block of the panel. {@code metrics}: the rows are shown as big values side by side (at most two), else as a label / value list. */
    public record Section(String title, List<Row> rows, boolean metrics) {
        public Section {
            rows = List.copyOf(rows);
        }
    }

    public record Panel(String title, List<Section> sections, String note) {
        public Panel {
            sections = List.copyOf(sections);
        }
    }

    /** Typical rate against the 7-day average from "Price Analytics" (NaN = unknown): negative = the market is cheaper than its week. */
    public double marketVsWeek() {
        return Double.isNaN(medianRate) || Double.isNaN(weekAvgPerK) || weekAvgPerK <= 0.0D ? Double.NaN : medianRate / weekAvgPerK - 1.0D;
    }

    /** The compact market dashboard: only what is known. */
    public Panel panel() {
        List<Section> out = new ArrayList<>();
        List<Row> rates = new ArrayList<>();
        if (!Double.isNaN(lowestRate)) {
            rates.add(new Row("CHEAPEST", PriceFormat.money(lowestRate) + " / 1k", Tone.GOOD));
        }
        if (!Double.isNaN(medianRate)) {
            rates.add(new Row("TYPICAL", PriceFormat.money(medianRate) + " / 1k", Tone.NEUTRAL));
        }
        if (!rates.isEmpty()) {
            out.add(new Section("RATES", rates, true));
        }
        List<Row> market = new ArrayList<>();
        double vs = marketVsWeek();
        if (!Double.isNaN(vs)) {
            market.add(new Row("vs 7-day average", PriceFormat.percent(vs), vs <= 0.0D ? Tone.GOOD : Tone.WARN));
        }
        if (menu.priceRisesInMs() > 0L) {
            market.add(new Row("Price rises in", io.theprisons.items.energy.EnergyFormat.duration(menu.priceRisesInMs() / 1000L), Tone.MUTED));
        }
        if (!market.isEmpty()) {
            out.add(new Section("MARKET", market, false));
        }
        List<Row> buy = new ArrayList<>();
        for (Cost c : costs) {
            if (c.complete()) {
                buy.add(new Row(PriceFormat.compact(c.energy()), PriceFormat.money(c.total()), Tone.NEUTRAL));
            }
        }
        if (!buy.isEmpty()) {
            out.add(new Section("BUY COST", buy, false));
        }
        List<Row> you = new ArrayList<>();
        if (!Double.isNaN(menu.balance())) {
            you.add(new Row("Balance", PriceFormat.money(menu.balance()), Tone.NEUTRAL));
        }
        if (!Double.isNaN(affordable)) {
            you.add(new Row("Can buy", PriceFormat.compact(affordable) + " CE", Tone.NEUTRAL));
        }
        if (heldEnergy > 0.0D) {
            you.add(new Row("Hold", PriceFormat.compact(heldEnergy) + " CE", Tone.NEUTRAL));
            if (!Double.isNaN(heldValue)) {
                you.add(new Row("Value", "~" + PriceFormat.money(heldValue), Tone.GOOD));
            }
        }
        if (!you.isEmpty()) {
            out.add(new Section("YOU", you, false));
        }
        long outliers = slots.values().stream().filter(SlotInfo::outlier).count();
        String note = outliers == 0 ? "" : outliers + " extreme listing" + (outliers == 1 ? "" : "s") + " ignored";
        return new Panel("ENERGY MARKET", out, note);
    }

    /** The panel as plain lines (the dev log and the tests): title, then every section with its rows. */
    public List<String> lines() {
        Panel p = panel();
        List<String> out = new ArrayList<>();
        out.add(p.title());
        for (Section s : p.sections()) {
            out.add(s.title());
            for (Row r : s.rows()) {
                out.add(r.label() + " " + r.value());
            }
        }
        if (!p.note().isEmpty()) {
            out.add(p.note());
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
        out.add(row("Rate", PriceFormat.money(s.rate()) + " /1k"));
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
