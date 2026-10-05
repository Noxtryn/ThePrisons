package com.freelocs.theprisons.modules.hud;

import com.freelocs.theprisons.core.client.TextStrip;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Locale;

/**
 * The two kinds of session stats (pure logic): <b>Ore Mining</b> and <b>Bandit</b>. While the ore macro runs it is
 * always Ore Mining. Without it, the latest action decides: a block broken → Ore Mining; a bandit hit or killed, or a
 * player killed → Bandit. Each has its own clock, numbers and save (see {@link ActivityLog}, {@link ActivityStore}).
 *
 * <p>Bandit kills come from the server's own counter in the sidebar on the bandit moons ("Bandits Killed" / "80",
 * game log 2026-10-04); player kills from the chat's kill line with the player as the killer ("× Killer killed /
 * threw Victim ...").</p>
 */
final class SessionMode {
    static final String ORE = "Ore Mining";
    static final String BANDIT = "Bandit";
    /** A jump of the sidebar counter larger than this is a new reading (another moon / reset), not kills. */
    static final long MAX_KILL_STEP = 50L;

    private SessionMode() {
    }

    /** Which mode an action switches to: the macro running keeps it at Ore Mining. */
    static String of(String action, boolean macroRunning) {
        return macroRunning ? ORE : action;
    }

    /** Older saves kept one activity per ore / bandit kind ("Gold", "Gold Bandits"): which mode they belong to. */
    static String migrate(String savedName) {
        if (savedName.equals(ORE) || savedName.equals(BANDIT)) {
            return savedName;
        }
        return savedName.toLowerCase(Locale.ROOT).contains("bandit") ? BANDIT : ORE;
    }

    private static String plain(String line) {
        return TextStrip.strip(line).replaceAll("[^\\p{L}\\p{N} ]", "").strip().toLowerCase(Locale.ROOT);
    }

    /** The sidebar's "Bandits Killed" counter (the number on the same or the next line); -1 = not shown. */
    static long banditsKilled(List<String> sidebar) {
        for (int i = 0; i < sidebar.size(); i++) {
            String line = plain(sidebar.get(i));
            if (!line.startsWith("bandits killed")) {
                continue;
            }
            String rest = line.substring("bandits killed".length()).strip();
            if (rest.isEmpty() && i + 1 < sidebar.size()) {
                rest = plain(sidebar.get(i + 1));
            }
            return rest.matches("[0-9]+") ? Long.parseLong(rest) : -1L;
        }
        return -1L;
    }

    /** Kills between two readings of the counter ({@code -1} = unknown): 0 unless it rose by 1..{@value #MAX_KILL_STEP}. */
    static long killStep(long before, long now) {
        if (before < 0L || now < 0L || now <= before || now - before > MAX_KILL_STEP) {
            return 0L;
        }
        return now - before;
    }

    /** The head sprite in front of a name in Cosmic's chat ("[Payney head]Payney"). */
    private static final java.util.regex.Pattern HEAD = java.util.regex.Pattern.compile("\\[[^\\]]* head]");

    /**
     * The chat's kill line with {@code me} as the killer: "× M4cL4ren killed Payney with Iron Sword 30" (the first name
     * after "×" is the killer). @return the victim, or {@code null}
     */
    static @Nullable String ownKill(String message, String me) {
        String text = HEAD.matcher(TextStrip.strip(message)).replaceAll("").strip();
        if (!text.startsWith("×") || me.isEmpty()) {
            return null;
        }
        String[] words = text.substring(1).strip().split("\\s+");
        if (words.length < 3 || !words[0].replaceAll("[^A-Za-z0-9_]", "").equals(me)) {
            return null;
        }
        for (int i = 2; i < words.length; i++) {
            String name = words[i].replaceAll("[^A-Za-z0-9_]", "");
            if (name.matches("[A-Za-z0-9_]{3,16}") && !name.equals(me) && !name.equals("the") && !name.equals("with")) {
                return name;
            }
        }
        return null;
    }
}
