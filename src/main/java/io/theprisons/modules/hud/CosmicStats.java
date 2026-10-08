package io.theprisons.modules.hud;

import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Session numbers for the Cosmic Prisons HUD (pure logic: the module feeds it, the renderer draws its {@link Snapshot}).
 *
 * <ul>
 *     <li><b>Rates:</b> ores, energy and XP go into 1-second buckets of the last 5 minutes (a ring of 300). OP/s is
 *     the last 60 s; energy and XP per hour are the last 5 minutes projected to an hour (less while the session is
 *     younger). Adding and reading are O(1) / O(300) - nothing grows with the session.</li>
 *     <li><b>Ores</b> are counted by the module: the broken block plus every ore around it that turns into something
 *     else right after (Fractured / Shatter procs take blocks beside or in a radius).</li>
 *     <li><b>Energy:</b> the held pickaxe's energy (lore) going up; energy absorbed from an orb ("Absorbed N Cosmic
 *     Energy") is not a gain. Without a lore value: "+N Energy" in the action bar.</li>
 *     <li><b>XP:</b> "+N XP" in the action bar; without it the vanilla XP points going up.</li>
 *     <li><b>Tax:</b> the sidebar's tax line (%); "No tax applied due to Inmate Rations" = 0 % for a minute.</li>
 *     <li><b>Boosters:</b> sidebar and boss bar lines with "booster" (with multiplier and time left), booster
 *     activation / expiry chat lines, and "Inmate Rations: 2x ..." (a consumed ration, repeated while active).
 *     "server" / "global" = server booster, everything else is the player's own.</li>
 * </ul>
 *
 * Lines with one of the keywords that no rule understood go to {@code unparsed} once each, so the rules can be fitted
 * to the server's exact wording.
 */
public final class CosmicStats {
    static final int WINDOW = 300;
    static final int OPS_WINDOW = 60;
    /** A ration counts as active this long after its last message. */
    static final long RATION_MS = 120_000L;
    static final long NO_TAX_MS = 60_000L;
    /** Sidebar / boss bar boosters not seen for this long are gone. */
    static final long SEEN_MS = 3_000L;
    /** An action bar XP / energy source is trusted (and the fallback ignored) this long after it was last seen. */
    static final long SOURCE_MS = 60_000L;
    /** More XP than this between two sidebar readings is no mining but another pickaxe / account. */
    static final long SIDEBAR_JUMP = 5_000_000L;
    private static final int UNPARSED_MAX = 64;

