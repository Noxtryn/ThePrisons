package io.theprisons.items.market;

import java.util.Locale;

/** Money for the market views: three significant digits ("$2.30K", "$14.8M", "$231K"), the same everywhere (tooltip, cards, panel). */
public final class PriceFormat {
    private PriceFormat() {
    }

    public static String compact(double v) {
        if (Double.isNaN(v)) {
            return "-";
        }
        double a = Math.abs(v);
        String suffix = "";
        double scaled = v;
        if (a >= 1e12) {
            scaled = v / 1e12;
            suffix = "T";
        } else if (a >= 1e9) {
            scaled = v / 1e9;
            suffix = "B";
        } else if (a >= 1e6) {
            scaled = v / 1e6;
            suffix = "M";
        } else if (a >= 1e3) {
            scaled = v / 1e3;
            suffix = "K";
        }
        double s = Math.abs(scaled);
        String fmt = s >= 100.0D ? "%.0f" : s >= 10.0D ? "%.1f" : "%.2f";
        return String.format(Locale.ROOT, fmt, scaled) + suffix;
    }

    public static String money(double v) {
        return "$" + compact(v);
    }

    /** "+8.4%" / "-18.9%" / "0.0%". */
    public static String percent(double fraction) {
        return String.format(Locale.ROOT, "%+.1f%%", fraction * 100.0D);
    }
}
