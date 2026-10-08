package io.theprisons.core.cosmic.parse;

import java.util.Locale;
import java.util.regex.Matcher;

/**
 * Which zone the player is in, from the server's chat lines. The rules are the ones {@code ThePrisonsCore} used on its own
 * before; it now calls this, so the model and the old {@code lastZone()} cannot disagree.
 */
public final class ZoneParser {
    private ZoneParser() {
    }

    /**
     * @param current the zone known so far ("" = none)
     * @param line    a system chat line, formatting stripped and lower case
     * @return the zone after this line: "diamond", "gold" ... (from "You entered a Diamond Zone"), "spawn", "mine",
     * "" (just joined, not known), or {@code current} when the line says nothing about zones
     */
    public static String next(String current, String line) {
        Matcher zone = CosmicPatterns.ZONE.matcher(line);
        if (zone.find()) {
            return zone.group(1).strip();
        }
        if (line.contains("welcome to spawn")) {
            return "spawn";
        }
        if (line.contains("to cosmicprisons")) {
            // Joined the server ("Welcome, <name> to CosmicPrisons!"): where the player is is not known yet.
            return "";
        }
        if (line.matches(".*welcome to the .+ mine.*")) {
            // Warped into a mine: an older "Diamond Zone" no longer says where the player is.
            return "mine";
        }
        return current;
    }

    /** Convenience for text that is not normalised yet. */
    public static String nextFromText(String current, String strippedText) {
        return next(current, strippedText.toLowerCase(Locale.ROOT));
    }
}