    private static final String NUMBER = "([0-9][0-9,]*(?:\\.[0-9]+)?)\\s*([kKmMbB])?";
    static final Pattern PERCENT = io.theprisons.core.cosmic.parse.CosmicPatterns.PERCENT;
    static final Pattern MULTIPLIER = Pattern.compile("([0-9]+(?:\\.[0-9]+)?)\\s*x\\b", Pattern.CASE_INSENSITIVE);
    static final Pattern ENERGY_GAIN = Pattern.compile("\\+\\s*" + NUMBER + "\\s*(?:cosmic\\s+)?energy", Pattern.CASE_INSENSITIVE);
    static final Pattern XP_GAIN = Pattern.compile("\\+\\s*" + NUMBER + "\\s*(?:mining\\s+)?(?:xp|exp)\\b", Pattern.CASE_INSENSITIVE);
    /**
     * Per-minute rates on the action bar ("1,234 XP/m", "+3.4k Energy per minute", "XP/min: 1,234",
     * "Energy: 12.3k/min"): group "kind" = xp / energy, "n" / "u" = number and unit.
     */
    static final Pattern PER_MINUTE = Pattern.compile("(?i)(?:\\+?(?<n1>[0-9][0-9,]*(?:\\.[0-9]+)?)\\s*(?<u1>[kmb])?\\s*(?:cosmic\\s+)?(?<k1>xp|exp|energy)\\s*(?:/|per\\s+)\\s*(?:m|min|minute)\\b"
            + "|(?<k2>xp|exp|energy)\\s*(?:/\\s*(?:m|min|minute)\\b|per\\s+min(?:ute)?)?\\s*[:=]?\\s*\\+?(?<n2>[0-9][0-9,]*(?:\\.[0-9]+)?)\\s*(?<u2>[kmb])?\\s*/\\s*(?:m|min|minute)\\b"
            + "|(?<k3>xp|exp|energy)\\s*/\\s*(?:m|min|minute)\\s*[:=]?\\s*\\+?(?<n3>[0-9][0-9,]*(?:\\.[0-9]+)?)\\s*(?<u3>[kmb])?)");
    /**
     * The action bar's own per-minute rates after the gains: "+64.9 XP (194.7/min) +128.7 CE (386/min)" - group "k" is the
     * kind (xp / ce / energy), "n" and "u" the number and its unit (k, m, b).
     */
    static final Pattern BAR_RATE = Pattern.compile("(?i)(?<k>xp|exp|ce|energy)\\s*\\(\\s*(?<n>[0-9][0-9,]*(?:\\.[0-9]+)?)\\s*(?<u>[kmb])?\\s*/\\s*(?:m|min|minute)\\s*\\)");
    static final Pattern ABSORBED = Pattern.compile("absorbed\\s+" + NUMBER + "\\s*cosmic\\s+energy", Pattern.CASE_INSENSITIVE);
    static final Pattern RATIONS = Pattern.compile("inmate rations:\\s*([0-9]+(?:\\.[0-9]+)?)x\\s+([a-z ]+?)\\s*\\.?$", Pattern.CASE_INSENSITIVE);
    static final Pattern ANY_NUMBER = Pattern.compile(NUMBER);
    /** "(!) You have 1 hrs 20 min of Rested XP at 2x XP!" */
    static final Pattern RESTED = Pattern.compile("you have (.+?) of rested xp at ([0-9]+(?:\\.[0-9]+)?)x", Pattern.CASE_INSENSITIVE);
    /** "Current bonus: 12% Energy Gain from 3 Charge Orbs." */
    static final Pattern CHARGE_ORBS = Pattern.compile("current bonus:\\s*([0-9]+(?:\\.[0-9]+)?)%\\s*energy gain from\\s*([0-9]+)\\s*charge orbs?",
            Pattern.CASE_INSENSITIVE);
    /** "(!) Anti XP Tax Pet [LVL 3]: no Guard XP Tax for 30m." */
    static final Pattern ANTI_TAX = Pattern.compile("anti xp tax pet[^:]*:\\s*no guard xp tax for (.+)", Pattern.CASE_INSENSITIVE);
    /** "(!) Lucky Pet: You have been imbued with an extreme sense of good fortune for 1 minute" */
    static final Pattern LUCKY = Pattern.compile("lucky pet:.*?for ([0-9]+\\s*(?:minutes?|min|m|seconds?|s|hours?|h))", Pattern.CASE_INSENSITIVE);
    private static final Pattern CLOCK = Pattern.compile("\\b([0-9]{1,2}):([0-9]{2})(?::([0-9]{2}))?\\b");
    private static final Pattern UNITS = Pattern.compile("([0-9]+)\\s*(d|h|m|s)(?:[a-z]*)\\b", Pattern.CASE_INSENSITIVE);

    /** An active booster; {@code endsAtMs} 0 = time left unknown. */
    public record Booster(String name, double multiplier, long endsAtMs) {
    }

    /** Everything the HUD shows, computed at most every few ticks. */
    public record Snapshot(long uptimeMs, double opsRecent, double opsAverage, long ores, double energyPerHour,
                           double xpPerHour, @Nullable Double taxPercent, String taxNote, List<Booster> serverBoosters,
                           List<Booster> personalBoosters, int learnedRoutes, String mode,
                           long levelUpEtaMs, double procShare, String loreProcs, double durability,
                           double forecastOps, double loreChance, String botState, int inventoryPercent,
                           double energyAvgPerHour, double xpAvgPerHour) {
        /** Without the averages (-1 = unknown: the HUD then uses the rate itself). */
        public Snapshot(long uptimeMs, double opsRecent, double opsAverage, long ores, double energyPerHour,
                        double xpPerHour, @Nullable Double taxPercent, String taxNote, List<Booster> serverBoosters,
                        List<Booster> personalBoosters, int learnedRoutes, String mode,
                        long levelUpEtaMs, double procShare, String loreProcs, double durability,
                        double forecastOps, double loreChance, String botState, int inventoryPercent) {
            this(uptimeMs, opsRecent, opsAverage, ores, energyPerHour, xpPerHour, taxPercent, taxNote, serverBoosters,
                    personalBoosters, learnedRoutes, mode, levelUpEtaMs, procShare, loreProcs, durability, forecastOps,
                    loreChance, botState, inventoryPercent, -1.0D, -1.0D);
        }
    }

    /** XP still needed for the next level: the sidebar's "a / b" XP line, else what the module last gave. */
    static final Pattern XP_OF = Pattern.compile(NUMBER + "\\s*/\\s*" + NUMBER);
    /** Sidebar "Level" value: "81 (16,321,009 XP)" - the total XP, it grows with every mined block. */
    static final Pattern TOTAL_XP = io.theprisons.core.cosmic.parse.CosmicPatterns.TOTAL_XP;
    /** Sidebar under "Cosmic Energy": "(429,210 / 1,259,523)" - the pickaxe's energy and its capacity. */
    static final Pattern ENERGY_OF = io.theprisons.core.cosmic.parse.CosmicPatterns.ENERGY_OF;

