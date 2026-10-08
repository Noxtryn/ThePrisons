package io.theprisons.modules.qol.market;

import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Which item a market name is (pure logic): the same ware appears as "Gold Satchel", "Gold Satchel (0 / 2,304 Ores)"
 * or "Iron Boots 0" and with its rarity in front ("Godly Contraband"). The identity is the cleaned name, split into the
 * family ("Contraband") and the rarity ("Godly"), so every price of one item lands on one entry and the item list shows
 * one cell per family with its rarities in a dropdown.
 *
 * <p>Not items: shop descriptions ("Random Cell Door Upgrade", "xx Random G-Kit Beacon xx", crate banners) and upgraded
 * instances ("Iron Boots 17", "Gold Satchel 14", "Sludge Sword 9" - only the plain level 0 counts).</p>
 */
public final class ItemIdentity {
    /** Cosmic tiers, lowest first. */
    public static final List<String> RARITIES = io.theprisons.items.ItemTier.ALL;

    /** The G-Kits in the order of the /gkit menu (learned from it when it is opened, see {@link #setGkitOrder}). */
    public static final List<String> GKIT_DEFAULT = List.of("Astronaut", "Cowboy", "Enchanter", "Pluto", "Slasher", "Sludge", "Starforged");
    private static volatile List<String> gkitOrder = GKIT_DEFAULT;

    /** The forms of a satchel of one ore, in the order the list shows them. */
    public static final List<String> SATCHEL_FORMS = List.of("Refined", "Ore", "Deepslate Ore", "Block");
    public static final List<String> SATCHEL_ORES = List.of("Coal", "Iron", "Lapis", "Redstone", "Gold", "Diamond", "Emerald",
            "Prismarine", "Quartz", "Amethyst");

    /** "Gold" + "Deepslate Ore" → "Deepslate Gold Ore Satchel" (the names the server uses). */
    public static String satchelName(String ore, String form) {
        return switch (form) {
            case "Ore" -> ore + " Ore Satchel";
            case "Deepslate Ore" -> "Deepslate " + ore + " Ore Satchel";
            case "Block" -> "Block of " + ore + " Satchel";
            default -> ore + " Satchel";
        };
    }

    public static List<String> gkitOrder() {
        return gkitOrder;
    }

    /** The kit names in the order the /gkit menu shows them; a kit not in it comes after these. */
    public static void setGkitOrder(List<String> order) {
        if (!order.isEmpty()) {
            gkitOrder = List.copyOf(order);
        }
    }

    /** @param key lower-case identity, {@code family} without the rarity, {@code rarity} null = none, {@code name} the clean display name */
    public record Id(String key, String family, @Nullable String rarity, String name) {
        /** Position of the rarity (0 = none / lowest). */
        public int rank() {
            if (rarity == null) {
                return -1;
            }
            int tier = RARITIES.indexOf(rarity);
            if (tier >= 0) {
                return tier;
            }
            int form = SATCHEL_FORMS.indexOf(rarity);
            if (form >= 0) {
                return form;
            }
            int kit = gkitOrder.indexOf(rarity);
            return kit >= 0 ? kit : 1000;
        }
    }

    private static final Pattern PSEUDO = Pattern.compile("(?i)(\\brandom\\b|^xx\\s|\\sxx$|\\*\\*\\*)");
    private static final Pattern DECORATED = Pattern.compile("^(II|III|IV|[*+✦]+)\\s+(.+?)\\s+(?:II|III|IV|[*+✦]+)$");
    private static final Pattern BRACKETS = Pattern.compile("\\s*\\[[^\\]]*]");
    private static final Pattern NUMERIC_PARENS = Pattern.compile("\\s*\\(\\s*\\d[^)]*\\)");
    private static final Pattern COLON_NUMBER = Pattern.compile(":\\d+$");
    /** A vanilla-material tool / weapon with a level and / or a prestige numeral: an upgraded single piece, not a ware. */
    private static final Pattern TOOL_INSTANCE = Pattern.compile(
            "(?i)^(wooden|stone|iron|golden|gold|diamond|netherite)\\s+(pickaxe|axe|shovel|hoe|sword|spear)(\\s+\\d+)?(\\s+[IVXL]+)$"
                    + "|^(wooden|stone|iron|golden|gold|diamond|netherite)\\s+(pickaxe|axe|shovel|hoe|sword|spear)\\s+[1-9]\\d*(\\s+[IVXL]+)?$");
    /** "Astronaut G-Kit", "Cowboy G-Kit Level Up": one family "G-Kit" / "G-Kit Level Up", the kit is the variant. */
    private static final Pattern KIT = Pattern.compile("(?i)^([\\w'-]+)\\s+G-?Kit(\\s+Level Up)?$");
    /** "Gold Satchel", "Gold Ore Satchel", "Deepslate Gold Ore Satchel", "Block of Gold Satchel". */
    private static final Pattern SATCHEL = Pattern.compile(
            "(?i)^(Block of |Deepslate )?(Coal|Iron|Lapis|Redstone|Gold|Diamond|Emerald|Prismarine|Quartz|Amethyst)( Ore)? Satchel$");
    private static final Pattern TRAILING_NUMBER = Pattern.compile("\\s+(\\d+)$");

