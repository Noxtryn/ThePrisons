package io.theprisons.items;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The enchantments a Cosmic item lists in its lore, as "name level" ("Outbreak I", "Absolute Efficiency IV"): a short line that is a few words and a
 * roman or arabic level, nothing else. Deliberately strict - a line that merely looks like prose is not an enchant. Inferred from the shape of the
 * lines (seen on the catalog items), not from a server list, so it is only used where an item can carry enchants.
 */
public final class LoreEnchants {
    private static final Pattern LINE = Pattern.compile("^([A-Z][A-Za-z'’]*(?: [A-Za-z'’]+){0,3}) (I{1,3}|IV|V|VI{0,3}|IX|X{1,2}|\\d{1,2})$");

    private LoreEnchants() {
    }

    public static List<String> parse(List<String> lore) {
        List<String> out = new ArrayList<>();
        for (String raw : lore) {
            Matcher m = LINE.matcher(raw.strip());
            if (m.matches()) {
                out.add((m.group(1) + " " + m.group(2)).toLowerCase(java.util.Locale.ROOT));
            }
        }
        return out;
    }
}