    private final long startMs;
    private final Rolling ores = new Rolling();
    /** Ores hit directly (without proc blocks): the base of the proc forecast. */
    private final Rolling hits = new Rolling();
    private final Rolling energy = new Rolling();
    private final Rolling xp = new Rolling();
    private long oresTotal;
    private long procOres;
    /** Hits that took at least one proc block along. */
    private long procEvents;
    private double loreChance = -1.0D;
    private long sidebarXpLeft = -1L;
    private long vanillaXpLeft = -1L;
    private String loreProcs = "";
    private double durability = -1.0D;

    private @Nullable String pickaxeKey;
    private long pickaxeEnergy = -1L;
    /** "Never" without overflow in {@code now - then}. */
    private static final long NEVER = Long.MIN_VALUE / 2L;
    private long loreSeenMs = NEVER;
    private long absorbCredit;
    private long absorbUntilMs;
    private long barEnergyMs = NEVER;
    private long barXpMs = NEVER;
    private long vanillaXp = -1L;
    /** Sidebar sources (preferred: Cosmic shows no "+N XP" and keeps the vanilla XP points at 0). */
    private long sidebarXp = -1L;
    private long sidebarXpMs = NEVER;
    private long sidebarEnergy = -1L;
    private long sidebarEnergyMax = -1L;
    private long sidebarEnergyMs = NEVER;
    /** Everything gained so far (the session HUD hands the increase on to the running activity). */
    private long xpTotal;
    private long energyTotal;
    /** The action bar's per-minute rates (-1 = never seen); kept, not reset, so the HUD never stops showing them. */
    private double barXpPerMinute = -1.0D;
    private double barEnergyPerMinute = -1.0D;
    private final Set<String> barWordings = new LinkedHashSet<>();

    private @Nullable Double tax;
    private long noTaxUntilMs;

    private final Map<String, Booster> chatBoosters = new LinkedHashMap<>();
    private final Map<String, Booster> shownBoosters = new LinkedHashMap<>();
    private final Map<String, Long> shownSeen = new LinkedHashMap<>();
    private final Map<String, Long> rations = new LinkedHashMap<>();
    private final Map<String, Double> rationMultiplier = new LinkedHashMap<>();

    private final Set<String> unparsedSeen = new LinkedHashSet<>();
    private final Consumer<String> unparsed;

    public CosmicStats(long startMs, Consumer<String> unparsed) {
        this.startMs = startMs;
        this.unparsed = unparsed;
    }

    // ── Feeding ─────────────────────────────────────────────────────────────

    /** Ores hit directly. */
    /** A saved ore count (restored activity): only the total, the rates start fresh. */
    public void restoreOres(long count) {
        oresTotal += Math.max(0L, count);
    }

    public void addOres(int count, long nowMs) {
        oresTotal += count;
        ores.add(nowMs, count);
        hits.add(nowMs, count);
    }

    /**
     * Ores taken along by procs (Fractured / Shatter / the pickaxe's side blocks): counted and kept apart;
     * {@code events} = how many hits they came from.
     */
    public void addProcOres(int count, int events, long nowMs) {
        oresTotal += count;
        ores.add(nowMs, count);
        procOres += count;
        procEvents += events;
    }

    /** The held pickaxe: its proc enchant lines from the lore ("" = none) and durability 0..1 (-1 = none / unbreakable). */
    public void pickaxe(String procLines, double durabilityShare, double procChance) {
        loreProcs = procLines;
        durability = durabilityShare;
        loreChance = procChance;
    }

    /** The proc chance from Fractured / Shatter lore lines ("Fractured 12%" + "Shatter 8%" = 0.20); -1 = none given. */
    static double procChance(List<String> lore) {
        double sum = 0.0D;
        boolean found = false;
        for (String line : lore) {
            String lower = line.toLowerCase(Locale.ROOT);
            if (lower.contains("fractured") || lower.contains("shatter")) {
                Matcher m = PERCENT.matcher(line);
                if (m.find()) {
                    sum += Double.parseDouble(m.group(1).replace(',', '.')) / 100.0D;
                    found = true;
                }
            }
        }
        return found ? Math.min(1.0D, sum) : -1.0D;
    }

    /** Vanilla XP still needed for the next level (fallback when the sidebar shows no "a / b" XP line). */
    public void vanillaXpLeft(long points) {
        vanillaXpLeft = points;
    }

    /** "Fractured III", "Shatter 12%" ... from lore lines (formatting already stripped), joined; "" = none. */
    static String procLines(List<String> lore) {
        StringBuilder sb = new StringBuilder();
        for (String line : lore) {
            String lower = line.toLowerCase(Locale.ROOT);
            if (lower.contains("fractured") || lower.contains("shatter")) {
                String t = line.trim();
                if (t.length() > 22) {
                    t = t.substring(0, 22);
                }
                sb.append(sb.isEmpty() ? "" : " · ").append(t);
            }
        }
        return sb.toString();
    }

