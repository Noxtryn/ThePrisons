package com.freelocs.theprisons.modules.qol.market;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Amounts as Cosmic writes them: "$20,000,000", "20m", "1.5k", "$2.60M", "324.8M CE" (pure logic). */
public final class Money {
    private static final Pattern AMOUNT = Pattern.compile("\\$?\\s*(\\d[\\d,]*(?:\\.\\d+)?)\\s*([kmbt])?(?![a-z])",
            Pattern.CASE_INSENSITIVE);

    private Money() {
    }

    /** The first amount in {@code text}; -1 = none. */
    public static double parse(String text) {
        Matcher m = AMOUNT.matcher(text);
        if (!m.find()) {
            return -1.0D;
        }
        double value = Double.parseDouble(m.group(1).replace(",", ""));
        String unit = m.group(2) == null ? "" : m.group(2).toLowerCase(Locale.ROOT);
        return switch (unit) {
            case "k" -> value * 1e3;
            case "m" -> value * 1e6;
            case "b" -> value * 1e9;
            case "t" -> value * 1e12;
            default -> value;
        };
    }

    /** 20 000 000 → "20.0M", 1 500 → "1.5K", 950 → "950". */
    public static String compact(double value) {
        double v = Math.abs(value);
        if (v >= 1e12) {
            return String.format(Locale.ROOT, "%.2fT", value / 1e12);
        }
        if (v >= 1e9) {
            return String.format(Locale.ROOT, "%.2fB", value / 1e9);
        }
        if (v >= 1e6) {
            return String.format(Locale.ROOT, "%.1fM", value / 1e6);
        }
        if (v >= 1e3) {
            return String.format(Locale.ROOT, "%.1fK", value / 1e3);
        }
        return String.format(Locale.ROOT, "%.0f", value);
    }
}