    private ItemIdentity() {
    }

    /** The identity of a market name; null = not an item to track (see the class comment). */
    public static @Nullable Id of(String rawName, @Nullable String customId) {
        String n = rawName.strip();
        if (n.isEmpty() || PSEUDO.matcher(n).find()) {
            return null;
        }
        String forcedRarity = null;
        Matcher d = DECORATED.matcher(n);
        if (d.matches()) {
            // "II Rare Candy II": the godly look of an item without a tier word
            n = d.group(2);
            forcedRarity = "Godly";
        }
        n = n.replaceAll("(?i)\\s*\\(right click\\)", "");
        // The same dust: "Pickaxe Dust", "Pickaxe Enchant Dust", "Dust"
        n = n.replaceAll("(?i)\\bpickaxe(?: enchant)? dust\\b", "Dust");
        n = BRACKETS.matcher(n).replaceAll("");
        n = NUMERIC_PARENS.matcher(n).replaceAll("");
        n = COLON_NUMBER.matcher(n).replaceAll("");
        n = n.replaceAll("\\s+", " ").strip();
        if (TOOL_INSTANCE.matcher(n).matches()) {
            return null;
        }
        Matcher t = TRAILING_NUMBER.matcher(n);
        if (t.find()) {
            if (Integer.parseInt(t.group(1)) != 0) {
                return null;
            }
            n = n.substring(0, t.start()).strip();
        }
        if (n.isEmpty()) {
            return null;
        }
        String rarity = forcedRarity;
        String family = n;
        Matcher satchel = SATCHEL.matcher(n);
        if (satchel.matches()) {
            String prefix = satchel.group(1) == null ? "" : satchel.group(1).strip().toLowerCase(Locale.ROOT);
            String ore = satchel.group(2).substring(0, 1).toUpperCase(Locale.ROOT) + satchel.group(2).substring(1).toLowerCase(Locale.ROOT);
            String form = prefix.startsWith("block") ? "Block" : prefix.equals("deepslate") ? "Deepslate Ore"
                    : satchel.group(3) != null ? "Ore" : "Refined";
            String fam = ore + " Satchel";
            return new Id((fam + "|" + form).toLowerCase(Locale.ROOT), fam, form, satchelName(ore, form));
        }
        Matcher kit = KIT.matcher(n);
        if (kit.matches() && !kit.group(1).equalsIgnoreCase("Mystery") && !kit.group(1).equalsIgnoreCase("Random")) {
            String kitName = kit.group(1);
            for (String known : gkitOrder) {
                if (known.equalsIgnoreCase(kitName)) {
                    kitName = known;
                }
            }
            String fam = kit.group(2) == null ? "G-Kit" : "G-Kit Level Up";
            return new Id((fam + "|" + kitName).toLowerCase(Locale.ROOT), fam, kitName, kitName + " " + fam);
        }
        if (n.regionMatches(true, 0, "Mystery ", 0, 8)) {
            // "Mystery Elite Clue Scroll": the rarity comes second, the family keeps "Mystery"
            String rest = n.substring(8);
            int sp = rest.indexOf(' ');
            if (sp > 0) {
                for (String r : RARITIES) {
                    if (r.equalsIgnoreCase(rest.substring(0, sp))) {
                        String fam = "Mystery " + rest.substring(sp + 1).strip();
                        return new Id((fam + "|" + r).toLowerCase(Locale.ROOT), fam, r, "Mystery " + r + " " + rest.substring(sp + 1).strip());
                    }
                }
            }
        }
        int space = n.indexOf(' ');
        if (space > 0) {
            String first = n.substring(0, space);
            for (String r : RARITIES) {
                if (r.equalsIgnoreCase(first)) {
                    rarity = r;
                    family = n.substring(space + 1).strip();
                    break;
                }
            }
        }
        String name = rarity == null ? family : rarity + " " + family;
        return new Id((family + "|" + (rarity == null ? "" : rarity)).toLowerCase(Locale.ROOT), family, rarity, name);
    }
}
