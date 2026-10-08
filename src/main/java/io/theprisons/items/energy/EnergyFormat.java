package io.theprisons.items.energy;

import java.util.Locale;

/** Number and time formatting of the energy view. */
public final class EnergyFormat {
    private EnergyFormat() {
    }

    /** 7 420 000 -> "7.42M", 10 000 000 -> "10.00M", 184 000 -> "184.0K", 950 -> "950". */
    public static String compact(double v) {
        double a = Math.abs(v);
        if (a >= 1e9) {
            return String.format(Locale.ROOT, "%.2fB", v / 1e9);
        }
        if (a >= 1e6) {
            return String.format(Locale.ROOT, "%.2fM", v / 1e6);
        }
        if (a >= 1e3) {
            return String.format(Locale.ROOT, "%.1fK", v / 1e3);
        }
        return String.format(Locale.ROOT, "%.0f", v);
    }

    /** The same without trailing zeros: 10 000 000 -> "10M", 7 420 000 -> "7.42M". */
    public static String shortForm(double v) {
        String s = compact(v);
        return s.replaceAll("\\.0+([KMB])$", "$1").replaceAll("(\\.\\d*?)0+([KMB])$", "$1$2");
    }

    /** "14m 02s", "1h 05m", "45s". */
    public static String duration(long seconds) {
        long s = Math.max(0L, seconds);
        if (s >= 3600L) {
            return (s / 3600L) + "h " + String.format(Locale.ROOT, "%02d", (s % 3600L) / 60L) + "m";
        }
        if (s >= 60L) {
            return (s / 60L) + "m " + String.format(Locale.ROOT, "%02d", s % 60L) + "s";
        }
        return s + "s";
    }

    /** "+184K/min", "-12.0K/min". */
    public static String rate(double perMinute) {
        return (perMinute >= 0 ? "+" : "-") + shortForm(Math.abs(perMinute)) + "/min";
    }
}
