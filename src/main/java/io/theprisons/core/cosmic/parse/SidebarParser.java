package io.theprisons.core.cosmic.parse;

import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;

/**
 * The numbers the Cosmic sidebar carries, read once for everybody. Every field is {@code null} when the sidebar does not show
 * it - a missing sidebar gives {@link #EMPTY}, never zeros.
 *
 * @param taxPercent  "Guard XP Tax 10%" (the number may be on the next line); 0 only when the sidebar says there is none
 * @param energyNow   the held pickaxe's energy, "(456,410 / 1,259,523)" under "Cosmic Energy"
 * @param energyMax   its capacity (0 = no pickaxe held)
 * @param energyLevel the "34% (level 71)" line: level
 * @param energyPercent the same line's percent
 * @param totalXp     the "Level" row "81 (16,321,009 XP)": total XP
 */
public record SidebarParser(@Nullable Double taxPercent, @Nullable Long energyNow, @Nullable Long energyMax,
                            @Nullable Integer energyLevel, @Nullable Integer energyPercent, @Nullable Long totalXp) {
    public static final SidebarParser EMPTY = new SidebarParser(null, null, null, null, null, null);

    public static SidebarParser parse(@Nullable List<String> lines) {
        if (lines == null || lines.isEmpty()) {
            return EMPTY;
        }
        Double tax = null;
        Long now = null;
        Long max = null;
        Integer level = null;
        Integer percent = null;
        Long xp = null;
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            String lower = line.toLowerCase(Locale.ROOT);
            Matcher total = CosmicPatterns.TOTAL_XP.matcher(line);
            if (total.matches()) {
                xp = CosmicPatterns.wholeNumber(total.group(1));
                continue;
            }
            Matcher held = CosmicPatterns.ENERGY_OF.matcher(line);
            if (held.matches() && (i > 0 && lines.get(i - 1).toLowerCase(Locale.ROOT).contains("cosmic energy")
                    || i > 1 && lines.get(i - 2).toLowerCase(Locale.ROOT).contains("cosmic energy"))) {
                now = CosmicPatterns.wholeNumber(held.group(1));
                max = CosmicPatterns.wholeNumber(held.group(2));
                continue;
            }
            Matcher lvl = CosmicPatterns.ENERGY_LEVEL.matcher(line.trim());
            if (lvl.matches()) {
                percent = Integer.parseInt(lvl.group(1));
                level = Integer.parseInt(lvl.group(2));
                continue;
            }
            if (lower.contains("tax")) {
                Matcher m = CosmicPatterns.PERCENT.matcher(line);
                if (!m.find()) {
                    m = i + 1 < lines.size() ? CosmicPatterns.PERCENT.matcher(lines.get(i + 1)) : null;
                    if (m != null && !m.find()) {
                        m = null;
                    }
                }
                if (m != null) {
                    tax = Double.parseDouble(m.group(1).replace(',', '.'));
                } else if (lower.contains("none") || lower.contains("off") || lower.contains("inactive")) {
                    tax = 0.0D;
                }
            }
        }
        return new SidebarParser(tax, now, max, level, percent, xp);
    }
}
