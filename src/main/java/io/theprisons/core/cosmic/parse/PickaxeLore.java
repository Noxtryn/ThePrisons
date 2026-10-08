package io.theprisons.core.cosmic.parse;

import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;

/**
 * Reads a Cosmic pickaxe's energy from its lore. Cosmic writes a heading "Cosmic Energy", a bar line ("||||| 82.0%") and
 * then "(242,159 / 293,135)"; the "Battery" block below has the same form and is not the energy. Older form:
 * "Energy: 1,234 / 50,000" on one line. {@code CosmicStats.loreEnergy} delegates here.
 */
public final class PickaxeLore {
    /** Energy now and capacity. */
    public record Energy(long now, long capacity) {
    }

    private PickaxeLore() {
    }

    /** The energy number in one lore line ("Energy: 1,234 / 50,000" -> 1234); -1 = none. */
    public static long energyOfLine(String line) {
        // Only "energy now / energy full" counts: "+34% Energy Gain from 6 Charge Orbs" is not the pickaxe's energy.
        if (!line.toLowerCase(Locale.ROOT).contains("energy")) {
            return -1L;
        }
        Matcher m = CosmicPatterns.ENERGY_FILL.matcher(line);
        return m.find() ? CosmicPatterns.wholeNumber(m.group(1)) : -1L;
    }

    /** The held pickaxe's energy now from its lore; -1 = none. */
    public static long energy(List<String> lore) {
        Energy read = read(lore);
        return read == null ? -1L : read.now();
    }

    /** Energy now and capacity, or {@code null} when the lore has no energy section. */
    public static @Nullable Energy read(List<String> lore) {
        for (int i = 0; i < lore.size(); i++) {
            String line = lore.get(i).trim();
            if (line.equalsIgnoreCase("cosmic energy") || line.equalsIgnoreCase("energy")) {
                for (int j = i + 1; j <= Math.min(lore.size() - 1, i + 3); j++) {
                    Matcher m = CosmicPatterns.ENERGY_FILL.matcher(lore.get(j));
                    if (m.find()) {
                        return new Energy(CosmicPatterns.wholeNumber(m.group(1)), CosmicPatterns.wholeNumber(m.group(2)));
                    }
                }
            }
        }
        for (String line : lore) {
            if (line.toLowerCase(Locale.ROOT).contains("energy")) {
                Matcher m = CosmicPatterns.ENERGY_FILL.matcher(line);
                if (m.find()) {
                    return new Energy(CosmicPatterns.wholeNumber(m.group(1)), CosmicPatterns.wholeNumber(m.group(2)));
                }
            }
        }
        return null;
    }
}
