package com.freelocs.theprisons.modules.mining.ore;

import java.util.Locale;

/**
 * Reactions of the Ore Macro to server messages (pure parts: message matching and slot choice; the module runs the
 * steps).
 *
 * <ul>
 *     <li><b>Pickaxe energy full</b> ("(!) Your pickaxe is full of energy!" or the older "[!] Your pickaxe energy
 *     is full! Please /extract or level it up to continue mining."): open the inventory, pick up the first sponge, left-click it onto the first pickaxe of the hotbar
 *     (from the left), put the sponge back where it came from, close the inventory.</li>
 *     <li><b>Ore satchel full</b> ("[!] Your Ore Satchel is now full!") and <b>inventory full</b> ("[!] Your inventory
 *     is full!"): {@code /sellall} right away, without stopping the macro (at most once per second).</li>
 *     <li><b>Pet ready</b> (the pet in the hotbar is off cooldown, e.g. "Anti XP Tax Pet"): select it, right click
 *     once, back to the item held before.</li>
 *     <li><b>Item ability ready</b> (e.g. a fireball in the hotbar is off cooldown): stop, select it, hold right click
 *     for 2.2 s without mining, back to the item held before, walk on.</li>
 * </ul>
 */
public final class Chores {
    public enum Kind { NONE, EXTRACT_ENERGY, SELL_ALL_NOW, USE_PET, USE_ABILITY, SORT_ITEMS, REDEEM_MONEY, DEATH_RECOVERY }

    /** "(!) Your pickaxe is full of energy!" and the older "[!] Your pickaxe energy is full!". */
    static final java.util.regex.Pattern ENERGY_FULL = java.util.regex.Pattern.compile(
            "your pickaxe (?:energy is full|is full of energy)");
    /** "[!] Your Ore Satchel is now full!" and "(!) Your Lapis Ore Satchel is full!". */
    static final java.util.regex.Pattern SATCHEL_FULL = java.util.regex.Pattern.compile("your (?:[a-z ]+ )?ore satchel is (?:now )?full");
    /** "(!) Anti XP Tax Pet is on cooldown for 35m 4s" (the server's answer when an item was used too early). */
    static final java.util.regex.Pattern ON_COOLDOWN = java.util.regex.Pattern.compile(
            "^[\\[(]![\\])]\\s*(.+?) is on cooldown for((?:\\s*\\d+\\s*[hms])+)");
    private static final java.util.regex.Pattern DURATION_PART = java.util.regex.Pattern.compile("(\\d+)\\s*([hms])");
    static final String INVENTORY_FULL = "your inventory is full";
    public static final String SPONGE = "minecraft:sponge";

    private Chores() {
    }

    private static final java.util.regex.Pattern FILL = java.util.regex.Pattern.compile("(\\d[\\d,]*)\\s*/\\s*(\\d[\\d,]*)");

    /**
     * The pickaxe is full of energy (it mines nothing then): under its lore's "Cosmic Energy" heading the line
     * "(351,762 / 351,762)" - energy now at least the maximum.
     */
    public static boolean pickaxeFull(java.util.List<String> lore) {
        for (int i = 0; i < lore.size(); i++) {
            if (!lore.get(i).strip().equalsIgnoreCase("cosmic energy")) {
                continue;
            }
            for (int j = i + 1; j <= Math.min(lore.size() - 1, i + 3); j++) {
                java.util.regex.Matcher m = FILL.matcher(lore.get(j));
                if (m.find()) {
                    long now = Long.parseLong(m.group(1).replace(",", ""));
                    long max = Long.parseLong(m.group(2).replace(",", ""));
                    return max > 0L && now >= max;
                }
            }
        }
        return false;
    }

    /** Which chore a (formatting-free) system message asks for. */
    public static Kind forMessage(String message) {
        String text = message.toLowerCase(Locale.ROOT);
        if (ENERGY_FULL.matcher(text).find()) {
            return Kind.EXTRACT_ENERGY;
        }
        if (SATCHEL_FULL.matcher(text).find() || text.contains(INVENTORY_FULL)) {
            return Kind.SELL_ALL_NOW;
        }
        return Kind.NONE;
    }

