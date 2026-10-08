package io.theprisons.hud;

import io.theprisons.modules.hud.CosmicStats;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static io.theprisons.hud.SessionView.Tone;

/**
 * Builds the {@link SessionView} for the Ore Macro and for the Bandit Macro from what they really know. They do not share one field list: the Ore dashboard is
 * about OP/s, ores, energy and XP; the Bandit dashboard about kills, the target, threats and the spear. A value that is not known is left out - never a dash,
 * never a made-up number.
 */
public final class SessionViewFactory {
    private SessionViewFactory() {
    }

    // ── formatting ───────────────────────────────────────────────────────────

    public static String compact(double v) {
        double a = Math.abs(v);
        if (a >= 1e9) {
            return String.format(Locale.ROOT, "%.2fB", v / 1e9);
        }
        if (a >= 1e6) {
            return String.format(Locale.ROOT, "%.2fM", v / 1e6);
        }
        if (a >= 1e4) {
            return String.format(Locale.ROOT, "%.1fK", v / 1e3);
        }
        return String.format(Locale.ROOT, "%.0f", v);
    }

    public static String rate(double perHour) {
        return compact(perHour) + "/h";
    }

    public static String clock(long ms) {
        long s = Math.max(0L, ms) / 1000L;
        return String.format(Locale.ROOT, "%02d:%02d:%02d", s / 3600L, s / 60L % 60L, s % 60L);
    }

    /** Time to the next level as HH:MM. */
    public static String eta(long ms) {
        long minutes = (ms + 59_999L) / 60_000L;
        return minutes > 99L * 60L ? ">99:00" : String.format(Locale.ROOT, "%02d:%02d", minutes / 60L, minutes % 60L);
    }

    private static String boosterName(CosmicStats.Booster b) {
        String name = b.name().replaceFirst("(?i)\\s*booster$", "");
        return name.isEmpty() ? String.format(Locale.ROOT, "%.1fx", b.multiplier()) : name;
    }

    private static String boosterTime(CosmicStats.Booster b, long nowMs) {
        if (b.endsAtMs() <= 0L) {
            return "ACTIVE";
        }
        long s = Math.max(0L, (b.endsAtMs() - nowMs) / 1000L);
        return s >= 3600L ? String.format(Locale.ROOT, "%d:%02d:%02d", s / 3600L, s / 60L % 60L, s % 60L) : String.format(Locale.ROOT, "%d:%02d", s / 60L, s % 60L);
    }

    private static List<SessionView.BoosterRow> boosters(CosmicStats.Snapshot s, long now) {
        List<SessionView.BoosterRow> out = new ArrayList<>();
        for (CosmicStats.Booster b : s.serverBoosters()) {
            out.add(new SessionView.BoosterRow(boosterName(b), boosterTime(b, now), Tone.XP));
        }
        for (CosmicStats.Booster b : s.personalBoosters()) {
            out.add(new SessionView.BoosterRow(boosterName(b), boosterTime(b, now), Tone.ENERGY));
        }
        return out;
    }

    private static String join(String... parts) {
        List<String> out = new ArrayList<>();
        for (String p : parts) {
            if (p != null && !p.isBlank() && !p.equals("Idle")) {
                out.add(p);
            }
        }
        return out.isEmpty() ? "Idle" : String.join(" • ", out);
    }

    private static String nice(String botState) {
        if (botState.isEmpty()) {
            return "";
        }
        String lower = botState.toLowerCase(Locale.ROOT);
        return Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
    }

    // ── Ore mining ───────────────────────────────────────────────────────────

    /**
     * @param activity   what the macro is doing ("Mining Gold", its step); "Idle" when nothing
     * @param debugRows  internal rows (empty unless developer mode)
     */
    public static SessionView ore(CosmicStats.Snapshot s, String activity, boolean showEta, boolean showYield, long now, boolean running, List<SessionView.Row> debugRows) {
        List<SessionView.Kpi> kpis = new ArrayList<>();
        kpis.add(new SessionView.Kpi(String.format(Locale.ROOT, "%.2f", s.opsRecent()), "OP/s", s.opsAverage() > 0.0D ? String.format(Locale.ROOT, "avg %.2f", s.opsAverage()) : null,
                Tone.PRIMARY));
        kpis.add(new SessionView.Kpi(compact(s.ores()), "ORES", s.procShare() > 0.0D && showYield ? String.format(Locale.ROOT, "procs +%.0f%%", s.procShare() * 100.0D) : null, Tone.PRIMARY));
        kpis.add(new SessionView.Kpi(rate(s.energyPerHour()), "ENERGY", s.energyAvgPerHour() >= 0.0D ? "avg " + rate(s.energyAvgPerHour()) : null, Tone.ENERGY));
        kpis.add(new SessionView.Kpi(rate(s.xpPerHour()), "XP", s.xpAvgPerHour() >= 0.0D ? "avg " + rate(s.xpAvgPerHour()) : null, Tone.XP));

        List<SessionView.Row> stats = new ArrayList<>();
        if (!s.botState().isEmpty()) {
            stats.add(new SessionView.Row("State", s.botState().toUpperCase(Locale.ROOT), s.botState().equals("ESCAPING") ? Tone.BAD : s.botState().equals("MINING") ? Tone.GOOD : Tone.NEUTRAL));
        }
        if (showEta && s.levelUpEtaMs() >= 0L) {
            stats.add(new SessionView.Row("Level ETA", eta(s.levelUpEtaMs()), Tone.XP));
        }
        if (s.taxPercent() != null) {
            double tax = s.taxPercent();
            stats.add(new SessionView.Row("Guard Tax", String.format(Locale.ROOT, "%.1f%%", tax), tax <= 0.0D ? Tone.GOOD : tax < 10.0D ? Tone.TIME : Tone.WARN));
        }
        if (!s.mode().isEmpty()) {
            stats.add(new SessionView.Row("Mode", s.mode(), Tone.NEUTRAL));
        }

        List<SessionView.Row> detail = new ArrayList<>();
        if (showYield && s.forecastOps() >= 0.0D) {
            detail.add(new SessionView.Row("Forecast", String.format(Locale.ROOT, "%.2f OP/s", s.forecastOps()), Tone.PRIMARY));
        }
        if (showYield && !s.loreProcs().isEmpty()) {
            detail.add(new SessionView.Row("Enchants", s.loreProcs(), Tone.TIME));
        }
        if (s.learnedRoutes() > 0) {
            detail.add(new SessionView.Row("Routes", String.valueOf(s.learnedRoutes()), Tone.NEUTRAL));
        }
        if (s.durability() >= 0.0D && s.durability() < 0.10D) {
            detail.add(0, new SessionView.Row("Pickaxe", "REPAIR NEEDED", Tone.BAD));
        }
        String state = join(running ? "Running" : "Paused", activity.equals("Idle") ? "" : activity);
        return new SessionView(SessionView.Kind.ORE, "THEPRISONS • ORE MINING", clock(s.uptimeMs()), state, kpis,
                new SessionView.Activity(activity.isEmpty() ? "Idle" : activity, s.inventoryPercent()), stats, detail, boosters(s, now), debugRows);
    }

