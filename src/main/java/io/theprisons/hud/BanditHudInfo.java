package io.theprisons.hud;

import java.util.List;
import java.util.function.Supplier;

/**
 * What the Bandit Macro tells the dashboard, in words a player understands ("Hunting", "Charging") - not planner terms. {@code charge} is 0..1 or -1 when it
 * cannot be read. The debug lines (tactical position, nav primitive, replan reason ...) are only shown in developer mode.
 */
public record BanditHudInfo(boolean running, String state, String target, double targetDistance, int threats, String spear, double charge, String recall,
                            String tactical, String route, boolean dryRun, long kills, long playerKills, List<String> debug) {
    public BanditHudInfo {
        debug = List.copyOf(debug);
    }

    private static volatile Supplier<BanditHudInfo> source = () -> null;

    /** The Bandit Macro registers itself here; the dashboard reads it (null = no Bandit Macro information). */
    public static void source(Supplier<BanditHudInfo> s) {
        source = s;
    }

    public static BanditHudInfo current() {
        return source.get();
    }
}