    /** Reads per-minute rates from an action bar line; true when it had one. */
    private boolean perMinute(String line, String lower) {
        if (!(lower.contains("xp") || lower.contains("exp") || lower.contains("energy"))) {
            return false;
        }
        if (barWordings.size() < 32 && barWordings.add(lower.replaceAll("[0-9][0-9,.]*", "#"))) {
            unparsed.accept("actionbar seen: " + line); // the exact wording, to check the rates against
        }
        Matcher m = PER_MINUTE.matcher(line);
        boolean found = false;
        while (m.find()) {
            for (int g = 1; g <= 3; g++) {
                String kind = m.group("k" + g);
                if (kind == null) {
                    continue;
                }
                double value = Double.parseDouble(m.group("n" + g).replace(",", "")) * unit(m.group("u" + g));
                if (kind.equalsIgnoreCase("energy")) {
                    barEnergyPerMinute = value;
                } else {
                    barXpPerMinute = value;
                }
                found = true;
            }
        }
        return found;
    }

    /**
     * "+64.9 XP (194.7/min) +128.7 CE (386/min)": the server's per-minute rates, read at once (every ~3 s with the next
     * action bar). The HUD shows them x 60 as the rate per hour.
     */
    private void barRates(String line) {
        Matcher m = BAR_RATE.matcher(line);
        while (m.find()) {
            double perMinute = Double.parseDouble(m.group("n").replace(",", "")) * unit(m.group("u"));
            String kind = m.group("k").toLowerCase(Locale.ROOT);
            if (kind.equals("ce") || kind.equals("energy")) {
                barEnergyPerMinute = perMinute;
            } else {
                barXpPerMinute = perMinute;
            }
        }
    }

    /** XP / energy per hour from the action bar's per-minute rates (x 60), -1 while it showed none. */
    public double barXpPerHour() {
        return barXpPerMinute < 0.0D ? -1.0D : barXpPerMinute * 60.0D;
    }

    public double barEnergyPerHour() {
        return barEnergyPerMinute < 0.0D ? -1.0D : barEnergyPerMinute * 60.0D;
    }

    public long xpTotal() {
        return xpTotal;
    }

    public long energyTotal() {
        return energyTotal;
    }

    /** Gains measured elsewhere (the session HUD's global stats) for this activity. */
    public void addGains(long xpGain, long energyGain, long nowMs) {
        if (xpGain > 0L) {
            gainXp(nowMs, xpGain);
        }
        if (energyGain > 0L) {
            gainEnergy(nowMs, energyGain);
        }
    }

    private void gainXp(long nowMs, long amount) {
        xp.add(nowMs, amount);
        xpTotal += amount;
    }

    private void gainEnergy(long nowMs, long amount) {
        energy.add(nowMs, amount);
        energyTotal += amount;
    }

    /** A rise of the pickaxe's energy, minus energy absorbed from items (not mined). */
    private void pickaxeGain(long gain, long nowMs) {
        if (nowMs < absorbUntilMs && absorbCredit > 0L) {
            long used = Math.min(gain, absorbCredit);
            absorbCredit -= used;
            gain -= used;
        }
        if (gain > 0L) {
            gainEnergy(nowMs, gain);
        }
    }

    /** The held pickaxe's energy from its lore ({@code key} = which pickaxe; another one starts a new baseline). */
    public void pickaxeEnergy(String key, long value, long nowMs) {
        loreSeenMs = nowMs;
        if (key.equals(pickaxeKey) && pickaxeEnergy >= 0L && value > pickaxeEnergy && nowMs - sidebarEnergyMs > SOURCE_MS) {
            pickaxeGain(value - pickaxeEnergy, nowMs);
        }
        // Down (energy taken out / pickaxe "full" and emptied) or another pickaxe: only a new baseline.
        pickaxeKey = key;
        pickaxeEnergy = value;
    }

    /** The vanilla XP points (fallback when the action bar shows no XP gains). */
    public void vanillaXp(long totalPoints, long nowMs) {
        if (vanillaXp >= 0L && totalPoints > vanillaXp && nowMs - barXpMs > SOURCE_MS && nowMs - sidebarXpMs > SOURCE_MS) {
            gainXp(nowMs, totalPoints - vanillaXp);
        }
        vanillaXp = totalPoints;
    }

