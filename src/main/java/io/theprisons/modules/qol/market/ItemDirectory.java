package io.theprisons.modules.qol.market;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Every Cosmic Prisons item the mod knows, not only the ones on the auction house right now (pure data): the item
 * families of the mod's own textures ({@code assets/theprisons/textures/item/prisons}), their rarities, satchels per
 * ore, masks, pets, trinkets, G-Kits, boosters, expanders, the plain NPC gear ... Their textures and frames come from the
 * name alone (the item look module reads the name), so a family needs only a vanilla item to carry it.
 *
 * <p>Not complete by itself - the wiki is not readable from the mod - so every item the menus show (auction house,
 * history, shops) is added to the list as well, see {@code MarketSearch}. A rarity list is only given where it is known:
 * Rare Candy exists as Godly only.</p>
 */
public final class ItemDirectory {
    /** The tiers every tiered family has, lowest first; Executive is the last rarity of all. */
    static final List<String> T6 = List.of("Simple", "Uncommon", "Elite", "Ultimate", "Legendary", "Godly", "Executive");
    static final List<String> T6_MYSTIC = List.of("Simple", "Uncommon", "Elite", "Ultimate", "Legendary", "Godly", "Mystic", "Executive");
    /** Contraband and cell door upgrades start at Uncommon (no Simple ones on the market). */
    static final List<String> FROM_UNCOMMON = List.of("Uncommon", "Elite", "Ultimate", "Legendary", "Godly", "Executive");
    /** Dust runs from Simple (1 %) to Mystic (18-25 %), then Executive. */
    static final List<String> DUST = List.of("Simple", "Uncommon", "Elite", "Ultimate", "Legendary", "Godly", "Mystic", "Executive");

    /** @param rarities empty = one plain item ({@code family}); {@code icon} = the vanilla item that carries it */
    public record Spec(String family, String icon, List<String> rarities) {
        /** The names of its variants, lowest rarity first. */
        public List<String> names() {
            if (rarities.isEmpty()) {
                return List.of(family);
            }
            List<String> out = new ArrayList<>();
            for (String r : rarities) {
                out.add(variantName(family, r));
            }
            return out;
        }
    }

    private static final List<Spec> ALL = new ArrayList<>();

    private ItemDirectory() {
    }

    /** "Shard" + "Godly" → "Godly Shard"; "Mystery Clue Scroll" + "Elite" → "Mystery Elite Clue Scroll". */
    static String variantName(String family, String rarity) {
        if (family.endsWith(" Satchel") && ItemIdentity.SATCHEL_FORMS.contains(rarity)) {
            return ItemIdentity.satchelName(family.substring(0, family.length() - 8), rarity);
        }
        if (family.startsWith("Mystery ")) {
            return "Mystery " + rarity + " " + family.substring(8);
        }
        return rarity + " " + family;
    }

    private static final java.util.Set<String> MASK_MODELS = java.util.Set.of("anonymous", "clue", "elite", "godly", "legendary",
            "leprechaun", "nitro", "outpost", "prisoner", "sentinel", "simple", "turkey", "ultimate", "uncommon", "valor");

    /**
     * The mod's own model for a mask ("Turkey Mask" → {@code prisons/mask/turkey}), null for anything else. The server
     * sends masks as player heads with a skin, so a mask the list invents needs a model of its own.
     */
    public static String maskModel(String name) {
        String n = name.toLowerCase(Locale.ROOT).strip();
        if (!n.endsWith(" mask")) {
            return null;
        }
        n = n.substring(0, n.length() - 5).replace("lucky ", "");
        return MASK_MODELS.contains(n) ? "prisons/mask/" + n : null;
    }

    public static List<Spec> all() {
        return ALL;
    }

    private static void tiered(String family, String icon, List<String> tiers) {
        ALL.add(new Spec(family, icon, tiers));
    }

    private static void one(String name, String icon) {
        ALL.add(new Spec(name, icon, List.of()));
    }

