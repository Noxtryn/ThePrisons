package com.freelocs.theprisons.modules.hud.scoreboard;

import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads Cosmic Prisons' sidebar. It is made of heading / value pairs on two lines ("Balance" / "$2,223,016.2",
 * "Level" / "81 (16,321,009 XP)", "Progress" / "308,993 (73.1%) to 82", "Current Zone" / "Safezone", "Criminal
 * Record" / "Neutral", "Cosmic Coins" / "Lifetime: 1,100" or "Seasonal: 0", "/top Credits" / "Balance: 0"), a title
 * line ("M4cL4ren Day 9") and, at times, a planets board ("Celestial" / "Planet" / "85 / 500", "Spaceship" /
 * "36 / 1500"). Lines are cleaned from server font icons first. Everything else is kept as "extra".
 */
final class ScoreboardData {
    private static final String NUMBER = "([0-9][0-9,]*(?:\\.[0-9]+)?)\\s*([kKmMbBtT])?";
    private static final Pattern LEVEL = Pattern.compile("^\\s*([0-9]+)\\s*(?:\\(\\s*([0-9][0-9,]*)\\s*xp\\s*\\))?",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern PROGRESS = Pattern.compile("([0-9][0-9,]*)\\s*\\(\\s*([0-9.]+)\\s*%\\s*\\)\\s*to\\s*([0-9]+)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern FRACTION = Pattern.compile("^\\s*([0-9][0-9,]*)\\s*/\\s*([0-9][0-9,]*)\\s*$");
    private static final Pattern DAY = Pattern.compile("(?i)\\bday\\s*([0-9]+)");
    private static final Pattern ANY_NUMBER = Pattern.compile(NUMBER);
    private static final String[] LABELS = {"criminal record", "cosmic coins", "/top credits", "top credits", "balance",
            "current zone", "zone", "level", "progress", "gang", "rank", "energy", "tokens"};

    /** One planet / ship of the planets board. */
    record Planet(String name, long value, long max) {
    }

    /**
     * What the sidebar showed; "" / -1 = not shown (the module keeps the last known values). {@code level},
     * {@code xpTotal}, {@code progress}, {@code toGo}, {@code nextLevel}: the HELD PICKAXE's level - Cosmic shows
     * "Level" / "Progress" only while a pickaxe is in the hand; these are never carried over.
     */
    record Values(String day, String record, String balance, String coinsLifetime, String coinsSeasonal, String credits,
                  String zone, String level, long xpTotal, double progress, String toGo, String nextLevel, String gang,
                  List<Planet> planets, List<String> extra) {
        static final Values EMPTY = new Values("", "", "", "", "", "", "", "", -1, -1, "", "", "", List.of(), List.of());

        /** These values, with the gaps filled from {@code older} (the sidebar switches between boards). */
        Values over(Values older) {
            return new Values(or(day, older.day), or(record, older.record), or(balance, older.balance),
                    or(coinsLifetime, older.coinsLifetime), or(coinsSeasonal, older.coinsSeasonal), or(credits, older.credits),
                    or(zone, older.zone), level, xpTotal, progress, toGo, nextLevel,
                    or(gang, older.gang), planets.isEmpty() ? older.planets : planets,
                    extra.isEmpty() && planets.isEmpty() ? older.extra : extra);
        }

        private static String or(String a, String b) {
            return a.isEmpty() ? b : a;
        }
    }

    private ScoreboardData() {
    }

    /** Removes server font icons (private use area, supplementary planes) and control characters. */
    static String clean(String line) {
        StringBuilder out = new StringBuilder(line.length());
        line.codePoints().forEach(cp -> {
            boolean privateUse = (cp >= 0xE000 && cp <= 0xF8FF) || cp >= 0xF0000 || (cp >= 0x10000 && cp < 0x20000 && !Character.isLetter(cp));
            if (!privateUse && !Character.isISOControl(cp) && Character.getType(cp) != Character.UNASSIGNED
                    && Character.getType(cp) != Character.SURROGATE) {
                out.appendCodePoint(cp);
            }
        });
        return out.toString().replaceAll("\\s+", " ").trim();
    }

    static Values parse(@Nullable List<String> sidebar) {
        if (sidebar == null) {
            return Values.EMPTY;
        }
        List<String> lines = new ArrayList<>();
        for (String raw : sidebar) {
            lines.add(clean(raw));
        }
        Map<String, String> pairs = new LinkedHashMap<>();
        List<Planet> planets = new ArrayList<>();
        List<String> extra = new ArrayList<>();
        String day = "";
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.isEmpty()) {
                continue;
            }
            String lower = line.toLowerCase(Locale.ROOT);
            String next = i + 1 < lines.size() ? lines.get(i + 1) : "";
            if (day.isEmpty()) {
                Matcher d = DAY.matcher(line);
                if (d.find()) {
                    day = d.group(1);
                    continue;
                }
            }
            String label = label(lower);
            if (label != null && !next.isEmpty()) {
                pairs.merge(label, next, (a, b) -> a + " | " + b);
                i++;
                continue;
            }
            if (next.equalsIgnoreCase("planet") && i + 2 < lines.size()) {
                Matcher f = FRACTION.matcher(lines.get(i + 2));
                if (f.find()) {
                    planets.add(new Planet(line, number(f.group(1)), number(f.group(2))));
                    i += 2;
                    continue;
                }
            }
            Matcher f = FRACTION.matcher(next);
            if (!next.isEmpty() && f.find() && !FRACTION.matcher(line).find()) {
                planets.add(new Planet(line, number(f.group(1)), number(f.group(2))));
                i++;
                continue;
            }
            if (line.chars().anyMatch(Character::isLetterOrDigit) && !lower.contains(".com") && !lower.contains(".net")
                    && extra.size() < 6) {
                extra.add(line);
            }
        }
        String coins = pairs.getOrDefault("cosmic coins", "");
        String lifetime = afterKey(coins, "lifetime");
        String seasonal = afterKey(coins, "seasonal");
        String level = "";
        long xpTotal = -1;
        Matcher l = LEVEL.matcher(pairs.getOrDefault("level", ""));
        if (l.find()) {
            level = l.group(1);
            if (l.group(2) != null) {
                xpTotal = number(l.group(2));
            }
        }
        double progress = -1;
        String toGo = "";
        String nextLevel = "";
        Matcher p = PROGRESS.matcher(pairs.getOrDefault("progress", ""));
        if (p.find()) {
            toGo = p.group(1);
            progress = Math.max(0, Math.min(1, Double.parseDouble(p.group(2)) / 100.0D));
            nextLevel = p.group(3);
        }
        String credits = pairs.getOrDefault("/top credits", pairs.getOrDefault("top credits", ""));
        int colon = credits.indexOf(':');
        if (colon >= 0) {
            credits = credits.substring(colon + 1).trim();
        }
        String zone = pairs.getOrDefault("current zone", pairs.getOrDefault("zone", ""));
        return new Values(day, pairs.getOrDefault("criminal record", ""), pairs.getOrDefault("balance", ""), lifetime,
                seasonal, credits, zone, level, xpTotal, progress, toGo, nextLevel, pairs.getOrDefault("gang", ""),
                List.copyOf(planets), List.copyOf(extra));
    }