    /** A chat ({@code overlay} = action bar) message, formatting stripped. */
    public void message(String text, boolean overlay, long nowMs) {
        String line = text.trim();
        String lower = line.toLowerCase(Locale.ROOT);
        if (overlay) {
            barRates(line);
        }
        boolean used = false;
        if (overlay && perMinute(line, lower)) {
            used = true;
        } else if (overlay) {
            Matcher e = ENERGY_GAIN.matcher(line);
            while (e.find()) {
                barEnergyMs = nowMs;
                if (nowMs - loreSeenMs > SOURCE_MS && nowMs - sidebarEnergyMs > SOURCE_MS) {
                    gainEnergy(nowMs, amount(e));
                }
                used = true;
            }
            Matcher x = XP_GAIN.matcher(line);
            while (x.find()) {
                barXpMs = nowMs;
                if (nowMs - sidebarXpMs > SOURCE_MS) {
                    gainXp(nowMs, amount(x));
                }
                used = true;
            }
        }
        Matcher a = ABSORBED.matcher(line);
        if (a.find()) {
            absorbCredit += amount(a);
            absorbUntilMs = nowMs + 5_000L;
            used = true;
        }
        if (lower.contains("no tax applied")) {
            noTaxUntilMs = nowMs + NO_TAX_MS;
            used = true;
        }
        Matcher r = RATIONS.matcher(stripPrefix(line));
        if (r.find()) {
            String name = "Rations: " + r.group(1) + "x " + title(r.group(2));
            rations.put(name, nowMs);
            rationMultiplier.put(name, Double.parseDouble(r.group(1)));
            used = true;
        }
        if (!overlay && lower.contains("booster")) {
            used |= boosterChat(line, lower, nowMs);
        }
        if (!overlay) {
            used |= effectChat(line, lower, nowMs);
        }
        if (!used) {
            noteUnparsed(overlay ? "actionbar" : "chat", line, lower);
        }
    }