    static {
        // ── Rarity families ──────────────────────────────────────────────────
        tiered("Shard", "minecraft:prismarine_shard", T6);
        tiered("Dust", "minecraft:sugar", DUST);
        tiered("Secret Dust", "minecraft:glowstone_dust", T6);
        tiered("Contraband", "minecraft:ender_chest", FROM_UNCOMMON);
        one("Mystery Clue Scroll", "minecraft:map");
        tiered("Mystery Clue Scroll", "minecraft:map", List.of("Uncommon", "Elite", "Ultimate", "Legendary"));
        tiered("Clue Scroll", "minecraft:map", List.of("Godly"));
        tiered("Randomization Scroll", "minecraft:paper", T6_MYSTIC);
        tiered("Enchant Orb", "minecraft:ender_pearl", T6);
        tiered("Enchant Book", "minecraft:book", T6);
        tiered("Page", "minecraft:paper", T6);
        tiered("Bandit Box Key", "minecraft:tripwire_hook", T6);
        tiered("XP Bottle", "minecraft:experience_bottle", T6);
        tiered("Cell Door Upgrade", "minecraft:iron_door", FROM_UNCOMMON);
        // Rare Candy exists as Godly only.
        tiered("Rare Candy", "minecraft:gold_nugget", List.of("Godly"));

        // ── Single items ─────────────────────────────────────────────────────
        one("White Scroll", "minecraft:paper");
        one("Black Scroll", "minecraft:paper");
        tiered("Black Scroll", "minecraft:paper", List.of("Godly"));
        one("Holy White Scroll", "minecraft:paper");
        one("Prestige Protection Scroll", "minecraft:paper");
        one("Eraser", "minecraft:paper");
        one("Absorber", "minecraft:paper");
        one("XP Booster", "minecraft:paper");
        one("Energy Booster", "minecraft:paper");
        one("Shard Booster", "minecraft:paper");
        one("Charge Orb", "minecraft:magma_cream");
        one("Charge Orb Slot", "minecraft:magma_cream");
        one("Tool Prestige Token", "minecraft:nether_star");
        one("Pickaxe Prestige Token", "minecraft:nether_star");
        one("Mastery Pickaxe Prestige Token", "minecraft:nether_star");
        one("Pickaxe Prestige Modifier", "minecraft:nether_star");
        one("Skill Token", "minecraft:nether_star");
        one("Skill Tree Reset", "minecraft:nether_star");
        one("Wormhole Powerup (Double Tap)", "minecraft:ender_eye");
        one("Wormhole Powerup (Overdrive)", "minecraft:ender_eye");
        one("Wormhole Powerup (BOGO)", "minecraft:ender_eye");
        for (String t : new String[]{"Blink", "Healing", "Absorption", "Resistance"}) {
            one(t + " Trinket", "minecraft:rabbit_foot");
        }
        // The G-Kits are one family; the kits are its variants in the order of the /gkit menu.
        tiered("G-Kit", "minecraft:chest", ItemIdentity.GKIT_DEFAULT);
        tiered("G-Kit Level Up", "minecraft:nether_star", ItemIdentity.GKIT_DEFAULT);
        one("G-Kit Flare", "minecraft:firework_rocket");
        one("Fractured G-Kit Flare", "minecraft:firework_rocket");
        one("Meteor Flare", "minecraft:firework_rocket");
        one("G-Kit Beacon", "minecraft:beacon");
        for (String m : new String[]{"Anonymous", "Clue", "Lucky Leprechaun", "Nitro", "Outpost", "Prisoner", "Sentinel", "Turkey", "Valor"}) {
            one(m + " Mask", "minecraft:player_head");
        }
        for (String p : new String[]{"Anti XP Tax", "Lucky", "Shockwave", "Cleanse", "Signal Jammer", "Blacksmith", "Bandit King"}) {
            one(p + " Pet", "minecraft:player_head");
        }
        one("Pet Leash", "minecraft:lead");
        one("Pet Incubator", "minecraft:turtle_egg");
        one("Boss Egg", "minecraft:egg");
        one("Item Nametag", "minecraft:name_tag");
        one("Item Lore Crystal", "minecraft:amethyst_shard");
        one("Kill Message", "minecraft:paper");
        one("Title", "minecraft:paper");
        one("Altar Pillar", "minecraft:quartz_pillar");
        one("Lucky Charm", "minecraft:rabbit_foot");
        one("Rabbit's Foot", "minecraft:rabbit_foot");
        one("+1 Home", "minecraft:paper");
        one("+1 PV Row", "minecraft:paper");
        one("+1 Showcase Row", "minecraft:paper");
        one("Time Extender", "minecraft:clock");
        one("Item Flip Credit", "minecraft:paper");
        one("Gen Breaker", "minecraft:iron_pickaxe");
        one("Aether Bloom", "minecraft:pink_petals");
        one("Red Roses", "minecraft:poppy");
        one("Money Note", "minecraft:paper");
        one("Cosmic Energy", "minecraft:light_blue_dye");
        one("Cosmic Coins", "minecraft:gold_nugget");
        one("Gang Points", "minecraft:paper");
        one("Cosmo-Slot Bot Ticket Sleeve", "minecraft:paper");
        one("Ticket Scrap", "minecraft:paper");
        one("Slot Bot Ticket", "minecraft:paper");
        one("Inmate Rations", "minecraft:potion");
        one("Cosmic Crate", "minecraft:chest");
        one("Rare Pickaxe Enchant", "minecraft:enchanted_book");

        // ── Satchels: per ore ────────────────────────────────────────────────
        String[] ores = {"Coal", "Iron", "Lapis", "Redstone", "Gold", "Diamond", "Emerald", "Prismarine", "Quartz", "Amethyst"};
        for (String ore : ores) {
            // one family per ore: Gold Satchel, Gold Ore Satchel, Deepslate Gold Ore Satchel, Block of Gold Satchel
            tiered(ore + " Satchel", "minecraft:chest", ItemIdentity.SATCHEL_FORMS);
        }

    }
}
