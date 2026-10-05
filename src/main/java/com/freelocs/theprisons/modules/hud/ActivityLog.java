package com.freelocs.theprisons.modules.hud;

import org.jspecify.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;

/**
 * Stats per kind of session ({@link SessionMode}: "Ore Mining", "Bandit"). Every activity has its own clock that only
 * runs while it is the current one and you keep acting (mining / hitting); it pauses when you switch to another
 * activity or stay idle for {@link #IDLE_MS}, and continues where it stopped when you come back. Each activity keeps its
 * own {@link CosmicStats}, fed with the activity clock, so uptime, ores and the rates are those of that activity.
 */
final class ActivityLog {
    /** No mining / hitting for this long pauses the clock. */
    static final long IDLE_MS = 120_000L;

    static final class Activity {
        final String name;
        final CosmicStats stats;
        /** Active time before the current run. */
        long baseMs;
        /** Wall time the current run started, -1 while paused. */
        long resumedAt = -1L;
        long lastActionMs;
        /** Bandit: bandits killed (the sidebar counter) and players killed. */
        long kills;
        long playerKills;
        /** What exactly right now ("Gold", "Gold Bandits"; not saved). */
        String detail = "";

        Activity(String name, CosmicStats stats) {
            this.name = name;
            this.stats = stats;
        }

        /** The activity clock (active milliseconds) at wall time {@code wallMs}. */
        long clock(long wallMs) {
            return resumedAt >= 0L ? baseMs + Math.max(0L, wallMs - resumedAt) : baseMs;
        }

        boolean running() {
            return resumedAt >= 0L;
        }

        void pause(long wallMs) {
            if (resumedAt >= 0L) {
                baseMs += Math.max(0L, wallMs - resumedAt);
                resumedAt = -1L;
            }
        }
    }

    private final Map<String, Activity> activities = new LinkedHashMap<>();
    private final Function<String, CosmicStats> factory;
    private @Nullable Activity current;

    ActivityLog(Function<String, CosmicStats> factory) {
        this.factory = factory;
    }

    /** The player acted on {@code name} (broke its block, hit it): switches to it if needed and keeps its clock running. */
    Activity act(String name, long wallMs) {
        Activity a = current;
        if (a == null || !a.name.equals(name)) {
            if (a != null) {
                a.pause(Math.min(wallMs, a.lastActionMs + 5_000L));
            }
            a = activities.computeIfAbsent(name, n -> new Activity(n, factory.apply(n)));
            current = a;
        }
        if (!a.running()) {
            a.resumedAt = wallMs;
        }
        a.lastActionMs = wallMs;
        return a;
    }

    /** Pauses the current activity after {@link #IDLE_MS} without action (the clock stops at the last action + 5 s). */
    void tick(long wallMs) {
        Activity a = current;
        if (a != null && a.running() && wallMs - a.lastActionMs > IDLE_MS) {
            a.pause(a.lastActionMs + 5_000L);
        }
    }

    @Nullable Activity current() {
        return current;
    }

    Map<String, Activity> all() {
        return activities;
    }

    /** The activity {@code name} without switching to it (created paused when new). */
    Activity entry(String name) {
        return activities.computeIfAbsent(name, n -> new Activity(n, factory.apply(n)));
    }

    /** Restores a saved activity (paused); saves of the same name add up (older saves per ore / bandit kind). */
    void restore(String name, long activeMs, long ores, long kills, long playerKills) {
        Activity a = entry(name);
        a.baseMs += activeMs;
        a.stats.restoreOres(ores);
        a.kills += kills;
        a.playerKills += playerKills;
    }

    /** The activity shown when the session was saved: shown again (paused) until the next action. */
    void restoreCurrent(String name) {
        Activity a = activities.get(name);
        if (a != null) {
            current = a;
        }
    }

    void clear() {
        activities.clear();
        current = null;
    }
}