    /** The sidebar's lines (formatting stripped), top to bottom. */
    public void sidebar(List<String> lines, long nowMs) {
        Double found = null;
        long xpLeft = -1L;
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            String lower = line.toLowerCase(Locale.ROOT);
            boolean used = false;
            Matcher total = TOTAL_XP.matcher(line);
            if (total.matches()) {
                sidebarXp(Long.parseLong(total.group(1).replaceAll("[,.]", "")), nowMs);
                continue;
            }
            Matcher held = ENERGY_OF.matcher(line);
            if (held.matches() && i > 0 && (lines.get(i - 1).toLowerCase(Locale.ROOT).contains("cosmic energy")
                    || i > 1 && lines.get(i - 2).toLowerCase(Locale.ROOT).contains("cosmic energy"))) {
                sidebarEnergy(Long.parseLong(held.group(1).replaceAll("[,.]", "")),
                        Long.parseLong(held.group(2).replaceAll("[,.]", "")), nowMs);
                continue;
            }
            if (lower.equals("cosmic energy") || lower.matches("[0-9]+% \\(level [0-9]+\\)")) {
                continue;
            }
            if (lower.contains("tax")) {
                Matcher m = PERCENT.matcher(line);
                if (!m.find()) {
                    // The number may be on the next line; a tax line without a number (and no next line) is not a reading.
                    m = i + 1 < lines.size() ? PERCENT.matcher(lines.get(i + 1)) : null;
                    if (m != null && !m.find()) {
                        m = null;
                    }
                }
                if (m != null) {
                    found = Double.parseDouble(m.group(1).replace(',', '.'));
                } else if (lower.contains("none") || lower.contains("off") || lower.contains("inactive")) {
                    found = 0.0D;
                }
                used = true;
            }
            if (lower.contains("booster")) {
                shown(line, i + 1 < lines.size() ? lines.get(i + 1) : "", nowMs);
                used = true;
            }
            if ((lower.contains("xp") || lower.contains("exp")) && !lower.contains("tax")) {
                Matcher m = XP_OF.matcher(line);
                if (m.find()) {
                    long have = Math.round(Double.parseDouble(m.group(1).replace(",", "")) * unit(m.group(2)));
                    long need = Math.round(Double.parseDouble(m.group(3).replace(",", "")) * unit(m.group(4)));
                    xpLeft = Math.max(0L, need - have);
                    used = true;
                }
            }
            if (!used) {
                noteUnparsed("sidebar", line, lower);
            }
        }
        tax = found;
        sidebarXpLeft = xpLeft;
    }

    /**
     * The total XP from the sidebar: every increase is mined XP. A drop, a jump (another pickaxe / account) or a reading
     * after a long gap (the other sources counted meanwhile) only sets a new baseline.
     */
    void sidebarXp(long total, long nowMs) {
        if (sidebarXp >= 0L && total > sidebarXp && total - sidebarXp < SIDEBAR_JUMP && nowMs - sidebarXpMs <= SOURCE_MS) {
            gainXp(nowMs, total - sidebarXp);
        }
        sidebarXp = total;
        sidebarXpMs = nowMs;
    }

    /** The held pickaxe's energy from the sidebar; another capacity means another pickaxe (a new baseline). */
    void sidebarEnergy(long value, long max, long nowMs) {
        if (max <= 0L) {
            return; // no pickaxe held: "(0 / 0)"
        }
        if (max == sidebarEnergyMax && sidebarEnergy >= 0L && value > sidebarEnergy && nowMs - sidebarEnergyMs <= SOURCE_MS) {
            pickaxeGain(value - sidebarEnergy, nowMs);
        }
        sidebarEnergy = value;
        sidebarEnergyMax = max;
        sidebarEnergyMs = nowMs;
    }

    /** The boss bars' titles (formatting stripped). */
    public void bossBars(List<String> titles, long nowMs) {
        for (String title : titles) {
            String lower = title.toLowerCase(Locale.ROOT);
            if (lower.contains("booster") || MULTIPLIER.matcher(title).find() && remaining(title) > 0L) {
                shown(title, "", nowMs);
            } else {
                noteUnparsed("bossbar", title, lower);
            }
        }
    }

    // ── Reading ─────────────────────────────────────────────────────────────

    public Snapshot snapshot(long nowMs, int learnedRoutes, String mode) {
        return snapshot(nowMs, learnedRoutes, mode, "", -1);
    }

    /** @param botState the automation's state ("" = off), @param inventoryPercent used inventory slots (-1 = unknown) */
    public Snapshot snapshot(long nowMs, int learnedRoutes, String mode, String botState, int inventoryPercent) {
        long elapsed = Math.max(1L, nowMs - startMs);
        double opsWindow = Math.max(1.0D, Math.min(OPS_WINDOW, elapsed / 1000.0D));
        double rateWindow = Math.max(1.0D, Math.min(WINDOW, elapsed / 1000.0D));
        Double shownTax = tax;
        String note = "";
        if (nowMs < noTaxUntilMs) {
            shownTax = 0.0D;
            note = "Rations";
        }
        List<Booster> server = new ArrayList<>();
        List<Booster> personal = new ArrayList<>();
        chatBoosters.values().removeIf(b -> b.endsAtMs() > 0L && b.endsAtMs() <= nowMs);
        shownSeen.entrySet().removeIf(e -> {
            if (nowMs - e.getValue() > SEEN_MS) {
                shownBoosters.remove(e.getKey());
                return true;
            }
            return false;
        });
        Map<String, Booster> all = new LinkedHashMap<>(chatBoosters);
        all.putAll(shownBoosters);
        for (Map.Entry<String, Booster> e : all.entrySet()) {
            (isServer(e.getKey()) ? server : personal).add(e.getValue());
        }
        rations.entrySet().removeIf(e -> nowMs - e.getValue() > RATION_MS);
        for (String name : rations.keySet()) {
            personal.add(new Booster(name, rationMultiplier.getOrDefault(name, 1.0D), 0L));
        }
        double xpPerHour = barXpPerMinute >= 0.0D ? barXpPerMinute * 60.0D : xp.sum(nowMs, WINDOW) / rateWindow * 3600.0D;
        long left = sidebarXpLeft >= 0L ? sidebarXpLeft : vanillaXpLeft;
        double windowEnergy = energy.sum(nowMs, WINDOW) / rateWindow * 3600.0D;
        double windowXp = xp.sum(nowMs, WINDOW) / rateWindow * 3600.0D;
        long eta = left < 0L || xpPerHour <= 0.0D ? -1L : Math.round(left / xpPerHour * 3_600_000.0D);
        long primary = oresTotal - procOres;
        // Forecast: hits per second x (1 + proc chance x blocks per proc, measured; 2 until a proc was seen).
        double forecast = -1.0D;
        if (loreChance >= 0.0D) {
            double perProc = procEvents > 0L ? procOres / (double) procEvents : 2.0D;
            forecast = hits.sum(nowMs, OPS_WINDOW) / opsWindow * (1.0D + loreChance * perProc);
        }
        return new Snapshot(nowMs - startMs, ores.sum(nowMs, OPS_WINDOW) / opsWindow, oresTotal * 1000.0D / elapsed,
                oresTotal, barEnergyPerMinute >= 0.0D ? barEnergyPerMinute * 60.0D : windowEnergy,
                xpPerHour,
                shownTax, note, List.copyOf(server), List.copyOf(personal), learnedRoutes, mode,
                eta, primary <= 0L ? 0.0D : procOres / (double) primary, loreProcs, durability, forecast, loreChance,
                botState, inventoryPercent, windowEnergy, windowXp);
    }

    // ── Parsing helpers ─────────────────────────────────────────────────────

    /** A booster shown in the sidebar / a boss bar (the time may stand on the next line). */
    private void shown(String line, String next, long nowMs) {
        String name = boosterName(line);
        long left = remaining(line);
        if (left <= 0L) {
            left = remaining(next);
        }
        Matcher m = MULTIPLIER.matcher(line);
        double mult = m.find() ? Double.parseDouble(m.group(1)) : 1.0D;
        // "Server Booster: 1.5x XP (12:30)" shows "1.5x XP" (the key keeps "server" for the split).
        String display = name;
        int colon = line.indexOf(':');
        if (colon >= 0 && colon < line.length() - 1 && MULTIPLIER.matcher(line.substring(colon + 1)).find()) {
            display = line.substring(colon + 1).replaceAll("\\(.*?\\)|\\b[0-9]{1,2}:[0-9]{2}(:[0-9]{2})?\\b", "")
                    .replaceAll("\\s+", " ").trim();
        }
        shownBoosters.put(key(name), new Booster(display, mult, left > 0L ? nowMs + left : 0L));
        shownSeen.put(key(name), nowMs);
    }

    /** Booster activation / expiry in chat ("received" = an item given, not active). */
    private boolean boosterChat(String line, String lower, long nowMs) {
        String name = boosterName(stripPrefix(line));
        if (lower.contains("expired") || lower.contains("has ended") || lower.contains("run out") || lower.contains("worn off")) {
            chatBoosters.remove(key(name));
            return true;
        }
        if (lower.contains("activated") || lower.contains("consumed") || lower.contains("you used")
                || lower.contains("now active") || lower.contains("enabled") || lower.contains("started")) {
            Matcher m = MULTIPLIER.matcher(line);
            double mult = m.find() ? Double.parseDouble(m.group(1)) : 1.0D;
            long left = remaining(line);
            chatBoosters.put(key(name), new Booster(name, mult, left > 0L ? nowMs + left : 0L));
            return true;
        }
        return false;
    }

    /** Effects that are not called "booster": Rested XP, charge orbs, the Anti XP Tax and Lucky pets. */
    private boolean effectChat(String line, String lower, long nowMs) {
        Matcher rested = RESTED.matcher(line);
        if (rested.find()) {
            long left = remaining(rested.group(1));
            String name = rested.group(2) + "x Rested XP";
            chatBoosters.entrySet().removeIf(e -> e.getValue().name().endsWith("Rested XP"));
            chatBoosters.put(key(name), new Booster(name, Double.parseDouble(rested.group(2)), left > 0L ? nowMs + left : 0L));
            return true;
        }
        if (lower.contains("rested xp has run out")) {
            chatBoosters.entrySet().removeIf(e -> e.getValue().name().endsWith("Rested XP"));
            return true;
        }
        Matcher orbs = CHARGE_ORBS.matcher(line);
        if (orbs.find()) {
            double percent = Double.parseDouble(orbs.group(1));
            String name = "Charge Orbs +" + orbs.group(1) + "% Energy";
            chatBoosters.entrySet().removeIf(e -> e.getValue().name().startsWith("Charge Orbs"));
            chatBoosters.put(key(name), new Booster(name, 1.0D + percent / 100.0D, 0L));
            return true;
        }
        Matcher tax = ANTI_TAX.matcher(stripPrefix(line));
        if (tax.find()) {
            long left = remaining(tax.group(1));
            long end = left > 0L ? nowMs + left : nowMs + 30L * 60_000L;
            chatBoosters.put(key("No XP Tax"), new Booster("No XP Tax (pet)", 1.0D, end));
            noTaxUntilMs = Math.max(noTaxUntilMs, end);
            return true;
        }
        if (lower.contains("anti xp tax") && lower.contains("run out")) {
            chatBoosters.remove(key("No XP Tax"));
            noTaxUntilMs = nowMs;
            return true;
        }
        Matcher lucky = LUCKY.matcher(stripPrefix(line));
        if (lucky.find()) {
            String amount = lucky.group(1).toLowerCase(Locale.ROOT).replace("minutes", "m").replace("minute", "m")
                    .replace("min", "m").replace("seconds", "s").replace("second", "s").replace("hours", "h").replace("hour", "h");
            long left = remaining(amount.replace(" ", ""));
            chatBoosters.put(key("Lucky"), new Booster("Lucky (pet)", 1.0D, nowMs + Math.max(left, 60_000L)));
            return true;
        }
        return false;
    }

    private static boolean isServer(String key) {
        return key.contains("server") || key.contains("global") || key.contains("event");
    }

    /** "1.5x Shard Booster / 10m" → "1.5x Shard Booster": the text up to and with "booster", without the "(!)". */
    static String boosterName(String line) {
        String s = stripPrefix(line);
        int at = s.toLowerCase(Locale.ROOT).indexOf("booster");
        if (at >= 0) {
            s = s.substring(0, at + "booster".length());
        }
        // From the multiplier on ("You activated a 2x Energy Booster" → "2x Energy Booster").
        Matcher m = MULTIPLIER.matcher(s);
        if (m.find()) {
            s = s.substring(m.start());
        }
        return s.replaceAll("\\s+", " ").trim();
    }

    /** Time left in a line: "12:34", "1:02:03", "1h 5m", "10m", "30s" (ms; 0 = none). */
    static long remaining(String line) {
        Matcher c = CLOCK.matcher(line);
        if (c.find()) {
            long a = Long.parseLong(c.group(1));
            long b = Long.parseLong(c.group(2));
            return c.group(3) != null ? ((a * 60L + b) * 60L + Long.parseLong(c.group(3))) * 1000L : (a * 60L + b) * 1000L;
        }
        long ms = 0L;
        Matcher u = UNITS.matcher(line);
        while (u.find()) {
            long n = Long.parseLong(u.group(1));
            ms += switch (Character.toLowerCase(u.group(2).charAt(0))) {
                case 'd' -> n * 86_400_000L;
                case 'h' -> n * 3_600_000L;
                case 'm' -> n * 60_000L;
                default -> n * 1_000L;
            };
        }
        return ms;
    }

    /** "1,234", "1.5k", "2M" → a whole number. */
    static long amount(Matcher m) {
        return Math.round(Double.parseDouble(m.group(1).replace(",", "")) * unit(m.group(2)));
    }

    private static double unit(@Nullable String unit) {
        if (unit == null) {
            return 1.0D;
        }
        return switch (Character.toLowerCase(unit.charAt(0))) {
            case 'k' -> 1_000.0D;
            case 'm' -> 1_000_000.0D;
            default -> 1_000_000_000.0D;
        };
    }

    /** The energy number in a pickaxe lore line ("Energy: 1,234 / 50,000" → 1234); -1 = none. */
    public static long loreEnergy(String line) {
        return io.theprisons.core.cosmic.parse.PickaxeLore.energyOfLine(line);
    }

    /**
     * The held pickaxe's energy from its lore; -1 = none. The rules live in {@code PickaxeLore} (shared with the Cosmic model):
     * Cosmic writes a heading "Cosmic Energy", a bar line and then "(242,159 / 293,135)"; the "Battery" block below has the same
     * form and is not the energy. Older form: "Energy: 1,234 / 50,000" on one line.
     */
    public static long loreEnergy(java.util.List<String> lore) {
        return io.theprisons.core.cosmic.parse.PickaxeLore.energy(lore);
    }

    private static String stripPrefix(String line) {
        return line.replaceFirst("^\\s*[(\\[]!?[)\\]]\\s*", "").trim();
    }

    private static String key(String name) {
        return name.toLowerCase(Locale.ROOT);
    }

    private static String title(String words) {
        StringBuilder sb = new StringBuilder();
        for (String w : words.trim().split("\\s+")) {
            if (!w.isEmpty()) {
                sb.append(sb.isEmpty() ? "" : " ").append(Character.toUpperCase(w.charAt(0))).append(w.substring(1));
            }
        }
        return sb.toString();
    }

    private void noteUnparsed(String source, String line, String lower) {
        if (!(lower.contains("booster") || lower.contains("energy") || lower.contains("xp") || lower.contains("tax"))
                || unparsedSeen.size() >= UNPARSED_MAX) {
            return;
        }
        // Numbers vary: one entry per wording.
        if (unparsedSeen.add(source + ":" + lower.replaceAll("[0-9][0-9,.:]*", "#"))) {
            unparsed.accept(source + ": " + line);
        }
    }

    /** Sums of the last {@value #WINDOW} seconds in 1-second buckets. */
    static final class Rolling {
        private final long[] sums = new long[WINDOW];
        private final long[] seconds = new long[WINDOW];

        Rolling() {
            java.util.Arrays.fill(seconds, Long.MIN_VALUE);
        }

        void add(long nowMs, long amount) {
            long second = Math.floorDiv(nowMs, 1000L);
            int i = (int) Math.floorMod(second, (long) WINDOW);
            if (seconds[i] != second) {
                seconds[i] = second;
                sums[i] = 0L;
            }
            sums[i] += amount;
        }

        /** The last {@code window} seconds, this one included. */
        long sum(long nowMs, int window) {
            long second = Math.floorDiv(nowMs, 1000L);
            long total = 0L;
            for (int k = 0; k < Math.min(window, WINDOW); k++) {
                int i = (int) Math.floorMod(second - k, (long) WINDOW);
                if (seconds[i] == second - k) {
                    total += sums[i];
                }
            }
            return total;
        }
    }
}
