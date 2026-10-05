package com.freelocs.theprisons.modules.mining.ore;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Item sorter of the Ore Macro (pure parts: when to start, which slots go into which private vault; the module runs
 * the steps).
 *
 * <p>When {@value #SHARD_PERCENT} % of the 36 inventory slots hold prismarine shard stacks, or
 * {@value #LOOSE_PERCENT} % hold items that are not blocks (every block counts as a block: ores, deepslate ores, ore
 * blocks, ...; the items the macro keeps do not count): {@code /sethome tmp} →
 * {@code /spawn} → wait for "Teleporting you to spawn in 1 seconds... (DO NOT MOVE)" + 1.5 s → {@code /pv <shards>}, shift-click every prismarine shard in, Esc → {@code /pv <other>}, shift-click
 * everything else in except satchels, pickaxes, sponges (absorber), light blue dye, player heads (pets) and the
 * auto-use ability items, Esc → {@code /home tmp}, wait for "Teleporting you to ... home in 1 seconds... (DO NOT MOVE)"
 * + 1.5 s → {@code /delhome tmp} → go on. A vault whose last slot is taken stops the macro with an alert.</p>
 */
public final class ItemSorter {
    public static final String SHARD = "minecraft:prismarine_shard";
    public static final String SPONGE = "minecraft:sponge";
    public static final String LIGHT_BLUE_DYE = "minecraft:light_blue_dye";
    public static final String PLAYER_HEAD = "minecraft:player_head";
    /** Contraband: a loot chest, opened by placing it on the ground at spawn. */
    public static final String CONTRABAND = "minecraft:ender_chest";
    /** Money notes: never swapped out of the hotbar. */
    public static final String MONEY = "minecraft:paper";
    /** The shard menu's "Roll all shards" button. */
    public static final String ROLL_ALL = "minecraft:diamond_horse_armor";
    /** Shard tiers, lowest first (Cosmic Prisons: Simple, Uncommon, Elite, Ultimate, Legendary, Godly). */
    static final List<String> SHARD_TIERS = List.of("simple", "uncommon", "unique", "elite", "ultimate", "legendary", "mystic",
            "godly", "heroic");
    private static final Pattern ENERGY_NUMBER = Pattern.compile("(\\d[\\d,]*(?:\\.\\d+)?)\\s*([kmb])?\\b", Pattern.CASE_INSENSITIVE);
    public static final int SHARD_PERCENT = 35;
    public static final int INVENTORY_SLOTS = 36;
    /** Stacks of prismarine shards that start the sorter: 35 % of 36 slots, rounded up = 13. */
    public static final int SHARD_STACKS = (INVENTORY_SLOTS * SHARD_PERCENT + 99) / 100;
    public static final int LOOSE_PERCENT = 50;
    /** Slots with items that are no blocks that start the sorter: 50 % of 36 = 18. */
    public static final int LOOSE_STACKS = (INVENTORY_SLOTS * LOOSE_PERCENT + 99) / 100;
    public static final String HOME = "tmp";
    /**
     * Exactly the last countdown line: "Teleporting you to spawn in 1 seconds... (DO NOT MOVE)" / "... tmp home in 1
     * seconds...". Earlier lines ("in 11 seconds", "in 5 seconds") do not count - only a single 1.
     */
    static final Pattern TELEPORTING = Pattern.compile("teleporting you to .*\\bin\\s+1\\s+seconds?\\b");

    private ItemSorter() {
    }

    /** One item of the inventory: its id, formatting-free name, whether it is a pickaxe and whether it is a block. */
    /** {@code plain}: no custom name and no lore - a mined drop, not a Cosmic special item with the same id. */
    public record Item(String id, String name, boolean pickaxe, boolean block, boolean plain) {
        public static final Item EMPTY = new Item("minecraft:air", "", false, false, true);

        public Item(String id, String name, boolean pickaxe, boolean block) {
            this(id, name, pickaxe, block, true);
        }

        public Item(String id, String name, boolean pickaxe) {
            this(id, name, pickaxe, false, true);
        }

        boolean empty() {
            return "minecraft:air".equals(id);
        }
    }

    /** Prismarine shard stacks in the 36 inventory slots. */
    public static int shardStacks(Item[] inventory) {
        int stacks = 0;
        for (Item item : inventory) {
            if (item != null && SHARD.equals(item.id())) {
                stacks++;
            }
        }
        return stacks;
    }

    /** Slots holding items that are no blocks (ores and all other blocks do not count) and that the macro does not keep. */
    public static int looseStacks(Item[] inventory, List<String> abilityParts) {
        int stacks = 0;
        for (Item item : inventory) {
            if (item != null && !item.empty() && !item.block() && !keep(item, abilityParts)) {
                stacks++;
            }
        }
        return stacks;
    }

    public static boolean due(Item[] inventory, List<String> abilityParts) {
        return due(inventory, abilityParts, 100);
    }

    /**
     * As {@link #due(Item[], List)}, and also when {@code limitPercent} % of the slots hold items the sorter puts away.
     * Blocks (ores) do not count: they are sold ({@code /sellall}), not carried to a vault.
     */
    public static boolean due(Item[] inventory, List<String> abilityParts, int limitPercent) {
        int used = 0;
        for (Item item : inventory) {
            if (item != null && !item.empty() && !item.block() && !keep(item, abilityParts)) {
                used++;
            }
        }
        return shardStacks(inventory) >= SHARD_STACKS || looseStacks(inventory, abilityParts) >= LOOSE_STACKS
                || used * 100 >= limitPercent * INVENTORY_SLOTS;
    }

    /** The (first) private vault number from a setting ("7", " 8 ", "8, 10"); -1 when empty or not a number (sorter off). */
    public static int vault(String text) {
        List<Integer> all = vaults(text);
        return all.isEmpty() ? -1 : all.get(0);
    }

    /** All private vault numbers of a setting, in order ("8, 10 11" → 8, 10, 11); empty when none. */
    public static List<Integer> vaults(String text) {
        List<Integer> out = new java.util.ArrayList<>();
        for (String part : text.split("[,;\\s]+")) {
            try {
                int n = Integer.parseInt(part.strip());
                if (n > 0 && !out.contains(n)) {
                    out.add(n);
                }
            } catch (NumberFormatException ignored) {
                // not a number: skipped
            }
        }
        return out;
    }

    /** Goes into the shard vault. */
    public static boolean shard(Item item) {
        return SHARD.equals(item.id());
    }

    /** Goes into the other vault: everything except shards, energy, contrabands and what the macro needs. */
    public static boolean other(Item item, List<String> abilityParts) {
        return other(item, abilityParts, true);
    }

    /** As {@link #other(Item, List)}; money (paper) is not put away either - it is redeemed (right click). */
    public static boolean other(Item item, List<String> abilityParts, boolean pets) {
        return !item.empty() && !shard(item) && !energy(item) && !contraband(item) && !money(item) && !sellable(item)
                && !keep(item, abilityParts, pets);
    }

    /**
     * A satchel that stays for the chosen ore packs: a normal ore ("gold") keeps its Ore and Ingot satchels, a deepslate
     * one ("deepslate_gold") its Deepslate Ore and Ore Block satchels; several packs keep all of theirs.
     */
    public static boolean keptSatchel(Item item, java.util.Collection<String> packs) {
        String name = item.name().toLowerCase(Locale.ROOT);
        if (!name.contains("satchel")) {
            return false;
        }
        for (String pack : packs) {
            boolean deep = pack.startsWith("deepslate_");
            String ore = deep ? pack.substring("deepslate_".length()) : pack;
            if (!name.contains(ore)) {
                continue;
            }
            if (deep ? name.contains("deepslate") && name.contains("ore") || name.contains("block")
                    : !name.contains("deepslate") && name.contains("ore") || name.contains("ingot")) {
                return true;
            }
        }
        return false;
    }

    /** A satchel the chosen packs do not use: into the vault. */
    public static boolean otherSatchel(Item item, java.util.Collection<String> packs) {
        return item.name().toLowerCase(Locale.ROOT).contains("satchel") && !keptSatchel(item, packs);
    }

    private static final java.util.Set<String> ORE_DROPS = java.util.Set.of("minecraft:coal", "minecraft:diamond",
            "minecraft:emerald", "minecraft:lapis_lazuli", "minecraft:redstone", "minecraft:quartz");

    /**
     * Sold with /sellall, never put into a vault: plain (no custom name / lore) ores, deepslate ores, ingots, raw ores,
     * ore blocks and the ores' drops. A Cosmic item with the same id but a name or lore is something else (kept / vaulted).
     */
    public static boolean sellable(Item item) {
        if (item.empty() || !item.plain() || item.name().toLowerCase(java.util.Locale.ROOT).contains("satchel")) {
            // A satchel is a gold_ore item too (game log 2026-10-03): never sold, even without lore.
            return false;
        }
        String id = item.id();
        return id.endsWith("_ore") || id.endsWith("_ingot") || id.startsWith("minecraft:raw_") || ORE_DROPS.contains(id)
                || id.matches("minecraft:(coal|iron|gold|diamond|emerald|lapis|redstone|copper|raw_[a-z]+)_block");
    }

    /** Money note (paper with "$" in its name, "$136,035.39"): redeemed with a right click. Scrolls are paper too. */
    public static boolean money(Item item) {
        return MONEY.equals(item.id()) && item.name().contains("$");
    }

    /** The rarest shard tier (Godly): a trip to spawn right away. */
    public static boolean godlyShard(Item item) {
        return shard(item) && item.name().toLowerCase(Locale.ROOT).contains("godly");
    }

    /** Cosmic energy (light blue dye): goes into the energy vault. */
    public static boolean energy(Item item) {
        return LIGHT_BLUE_DYE.equals(item.id());
    }

    /** A contraband (ender chest): opened at spawn, never put into a vault. */
    public static boolean contraband(Item item) {
        return CONTRABAND.equals(item.id());
    }

    /**
     * May be swapped out of the hotbar to make room for a contraband or a shard: everything except a pickaxe, shards,
     * contrabands and money (paper). An empty slot is fine too.
     */
    public static boolean swappable(Item item) {
        return item.empty() || !item.pickaxe() && !shard(item) && !contraband(item) && !money(item);
    }

    /** The tier of a shard by its name (0 = Simple, higher = rarer); -1 = not known. */
    public static int shardRank(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        for (int i = SHARD_TIERS.size() - 1; i >= 0; i--) {
            if (lower.contains(SHARD_TIERS.get(i))) {
                return i;
            }
        }
        return -1;
    }

    /** The energy in an energy item: the largest number on a line with "energy" ("8,000,000", "8.5M"); 0 = none. */
    public static long energyAmount(List<String> lines) {
        return amount(lines, "energy");
    }

    /** The money on a note (paper): the largest number on a line with "$" ("$5,000,000", "$2.5M"); 0 = none. */
    public static long moneyAmount(List<String> lines) {
        return amount(lines, "$");
    }

    private static long amount(List<String> lines, String marker) {
        long best = 0L;
        for (String line : lines) {
            if (!line.toLowerCase(Locale.ROOT).contains(marker)) {
                continue;
            }
            java.util.regex.Matcher m = ENERGY_NUMBER.matcher(line);
            while (m.find()) {
                double v;
                try {
                    v = Double.parseDouble(m.group(1).replace(",", ""));
                } catch (NumberFormatException e) {
                    continue;
                }
                String unit = m.group(2) == null ? "" : m.group(2).toLowerCase(Locale.ROOT);
                v *= switch (unit) {
                    case "k" -> 1_000D;
                    case "m" -> 1_000_000D;
                    case "b" -> 1_000_000_000D;
                    default -> 1D;
                };
                best = Math.max(best, Math.round(v));
            }
        }
        return best;
    }

    /** Stays in the inventory: satchels, the pickaxe, sponges, pets (player heads), ability items. */
    public static boolean keep(Item item, List<String> abilityParts) {
        return keep(item, abilityParts, true);
    }

    /**
     * Stays in the inventory: satchels, the pickaxe and sponges - pets (player heads) only with {@code pets}, ability
     * items only those named in {@code abilityParts} (empty when the macro does not use them).
     */
    public static boolean keep(Item item, List<String> abilityParts, boolean pets) {
        String name = item.name().toLowerCase(Locale.ROOT);
        if (name.contains("satchel") || item.pickaxe()) {
            return true;
        }
        if (SPONGE.equals(item.id()) || pets && PLAYER_HEAD.equals(item.id())) {
            return true;
        }
        for (String part : abilityParts) {
            if (!part.isEmpty() && name.contains(part)) {
                return true;
            }
        }
        return false;
    }

    /** A system message saying the teleport home starts. */
    public static boolean teleporting(String message) {
        return TELEPORTING.matcher(message.toLowerCase(Locale.ROOT)).find();
    }
}