    // ── Bandit ───────────────────────────────────────────────────────────────

    public static SessionView bandit(CosmicStats.Snapshot s, @Nullable BanditHudInfo b, long kills, long playerKills, boolean showEta, long now, List<SessionView.Row> debugRows) {
        double hours = s.uptimeMs() / 3_600_000.0D;
        List<SessionView.Kpi> kpis = new ArrayList<>();
        kpis.add(new SessionView.Kpi(compact(kills), "KILLS", playerKills > 0 ? playerKills + " players" : null, Tone.PRIMARY));
        kpis.add(new SessionView.Kpi(hours > 0.01D ? String.format(Locale.ROOT, "%.0f", kills / hours) : "0", "KILLS/h", null, Tone.PRIMARY));
        kpis.add(new SessionView.Kpi(rate(s.energyPerHour()), "ENERGY", s.energyAvgPerHour() >= 0.0D ? "avg " + rate(s.energyAvgPerHour()) : null, Tone.ENERGY));
        kpis.add(new SessionView.Kpi(rate(s.xpPerHour()), "XP", s.xpAvgPerHour() >= 0.0D ? "avg " + rate(s.xpAvgPerHour()) : null, Tone.XP));

        List<SessionView.Row> stats = new ArrayList<>();
        List<SessionView.Row> detail = new ArrayList<>();
        String activity = "Idle";
        if (b != null) {
            activity = b.running() ? (b.target().isEmpty() ? "Searching" : "Fighting") : "Idle";
            if (!b.target().isEmpty()) {
                String target = b.targetDistance() >= 0.0D ? b.target() + " • " + String.format(Locale.ROOT, "%.0fm", b.targetDistance()) : b.target();
                stats.add(new SessionView.Row("Target", target, Tone.PRIMARY));
            }
            stats.add(new SessionView.Row("Threats", String.valueOf(b.threats()), b.threats() >= 3 ? Tone.BAD : b.threats() > 0 ? Tone.WARN : Tone.GOOD));
            stats.add(new SessionView.Row("State", b.state(), Tone.NEUTRAL));
            stats.add(new SessionView.Row("Spear", b.spear(), Tone.NEUTRAL));
            if (b.charge() >= 0.0D) {
                stats.add(new SessionView.Row("Charge", Math.round(b.charge() * 100.0D) + "%", Tone.ENERGY));
            }
            if (!b.recall().isEmpty()) {
                stats.add(new SessionView.Row("Recall", b.recall(), Tone.NEUTRAL));
            }
            if (!b.tactical().isEmpty()) {
                detail.add(new SessionView.Row("Position", b.tactical(), Tone.NEUTRAL));
            }
            if (!b.route().isEmpty()) {
                detail.add(new SessionView.Row("Route", b.route(), Tone.NEUTRAL));
            }
            if (b.dryRun()) {
                detail.add(new SessionView.Row("Mode", "DRY RUN (no attacks)", Tone.WARN));
            }
        }
        if (showEta && s.levelUpEtaMs() >= 0L) {
            detail.add(new SessionView.Row("Level ETA", eta(s.levelUpEtaMs()), Tone.XP));
        }
        List<SessionView.Row> dbg = new ArrayList<>(debugRows);
        if (b != null) {
            for (String line : b.debug()) {
                dbg.add(new SessionView.Row("", line, Tone.MUTED));
            }
        }
        String state = join(b != null && b.running() ? "Running" : "Paused", b == null ? "" : b.state());
        return new SessionView(SessionView.Kind.BANDIT, "THEPRISONS • BANDITS", clock(s.uptimeMs()), state, kpis,
                new SessionView.Activity(activity, -1), stats, detail, boosters(s, now), dbg);
    }

    public static String stateLabel(String botState) {
        return nice(botState);
    }
}