    /**
     * The item and its remaining cooldown from "(!) &lt;item&gt; is on cooldown for 35m 4s"; {@code null} for other
     * messages.
     *
     * @return {lower case item name, cooldown in ms}
     */
    public static Object @org.jspecify.annotations.Nullable [] cooldown(String message) {
        java.util.regex.Matcher matcher = ON_COOLDOWN.matcher(message.strip().toLowerCase(Locale.ROOT));
        if (!matcher.find()) {
            return null;
        }
        long ms = 0L;
        java.util.regex.Matcher part = DURATION_PART.matcher(matcher.group(2));
        while (part.find()) {
            long n = Long.parseLong(part.group(1));
            ms += switch (part.group(2)) {
                case "h" -> n * 3_600_000L;
                case "m" -> n * 60_000L;
                default -> n * 1_000L;
            };
        }
        return new Object[]{matcher.group(1).strip(), ms};
    }

    /** Comma separated name parts ("fireball, meteor") → lower case parts without blanks. */
    public static java.util.List<String> nameParts(String csv) {
        java.util.List<String> parts = new java.util.ArrayList<>();
        for (String part : csv.split(",")) {
            String cleaned = part.strip().toLowerCase(Locale.ROOT);
            if (!cleaned.isEmpty()) {
                parts.add(cleaned);
            }
        }
        return parts;
    }

    /**
     * The first hotbar slot (0-8, left to right) whose item name contains one of the parts and is ready (not on
     * cooldown and not held back); -1 when none.
     *
     * @param names formatting-free item names per hotbar slot ("" = empty)
     * @param ready whether the item in a slot may be used now
     */
    public static int readySlot(String[] names, boolean[] ready, java.util.List<String> parts) {
        for (int slot = 0; slot < Math.min(9, names.length); slot++) {
            String name = names[slot].toLowerCase(Locale.ROOT);
            if (name.isEmpty() || !ready[slot]) {
                continue;
            }
            for (String part : parts) {
                if (name.contains(part)) {
                    return slot;
                }
            }
        }
        return -1;
    }

    /**
     * Slots of the player's inventory screen (9-35 main inventory, 36-44 hotbar left to right).
     *
     * @param itemIds  item id per screen slot (index = slot number, empty = "minecraft:air")
     * @param pickaxes whether the item in a slot is a pickaxe
     * @return {sponge slot, pickaxe slot}; -1 where none was found. The sponge is the first one in the inventory as it
     * is seen (main inventory from the top left, then the hotbar); the pickaxe the first one of the hotbar from the left.
     */
    public static int[] slots(String[] itemIds, boolean[] pickaxes) {
        int sponge = -1;
        for (int slot = 9; slot <= 44 && slot < itemIds.length; slot++) {
            if (SPONGE.equals(itemIds[slot])) {
                sponge = slot;
                break;
            }
        }
        int pickaxe = -1;
        for (int slot = 36; slot <= 44 && slot < pickaxes.length; slot++) {
            if (pickaxes[slot]) {
                pickaxe = slot;
                break;
            }
        }
        return new int[]{sponge, pickaxe};
    }

    /** The head sprite in front of a name in Cosmic's chat ("[Payney head]Payney"). */
    private static final java.util.regex.Pattern HEAD = java.util.regex.Pattern.compile("\\[[^\\]]* head]");

    /**
     * Killed: Cosmic respawns at once at spawn - no death screen, only the chat says it (game 2026-10-05):
     * "× [Payney head]Payney threw M4cL4ren off the runaway train using Iron Sword 30.", "WHITE SCROLL PROTECTED",
     * "You kept your pickaxe but lost 642,185 energy". A kill line counts only with {@code me} as the victim (the
     * first name after "×" is the killer).
     */
    public static boolean died(String message, String me) {
        String text = HEAD.matcher(message).replaceAll("").strip();
        String lower = text.toLowerCase(java.util.Locale.ROOT);
        if (lower.startsWith("you kept your pickaxe but lost") || lower.equals("white scroll protected")) {
            return true;
        }
        if (!text.startsWith("×") || me.isEmpty()) {
            return false;
        }
        String[] words = text.substring(1).strip().split("\\s+");
        for (int i = 1; i < words.length; i++) {
            if (words[i].replaceAll("[^A-Za-z0-9_]", "").equals(me)) {
                return true;
            }
        }
        return false;
    }

    /** "(!) You have entered combat. Do not log out for 10s!" - no /spawn, /warp for 10 s. */
    public static boolean combat(String message) {
        return message.toLowerCase(java.util.Locale.ROOT).contains("you have entered combat");
    }
}