    private static @Nullable String label(String lower) {
        for (String label : LABELS) {
            if (lower.equals(label)) {
                return label;
            }
        }
        return null;
    }

    /** "Lifetime: 1,100 | Seasonal: 0" -> the value after "lifetime:" ("" when not there). */
    private static String afterKey(String text, String key) {
        for (String part : text.split("\\|")) {
            String p = part.trim();
            if (p.toLowerCase(Locale.ROOT).startsWith(key)) {
                int colon = p.indexOf(':');
                return colon >= 0 ? p.substring(colon + 1).trim() : "";
            }
        }
        return "";
    }

    static long number(String digits) {
        try {
            return Long.parseLong(digits.replace(",", "").replaceAll("\\..*$", ""));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** "$2,223,016.2" -> "$2.22M" (keeps the text when there is no number). */
    static String money(String value) {
        Matcher m = ANY_NUMBER.matcher(value);
        if (!m.find()) {
            return value;
        }
        double v = Double.parseDouble(m.group(1).replace(",", ""));
        if (m.group(2) != null) {
            return value;
        }
        String prefix = value.contains("$") ? "$" : "";
        return prefix + compact(v);
    }

    static String compact(double value) {
        if (value < 0) {
            return "–";
        }
        if (value >= 1e12) {
            return String.format(Locale.ROOT, "%.2fT", value / 1e12);
        }
        if (value >= 1e9) {
            return String.format(Locale.ROOT, "%.2fB", value / 1e9);
        }
        if (value >= 1e6) {
            return String.format(Locale.ROOT, "%.2fM", value / 1e6);
        }
        if (value >= 1e4) {
            return String.format(Locale.ROOT, "%.1fK", value / 1e3);
        }
        return String.format(Locale.ROOT, "%,.0f", value);
    }

    static String duration(long ms) {
        long s = Math.max(0, ms) / 1000;
        long h = s / 3600;
        long m = (s % 3600) / 60;
        return h > 0 ? String.format(Locale.ROOT, "%dh %02dm", h, m) : String.format(Locale.ROOT, "%dm %02ds", m, s % 60);
    }
}
