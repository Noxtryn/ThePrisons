package io.theprisons.modules.mining.ore;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Level steps of the pickaxe that start a trip to spawn: every 5th level (5, 10 ... 100) and every 5th prestige
 * (5, 10, 15 ...). The level and the prestige are read from the pickaxe: its name ("... Pickaxe 81 II" = level 81,
 * prestige II), else a "Prestige" line of its lore.
 */
public final class Milestones {
    /** Level and prestige of a pickaxe; -1 = not found. */
    public record Reading(int level, int prestige) {
        static final Reading NONE = new Reading(-1, 0);
    }

    private static final Pattern NAME = Pattern.compile("(?i)\\b(\\d{1,3})\\b(?:\\s+([IVXLC]+))?\\s*$");
    private static final Pattern PRESTIGE_LINE = Pattern.compile("(?i)prestige\\D{0,4}(\\d{1,3}|[IVXLC]+)\\b");

    private int lastLevel = -1;
    private int lastPrestige = -1;

    public static Reading read(String name, List<String> lore) {
        Matcher m = NAME.matcher(name.strip());
        if (!m.find()) {
            return Reading.NONE;
        }
        int level = Integer.parseInt(m.group(1));
        int prestige = m.group(2) == null ? -1 : roman(m.group(2));
        if (prestige < 0) {
            for (String line : lore) {
                Matcher p = PRESTIGE_LINE.matcher(line);
                if (p.find()) {
                    String v = p.group(1);
                    prestige = Character.isDigit(v.charAt(0)) ? Integer.parseInt(v) : roman(v);
                    break;
                }
            }
        }
        return new Reading(level, Math.max(0, prestige));
    }

    static int roman(String text) {
        int total = 0;
        int prev = 0;
        for (int i = text.length() - 1; i >= 0; i--) {
            int v = switch (Character.toUpperCase(text.charAt(i))) {
                case 'I' -> 1;
                case 'V' -> 5;
                case 'X' -> 10;
                case 'L' -> 50;
                case 'C' -> 100;
                default -> 0;
            };
            total += v < prev ? -v : v;
            prev = Math.max(prev, v);
        }
        return total;
    }

    /**
     * A new reading: whether it crossed a step - the level went to a multiple of 5 (5-100) or the prestige to a
     * multiple of 5. The first reading only sets the start (no trip for the level the macro began with).
     */
    public boolean crossed(Reading now) {
        if (now.level() < 0) {
            return false;
        }
        boolean first = lastLevel < 0;
        boolean prestigeStep = !first && now.prestige() != lastPrestige && now.prestige() >= 5 && now.prestige() % 5 == 0;
        boolean levelStep = !first && now.level() != lastLevel && now.level() >= 5 && now.level() <= 100 && now.level() % 5 == 0;
        lastLevel = now.level();
        lastPrestige = now.prestige();
        return prestigeStep || levelStep;
    }

    public void reset() {
        lastLevel = -1;
        lastPrestige = -1;
    }
}
