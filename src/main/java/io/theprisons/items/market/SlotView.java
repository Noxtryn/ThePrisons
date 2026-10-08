package io.theprisons.items.market;

import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Everything the renderer needs for ONE auction slot, prepared when the page changed: a thin frame colour (0 = none), a short percentage and the analysis for the
 * hover. Reading it costs nothing; the hover text is formatted only for the one slot under the mouse. Fair price: no frame; unknown: no frame.
 */
public record SlotView(ListingAnalysis analysis, int borderArgb, @Nullable String badge) {
    public static SlotView of(ListingAnalysis a) {
        int border = 0;
        String badge = null;
        if (a.rating() != ListingAnalysis.Rating.UNKNOWN && a.rating() != ListingAnalysis.Rating.FAIR) {
            int rgb = switch (a.rating()) {
                case GREAT -> 0x3CE0A0;       // green
                case GOOD -> 0x4FD8E8;        // green / cyan
                case OVERPRICED -> 0xFF6B6B;  // red
                default -> 0;
            };
            int alpha = switch (a.confidence()) {
                case HIGH -> 0xE0;
                case MEDIUM -> 0xB0;
                default -> 0x70;
            };
            border = (alpha << 24) | rgb;
            if (!Double.isNaN(a.diff()) && Math.abs(a.diff()) >= 0.10D) {
                badge = String.format(Locale.ROOT, "%+.0f%%", a.diff() * 100.0D);
            }
        }
        return new SlotView(a, border, badge);
    }

    /** The hover block (formatted on demand): only the fields that are known. */
    public List<String> hover(long now) {
        MarketStats s = analysis.stats();
        int n = analysis.amount();
        List<String> out = new ArrayList<>();
        out.add("MARKET");
        out.add(row("Listed", PriceFormat.money(analysis.listedTotal())));
        if (s.known() && s.confidence() != MarketConfidence.NONE) {
            out.add(row("Fair", PriceFormat.money(s.fair() * n)));
            out.add(row("Difference", PriceFormat.percent(analysis.diff())));
        } else {
            out.add(row("Fair", "unknown"));
        }
        out.add("");
        out.add(row("Unit", PriceFormat.money(analysis.listedUnit())));
        if (!Double.isNaN(s.quickSell())) {
            out.add(row("Quick Sell", PriceFormat.money(s.quickSell() * n)));
        }
        if (!Double.isNaN(s.fair24h())) {
            out.add(row("24h", PriceFormat.money(s.fair24h() * n)));
        }
        if (!Double.isNaN(s.fair7d())) {
            out.add(row("7d", PriceFormat.money(s.fair7d() * n)));
        }
        if (s.trend() != MarketStats.Trend.UNKNOWN) {
            out.add(row("Trend", PriceFormat.percent(s.trendPercent()) + " " + s.trend().name().toLowerCase(Locale.ROOT)));
        }
        if (!Double.isNaN(s.low()) && !Double.isNaN(s.high()) && s.samples() >= 3) {
            out.add(row("Range", PriceFormat.money(s.low() * n) + " - " + PriceFormat.money(s.high() * n)));
        }
        out.add(row("Samples", Integer.toString(s.samples())));
        if (!s.basis().equals("none")) {
            out.add(row("Basis", s.basis().equals("sales") ? "Sales (" + s.window().substring(0, s.window().indexOf(' ')) + ")" : s.basis().equals("listings") ? "Listings" : "Older data"));
        }
        out.add(row("Confidence", s.confidence().label()));
        if (s.lastSeenMs() > 0L) {
            out.add(row("Last Seen", MarketStats.age(Math.max(0L, now - s.lastSeenMs())) + " ago"));
        }
        if (analysis.suspicious()) {
            out.add("Far below the fair price: check the item");
        }
        return out;
    }

    private static String row(String label, String value) {
        return String.format(Locale.ROOT, "%-11s %s", label, value);
    }
}
