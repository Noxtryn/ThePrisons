package io.theprisons.core.cosmic.parse;

import java.util.regex.Pattern;

/**
 * The text patterns of Cosmic Prisons that more than one reader needs. {@code CosmicStats} (session HUD), the pickaxe lore
 * reader and the sidebar parser all use these constants instead of keeping a private copy.
 */
public final class CosmicPatterns {
    private CosmicPatterns() {
    }

    /** "12%" / "3,5 %" - group 1 = the number. */
    public static final Pattern PERCENT = Pattern.compile("([0-9]+(?:[.,][0-9]+)?)\\s*%");
    /** Sidebar "Level" value "81 (16,321,009 XP)": group 1 = the total XP. */
    public static final Pattern TOTAL_XP = Pattern.compile("^[0-9][0-9,.]*\\s*\\(([0-9][0-9,.]*)\\s*xp\\)$", Pattern.CASE_INSENSITIVE);
    /** Sidebar under "Cosmic Energy": "(429,210 / 1,259,523)" - the pickaxe's energy and its capacity. */
    public static final Pattern ENERGY_OF = Pattern.compile("^\\(([0-9][0-9,.]*)\\s*/\\s*([0-9][0-9,.]*)\\)$");
    /** "242,159 / 293,135" (brackets optional): group 1 = the energy now, group 2 = the capacity. */
    public static final Pattern ENERGY_FILL = Pattern.compile("(\\d[\\d,.]*)\\s*/\\s*(\\d[\\d,.]*)");
    /** Sidebar energy level line "34% (level 71)": group 1 = percent, group 2 = level. */
    public static final Pattern ENERGY_LEVEL = Pattern.compile("^([0-9]+)%\\s*\\(level\\s*([0-9]+)\\)$", Pattern.CASE_INSENSITIVE);
    /** "You entered a Diamond Zone" / "You have entered the Gold Zone" (lower case input): group 1 = the zone name. */
    public static final Pattern ZONE = Pattern.compile("you (?:have )?entered (?:a|an|the) (.+?) zone");

    /** "1,234" / "1.234" -> 1234. Throws {@link NumberFormatException} when there is no digit. */
    public static long wholeNumber(String text) {
        return Long.parseLong(text.replaceAll("[,.\\s]", ""));
    }
}
