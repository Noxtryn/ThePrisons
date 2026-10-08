package io.theprisons.items.market;

import io.theprisons.modules.qol.market.Money;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Everything the renderer needs for ONE auction slot, prepared when the page changed: a thin border colour (0 = none), a short badge text and the
 * analysis for the hover. Reading it costs nothing; the hover text is formatted only for the one slot under the mouse.
 */
public record SlotView(ListingAnalysis analysis, int borderArgb, @Nullable String badge) {
    public static SlotView of(ListingAnalysis a) {
        int border = 0;
        String badge = null;
        if (a.rating() != ListingAnalysis.Rating.UNKNOWN && a.rating() != ListingAnalysis.Rating.FAIR) {
            int rgb = switch (a.rating()) {
                case GREAT -> 0x3CE0A0;
                case GOOD -> 0x58D68D;
                case OVERPRICED -> 0xFF6B6B;
                default -> 0;
            };
            int alpha = switch (a.confidence()) {
                case HIGH -> 0xE0;
                case MEDIUM -> 0xB0;
                default -> 0x70;
            };
            border = (alpha << 24) | rgb;
        }
        if (!Double.isNaN(a.diff()) && Math.abs(a.diff()) >= 0.10D) {
            badge = String.format(Locale.ROOT, "%+.0f%%", a.diff() * 100.0D);
        }
        return new SlotView(a, border, badge);
    }

    /** The hover block (formatted on demand). */
    public List<String> hover(long now) {
        List<String> out = new ArrayList<>();
        out.add("MARKET");
        out.add(row("Listed", "$" + Money.compact(analysis.listedTotal())));
        if (Double.isNaN(analysis.estimatedUnit())) {
            out.add(row("Estimated", "unknown"));
        } else {
            out.add(row("Estimated", "$" + Money.compact(analysis.estimatedUnit() * analysis.amount())));
            out.add(row("Difference", String.format(Locale.ROOT, "%+.1f%%", analysis.diff() * 100.0D)));
        }
        out.add("");
        out.add(row("Unit Price", "$" + Money.compact(analysis.listedUnit())));
        out.add(row("Samples", Integer.toString(analysis.samples())));
        out.add(row("Confidence", analysis.confidence().label()));
        if (analysis.lastSeenMs() > 0L) {
            out.add(row("Last Seen", MarketStats.age(Math.max(0L, now - analysis.lastSeenMs())) + " ago"));
        }
        return out;
    }

    private static String row(String label, String value) {
        return String.format(Locale.ROOT, "%-11s %s", label, value);
    }
}
