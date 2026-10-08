package com.freelocs.theprisons.modules.qol.items;

import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.NbtComponent;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import com.freelocs.theprisons.core.client.TextStrip;
import net.minecraft.component.type.LoreComponent;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import org.jspecify.annotations.Nullable;

import java.util.IdentityHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Recognises Cosmic Prisons items from the data the server puts on them ({@code PublicBukkitValues} with
 * {@code cosmicprisons:*} keys, the same data the Cosmic Textures mod reads): which family, which tier, and a short
 * badge (charge orb percent, prestige level, enchant level). Results are cached per data component, so a slot that
 * is drawn every frame costs one map lookup.
 */
public final class PrisonsItems {
    public enum Tier {
        SIMPLE, UNCOMMON, ELITE, ULTIMATE, LEGENDARY, GODLY;

        public final String id = name().toLowerCase(Locale.ROOT);

        static @Nullable Tier parse(@Nullable String value) {
            if (value == null || value.isEmpty()) {
                return null;
            }
            String upper = value.toUpperCase(Locale.ROOT);
            for (Tier tier : values()) {
                if (upper.equals(tier.name())) {
                    return tier;
                }
            }
            for (Tier tier : values()) {
                if (upper.contains(tier.name())) {
                    return tier;
                }
            }
            return null;
        }

        static @Nullable Tier ofIndex(int index) {
            return index >= 0 && index < values().length ? values()[index] : null;
        }
    }

    /**
     * What is known about an item: our model (or null), its tier (or null), a badge text (or "") and the model the
     * plain base item (same Minecraft id, no components) gets once this family is learned for it (or null).
     */
    public record Info(@Nullable Identifier model, @Nullable Tier tier, String badge, @Nullable Identifier plainModel) {
        static final Info NONE = new Info(null, null, "", null);
    }

    private static final String NS = "cosmicprisons:";
    private static final int CACHE_LIMIT = 4096;
    /** Keyed by the identity of the stack's custom data (or name / lore when it has none). */
    private static final Map<Object, Info> CACHE = new IdentityHashMap<>();
    private static final java.util.regex.Pattern MASK = java.util.regex.Pattern.compile("(?i)\\bmask\\b");
    /** "Anti XP Tax Pet [LVL 1]", "Lucky Pet" - not "Pet Leash". */
    private static final java.util.regex.Pattern PET = java.util.regex.Pattern.compile("(?i)\\bpet\\b\\s*(?:\\[|$)");
    private static final Map<LoreComponent, java.util.Optional<Identifier>> WORN = new IdentityHashMap<>();
    /** "Tool Enchant Orb", "whistle orbs", "pickaxe_enchant_orb" (not charge / energy orbs, checked separately). */
    private static final java.util.regex.Pattern ORB = java.util.regex.Pattern.compile("(?i)(?:\\b|_)orbs?\\b");
    private static final java.util.regex.Pattern ENCHANT_ORB = java.util.regex.Pattern.compile("(?i)\\benchant(?:ment)?\\s*orb\\b");
    private static final java.util.regex.Pattern REROLL = java.util.regex.Pattern.compile("(?i)\\bre-?\\s?roll\\b");
    /** Generic animals for unknown pets, same order as tools/textures/pets.py GENERIC (names are hashed into it). */
    static final String[] GENERIC_PETS = {"slime", "fox", "rabbit", "owl", "dragon", "pig"};

    private PrisonsItems() {
    }

    public static Info info(ItemStack stack) {
        if (stack.isEmpty()) {
            return Info.NONE;
        }
        NbtComponent data = stack.get(DataComponentTypes.CUSTOM_DATA);
        boolean hasData = data != null && !data.isEmpty();
        Text name = stack.get(DataComponentTypes.CUSTOM_NAME);
        if (name == null) {
            name = stack.get(DataComponentTypes.ITEM_NAME);
        }
        LoreComponent lore = stack.get(DataComponentTypes.LORE);
        if (!hasData && name == null && lore == null) {
            return Info.NONE;
        }
        Object key = hasData ? data : name != null ? name : lore;
        Info cached = CACHE.get(key);
        if (cached != null) {
            return cached;
        }
        Info info;
        try {
            // Rarity from the name colour / lore only for Cosmic items and gear (not for menu buttons).
            boolean gear = hasData || stack.contains(DataComponentTypes.EQUIPPABLE) || stack.contains(DataComponentTypes.WEAPON)
                    || stack.contains(DataComponentTypes.TOOL);
            Tier hint = gear ? hintTier(name, lore) : null;
            NbtCompound values = hasData ? data.copyNbt().getCompoundOrEmpty("PublicBukkitValues") : new NbtCompound();
            String shown = name != null ? name.getString() : null;
            // A revealed orb is often named after its enchant only ("Whistle IV"): its lore calls it an enchant orb.
            if (lore != null && !stack.contains(DataComponentTypes.TOOL) && orbFamily(shown != null ? shown : "", "") == null
                    && lore.lines().stream().anyMatch(l -> ENCHANT_ORB.matcher(l.getString()).find())) {
                shown = (shown != null ? shown : "") + " (Enchant Orb)";
            }
            info = resolve(values, shown, hint);
        } catch (RuntimeException e) {
            info = Info.NONE;
        }
        if (CACHE.size() >= CACHE_LIMIT) {
            CACHE.clear();
        }
        CACHE.put(key, info);
        logMaskItem(stack, name, lore);
        if (info.plainModel() != null && hasData) {
            VanillaBases.observe(stack.getItem(), info.plainModel());
        }
        return info;
    }

    /**
     * How a pet is drawn - after what it does: Anti XP Tax (XP orb with a tax shield), Lucky (lucky cat), Wormhole
     * Powerup (portal), Shockwave, Cleanse, Signal Jammer (robot), Blacksmith (mole smith), Bandit King (raccoon).
     * Unknown pets get a generic animal derived from their name: one look per pet, different pets differ.
     */
    static String petCreature(String text) {
        String t = text.toLowerCase(Locale.ROOT);
        String[][] rules = {
                {"anti_xp_tax", "anti xp tax", "xp tax", "tax"},
                {"lucky", "lucky", "luck", "fortune"},
                {"wormhole", "wormhole", "powerup", "portal"},
                {"shockwave", "shockwave", "shock"},
                {"cleanse", "cleanse", "cleansing"},
                {"signal_jammer", "signal jammer", "jammer", "signal"},
                {"blacksmith", "blacksmith", "smith", "repair"},
                {"bandit_king", "bandit king", "bandit"},
        };
        for (String[] rule : rules) {
            for (int i = 1; i < rule.length; i++) {
                if (java.util.regex.Pattern.compile("\\b" + rule[i] + "\\b").matcher(t).find()) {
                    return rule[0];
                }
            }
        }
        String key = t.replaceAll("\\[.*?]", "").replaceAll("\\bpet\\b", "").replaceAll("[^a-z]", "");
        return GENERIC_PETS[Math.floorMod(key.hashCode(), GENERIC_PETS.length)];
    }

    private static final java.util.regex.Pattern PERCENT = java.util.regex.Pattern.compile("(\\d{1,3})\\s*%");
    private static final java.util.regex.Pattern ROMAN = java.util.regex.Pattern.compile("\\b(I{1,3}|IV|V|VI{0,3}|IX|X)\\b");

    /**
     * The model of an item from its name alone (Cosmic sends many items without their data): every family the mod
     * has textures for. {@code null} when the name says nothing.
     */
    static @Nullable String byName(String n, @Nullable Tier tier) {
        if (PET.matcher(n).find() || MASK.matcher(n).find()) {
            return null; // pets and masks have their own looks
        }
        Tier t = tier != null ? tier : tierWord(n);
        String tid = (t != null ? t : Tier.SIMPLE).id;
        if (n.contains("satchel")) {
            return satchel(n);
        }
        // menu buttons in server GUIs
        if (n.equals("next page") || n.startsWith("next page") || n.equals("next") || n.startsWith("next ->")) {
            return "menu/next";
        }
        if (n.equals("previous page") || n.startsWith("previous page") || n.equals("previous") || n.startsWith("prev")) {
            return "menu/previous";
        }
        if (n.equals("back") || n.startsWith("go back") || n.startsWith("back to")) {
            return "menu/back";
        }
        if (n.startsWith("refresh") || n.contains("refresh")) {
            return "menu/refresh";
        }
        if (n.equals("close") || n.startsWith("close menu") || n.equals("exit")) {
            return "menu/close";
        }
        if (n.contains("charge orb slot")) {
            return "misc/charge_orb_slot";
        }
        if (n.contains("charge orb")) {
            java.util.regex.Matcher m = PERCENT.matcher(n);
            int percent = m.find() ? Integer.parseInt(m.group(1)) : 1;
            return "charge_orb/stage_" + Math.max(1, Math.min(4, (percent - 1) / 5 + 1));
        }
        if (n.contains("ticket sleeve")) {
            return "misc/ticket_sleeve";
        }
        if (n.contains("ticket scrap")) {
            return "misc/ticket_scrap";
        }
        if (n.contains("slot bot ticket")) {
            return "misc/slot_bot_ticket";
        }
        if (n.contains("inmate ration") || n.contains("inmate potion")) {
            return "misc/inmate_rations";
        }
        if (n.contains("showcase")) {
            return "misc/showcase_row";
        }
        if (n.contains("expander")) {
            return n.contains("home") ? "misc/home_expander" : n.contains("pv") || n.contains("vault") ? "misc/pv_expander"
                    : n.contains("time") ? "misc/time_extender" : "misc/expander";
        }
        if (n.contains("time extender")) {
            return "misc/time_extender";
        }
        if (n.contains("prestige modifier")) {
            return n.contains("random") ? "misc/prestige_modifier_random" : "misc/prestige_modifier";
        }
        if (n.contains("prestige token")) {
            return "prestige_token/level_" + Math.max(1, Math.min(10, romanLevel(n)));
        }
        if (n.contains("executive") && n.contains("shard")) {
            return "misc/executive_shard";
        }
        if (n.contains("powerup") || n.contains("power-up")) {
            return n.contains("overdrive") ? "powerup/overdrive" : n.contains("bogo") ? "powerup/bogo"
                    : n.contains("double tap") ? "powerup/double_tap" : n.contains("random") ? "powerup/random" : "powerup/generic";
        }
        if (n.contains("rare candy") || n.contains("candy")) {
            Tier ct = t != null ? t : Tier.ofIndex(Math.max(0, Math.min(5, romanLevel(n) - 1)));
            return "candy/" + (ct != null ? ct : Tier.SIMPLE).id;
        }
        if (n.contains("skill token")) {
            return "misc/skill_token";
        }
        if (n.contains("flare")) {
            return n.contains("meteor") ? "flare/meteor" : n.contains("fractured") ? "flare/fractured"
                    : n.contains("g-kit") || n.contains("gkit") ? "flare/gkit" : "flare/basic";
        }
        if (n.contains("g-kit") || n.contains("gkit")) {
            return n.contains("sludge") ? "gkit/sludge" : n.contains("astronaut") ? "gkit/astronaut"
                    : n.contains("starforged") ? "gkit/starforged" : n.contains("slasher") ? "gkit/slasher" : "gkit/generic";
        }
        if (n.contains("upgrade")) {
            return "upgrade/" + tid;
        }
        if (n.contains("trinket")) {
            return n.startsWith("random") ? "trinket/random" : n.contains("blink") ? "trinket/blink"
                    : n.contains("heal") ? "trinket/healing" : n.contains("absorption") ? "trinket/absorption"
                    : n.contains("resistance") ? "trinket/resistance" : "trinket/generic";
        }
        // the families that usually come with data, by name
        if (n.contains("secret dust")) {
            return "secret_dust/" + tid;
        }
        if (n.contains("dust")) {
            return "dust/" + tid;
        }
        if (n.contains("shard booster")) {
            return "booster/gp";
        }
        if (n.contains("shard")) {
            return "shard/" + tid;
        }
        if (n.contains("contraband")) {
            return "contraband/" + tid;
        }
        if (n.contains("clue scroll")) {
            return "clue_scroll/" + tid;
        }
        if (n.contains("randomization scroll")) {
            return "randomization_scroll/" + tid;
        }
        if (n.contains("white scroll")) {
            return "enchant/white_scroll";
        }
        if (n.contains("black scroll")) {
            return "enchant/black_scroll";
        }
        if (n.startsWith("eraser")) {
            return "enchant/eraser";
        }
        if (n.startsWith("absorber")) {
            return "enchant/absorber";
        }
        if (n.contains("enchant book") || n.contains("mystery book") || (n.contains("mystery") && n.contains("enchant"))) {
            return "book/" + tid;
        }
        if (n.endsWith(" page") || n.contains("% page") || n.contains("page (")) {
            return "page/" + tid;
        }
        if (n.contains("bandit box key") || (n.contains("key") && t != null)) {
            return "key/" + tid;
        }
        if (n.contains("xp bottle") || n.contains("experience bottle")) {
            return "xp_bottle/" + tid;
        }
        if (n.contains("xp booster")) {
            return "booster/xp";
        }
        if (n.contains("energy booster")) {
            return "booster/energy";
        }
        if (n.contains("cosmic energy")) {
            return "misc/cosmic_energy";
        }
        if (n.contains("money note") || n.contains("bank note") || n.contains("cash note")) {
            return "misc/money_note";
        }
        if (n.contains("gang point")) {
            return "misc/gang_points";
        }
        if (n.contains("cosmic coin")) {
            return "misc/cosmic_coins";
        }
        if (n.contains("lootbox") || n.contains("megabox") || n.contains("cosmic crate")) {
            return "misc/cosmic_crate";
        }
        for (String[] misc : MISC_BY_NAME) {
            if (n.contains(misc[0])) {
                return misc[1];
            }
        }
        // a revealed enchant: "Aegis I (17%)", "Absolute Efficiency XI (100%)" - pickaxe enchants are orbs
        java.util.regex.Matcher revealed = REVEALED.matcher(n);
        if (revealed.matches()) {
            return (TOOL_ORB_ENCHANTS.contains(revealed.group(1)) ? "enchant_orb/" : "book_revealed/") + tid;
        }
        return null;
    }

    /** "+1 home", "item nametag", ... -> fixed looks (lower case name parts, checked in this order). */
    private static final String[][] MISC_BY_NAME = {
            {"prestige protection scroll", "enchant/white_scroll"}, {"+1 home", "misc/home_expander"}, {"+1 pv row", "misc/pv_expander"}, {"lucky charm", "misc/lucky_charm"},
            {"rabbit's foot", "misc/rabbits_foot"}, {"nametag", "misc/nametag"}, {"name tag", "misc/nametag"},
            {"lore crystal", "misc/lore_crystal"}, {"flip credit", "misc/flip_credit"},
            {"pet leash", "misc/pet_leash"}, {"boss egg", "misc/boss_egg"},
            {"skill tree reset", "misc/skill_tree_reset"}, {"altar pillar", "misc/altar_pillar"},
            {"pet incubator", "misc/pet_incubator"}, {"kill message", "misc/kill_message"},
            {"title \"", "misc/title"}, {" title", "misc/title"}, {"pickaxe enchant", "misc/rare_pickaxe_enchant"},
            {"mining enchant", "misc/rare_pickaxe_enchant"}, {"aether bloom", "misc/aether_bloom"},
            {"red roses", "misc/red_roses"},
    };
    /** "aegis i (17%)" -> "aegis"; scrolls / dust / pages with a level are not enchants. */
    private static final java.util.regex.Pattern REVEALED =
            java.util.regex.Pattern.compile("^((?!.*(?:scroll|dust|page|token|modifier))[a-z][a-z' ]+?) (?:[ivx]+) \\(\\d{1,3}%\\)$");
    /** Pickaxe enchants come as orbs (Cosmic chat: "Magnet I (N%)" orbs, "whistle orbs", "absolute eff orbs"). */
    private static final java.util.Set<String> TOOL_ORB_ENCHANTS = java.util.Set.of("absolute efficiency", "efficiency",
            "magnet", "whistle", "momentum", "flurry", "jackhammer", "fortune");
    private static final String[] SATCHEL_ORES = {"coal", "iron", "lapis", "redstone", "gold", "diamond", "emerald",
            "prismarine", "quartz", "amethyst"};

    /** "Deepslate Gold Ore Satchel (0 / 2,304 Ores)" -> satchel/gold_deepslate, "Gold Satchel" (refined) -> gold_block. */
    static String satchel(String n) {
        if (n.startsWith("random")) {
            return "satchel/random";
        }
        for (String ore : SATCHEL_ORES) {
            if (n.contains(ore)) {
                if (n.contains("deepslate")) {
                    return "satchel/" + ore + "_deepslate";
                }
                return n.contains(ore + " ore") ? "satchel/" + ore : "satchel/" + ore + "_block";
            }
        }
        return "satchel/random";
    }

    /** A tier word anywhere in the name ("II Rare Candy II" has none, "Elite Contraband" has one). */
    static @Nullable Tier tierWord(String n) {
        for (Tier tier : Tier.values()) {
            if (java.util.regex.Pattern.compile("\\b" + tier.id + "\\b").matcher(n).find()) {
                return tier;
            }
        }
        return null;
    }

    /** "Tool Prestige Token IV" -> 4 (1 when there is no roman numeral). */
    static int romanLevel(String name) {
        java.util.regex.Matcher m = ROMAN.matcher(name.toUpperCase(Locale.ROOT));
        int best = 1;
        while (m.find()) {
            best = Math.max(best, switch (m.group(1)) {
                case "I" -> 1;
                case "II" -> 2;
                case "III" -> 3;
                case "IV" -> 4;
                case "V" -> 5;
                case "VI" -> 6;
                case "VII" -> 7;
                case "VIII" -> 8;
                case "IX" -> 9;
                case "X" -> 10;
                default -> 1;
            });
        }
        return best;
    }

    /** Badges from the name: "12% Charge Orb" -> "12%", "Tool Prestige Token IV" -> "P4". */
    static String nameBadge(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        if (n.contains("charge orb") && !n.contains("slot")) {
            java.util.regex.Matcher m = PERCENT.matcher(n);
            return m.find() ? m.group(1) + "%" : "";
        }
        if (n.contains("prestige token")) {
            return "P" + romanLevel(name);
        }
        return "";
    }

    /** Masks with their own look: Turkey (a rooster), Nitro (flames, goggles), Outpost, Lucky Leprechaun, Valor. */
    static @Nullable String maskTheme(String text) {
        String t = text.toLowerCase(Locale.ROOT);
        if (t.contains("turkey")) {
            return "turkey";
        }
        if (t.contains("nitro")) {
            return "nitro";
        }
        if (t.contains("outpost")) {
            return "outpost";
        }
        if (t.contains("leprechaun")) {
            return "leprechaun";
        }
        if (t.contains("valor")) {
            return "valor";
        }
        if (t.contains("clue")) {
            return "clue";
        }
        if (t.contains("sentinel")) {
            return "sentinel";
        }
        if (t.contains("anonymous")) {
            return "anonymous";
        }
        if (t.contains("prisoner")) {
            return "prisoner";
        }
        return null;
    }

    /**
     * The 3D model for a mask worn on the head: the mask item itself, or a helmet with a mask attached (a lore line
     * naming "... Mask"). {@code null} when the head item is no mask.
     */
    public static @Nullable Identifier wornMask(ItemStack stack) {
        if (stack.isEmpty()) {
            return null;
        }
        Info info = info(stack);
        Identifier model = info.model();
        if (model != null && model.getPath().startsWith("prisons/mask/")) {
            return Identifier.of("theprisons", "prisons/mask_worn/" + model.getPath().substring("prisons/mask/".length()));
        }
        LoreComponent lore = stack.get(DataComponentTypes.LORE);
        // Cosmic's helmets are player heads with a skin ("Sludge Helmet 30", lore "Mask (Turkey Mask)").
        if (lore == null || !(stack.contains(DataComponentTypes.EQUIPPABLE) || stack.isOf(net.minecraft.item.Items.PLAYER_HEAD))) {
            return null;
        }
        java.util.Optional<Identifier> cached = WORN.get(lore);
        if (cached == null) {
            cached = java.util.Optional.ofNullable(attachedMask(lore, info.tier()));
            if (WORN.size() >= CACHE_LIMIT) {
                WORN.clear();
            }
            WORN.put(lore, cached);
        }
        return cached.orElse(null);
    }

    private static @Nullable Identifier attachedMask(LoreComponent lore, @Nullable Tier tier) {
        for (Text line : lore.lines()) {
            String text = TextStrip.strip(line.getString());
            String lower = text.toLowerCase(Locale.ROOT);
            if (!MASK.matcher(text).find() || lower.contains("empty") || lower.contains("none")) {
                continue;
            }
            String theme = maskTheme(text);
            return Identifier.of("theprisons", "prisons/mask_worn/" + (theme != null ? theme : (tier != null ? tier : Tier.SIMPLE).id));
        }
        return null;
    }

    private static final java.util.Set<String> LOGGED_MASKS = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** Logs the data of items that mention a mask once (to see how Cosmic marks a mask on a helmet). */
    private static void logMaskItem(ItemStack stack, @Nullable Text name, @Nullable LoreComponent lore) {
        String n = name != null ? name.getString() : stack.getName().getString();
        StringBuilder all = new StringBuilder(n);
        if (lore != null) {
            for (Text line : lore.lines()) {
                all.append(" | ").append(line.getString());
            }
        }
        if (!all.toString().toLowerCase(Locale.ROOT).contains("mask") || LOGGED_MASKS.size() > 64 || !LOGGED_MASKS.add(n)) {
            return;
        }
        com.freelocs.theprisons.ThePrisonsClient.LOGGER.info("[item_look] mask item: id={} name='{}' model={} data={} text={}",
                net.minecraft.registry.Registries.ITEM.getId(stack.getItem()), n, stack.get(DataComponentTypes.ITEM_MODEL),
                stack.get(DataComponentTypes.CUSTOM_DATA), TextStrip.strip(all.toString()));
    }

    /** Cosmic's tier colours on the item name (white / grey say nothing), else a lore line starting with a tier. */
    private static @Nullable Tier hintTier(@Nullable Text name, @Nullable LoreComponent lore) {
        if (name != null) {
            Integer rgb = name.visit((style, text) -> text.isBlank() || style.getColor() == null
                    ? java.util.Optional.<Integer>empty() : java.util.Optional.of(style.getColor().getRgb()), Style.EMPTY).orElse(null);
            Tier tier = colourTier(rgb);
            if (tier != null) {
                return tier;
            }
        }
        if (lore != null) {
            for (Text line : lore.lines()) {
                String first = TextStrip.strip(line.getString()).split(" ", 2)[0].toUpperCase(Locale.ROOT);
                for (Tier tier : Tier.values()) {
                    if (first.equals(tier.name())) {
                        return tier;
                    }
                }
            }
        }
        return null;
    }

    static @Nullable Tier colourTier(@Nullable Integer rgb) {
        if (rgb == null) {
            return null;
        }
        return switch (rgb & 0xFFFFFF) {
            case 0x55FF55, 0x00AA00 -> Tier.UNCOMMON;
            case 0x55FFFF, 0x00AAAA -> Tier.ELITE;
            case 0xFFFF55 -> Tier.ULTIMATE;
            case 0xFFAA00 -> Tier.LEGENDARY;
            case 0xFF5555, 0xAA0000 -> Tier.GODLY;
            default -> null;
        };
    }

    static Info resolve(NbtCompound values, @Nullable String customName) {
        return resolve(values, customName, null);
    }

    /** Pure lookup on the {@code PublicBukkitValues} compound (unit tested). */
    static Info resolve(NbtCompound values, @Nullable String customName, @Nullable Tier hint) {
        if (values.isEmpty() && customName == null && hint == null) {
            return Info.NONE;
        }
        String id = str(values, "custom_item_id");
        String name = customName != null ? customName : "";
        Tier tier;
        String badge = "";
        String model;
        // Recognised by name first (these have no own item id), like Cosmic Textures does.
        if (name.contains("Secret Dust")) {
            tier = nameTier(name);
            return info(family("secret_dust", tier), tier, "");
        }
        if (name.contains("Cosmic Coin")) {
            return info("misc/cosmic_coins", null, "");
        }
        if (name.contains("GP Booster")) {
            return info("booster/gp", null, "");
        }
        // Enchant orbs before the books: Cosmic names them alike ("Mystery Godly Tool Enchant Orb").
        String orb = orbFamily(name, id);
        if (orb != null) {
            Tier t = anyTier(values);
            String lower = name.toLowerCase(Locale.ROOT);
            if (t == null) {
                t = tierWord(lower);
            }
            if (t == null) {
                t = hint != null ? hint : Tier.SIMPLE;
            }
            return info(family(orb, t), t, lower.contains("mystery") || lower.contains("random") ? "?" : "");
        }
        switch (id) {
            case "shard" -> {
                tier = Tier.parse(str(values, "shard_tier"));
                model = family("shard", tier);
            }
            case "pickaxe_enchant_dust" -> {
                tier = Tier.parse(str(values, "pickaxe_dust_tier"));
                model = family("dust", tier);
            }
            case "xp_bottle" -> {
                tier = Tier.parse(str(values, "xp_tier"));
                model = family("xp_bottle", tier);
            }
            case "gear_enchant_page" -> {
                tier = Tier.parse(str(values, "page_tier"));
                model = family("page", tier);
            }
            case "bandit_box_key" -> {
                tier = Tier.parse(str(values, "bandit_box_key_tier"));
                model = family("key", tier);
            }
            case "clue_scroll", "mystery_clue_scroll" -> {
                tier = Tier.parse(str(values, "mystery_tier"));
                if (tier == null) {
                    tier = Tier.parse(str(values, "clue_scroll_data"));
                }
                model = family("clue_scroll", tier);
            }
            case "mystery_enchant_book" -> {
                tier = Tier.parse(str(values, "mystery_tier"));
                model = family("book", tier);
            }
            case "gear_enchant_book" -> {
                tier = Tier.parse(str(values, "gear_enchant_tier"));
                int level = values.getInt(NS + "gear_enchant_level", 0);
                badge = level > 0 ? roman(level) : "";
                model = family("book_revealed", tier);
            }
            case "randomization_scroll" -> {
                tier = Tier.ofIndex(values.getInt(NS + "randomization_scroll_tier", -1));
                model = family("randomization_scroll", tier);
            }
            case "mystery_chest" -> {
                String chest = str(values, "mysterychestid");
                tier = chest.contains("enchantbook") ? Tier.parse(chest) : null;
                model = family("book", tier);
            }
            case "charge_orb" -> {
                tier = null;
                int percent = values.getInt(NS + "charge_orb_percent", 0);
                badge = percent > 0 ? percent + "%" : "";
                model = "charge_orb/stage_" + Math.max(1, Math.min(4, (percent - 1) / 5 + 1));
            }
            case "pickaxe_prestige_token" -> {
                tier = null;
                int level = values.getInt(NS + "prestige_token_level", 0);
                badge = level > 0 ? "P" + level : "";
                model = "prestige_token/level_" + Math.max(1, Math.min(10, level));
            }
            case "absorber", "white_scroll", "black_scroll", "eraser" -> {
                tier = null;
                model = "enchant/" + id;
            }
            case "gang_point_note" -> {
                tier = null;
                model = "misc/gang_points";
            }
            case "cosmic_energy", "money_note", "gen_breaker", "cosmic_crate", "slot_bot_ticket" -> {
                tier = null;
                model = "misc/" + id;
            }
            case "booster" -> {
                tier = null;
                model = switch (str(values, "booster_type")) {
                    case "xp" -> "booster/xp";
                    case "energy" -> "booster/energy";
                    default -> null;
                };
            }
            default -> {
                if (id.startsWith("contraband_")) {
                    tier = Tier.parse(id.substring("contraband_".length()));
                    model = family("contraband", tier);
                } else {
                    tier = anyTier(values);
                    model = null;
                }
            }
        }
        if (tier == null && !name.isEmpty()) {
            tier = nameTier(name);
        }
        if (tier == null) {
            tier = hint;
        }
        // Cosmic often sends items without their data: recognise them by name.
        if (model == null && !name.isEmpty()) {
            String byName = byName(name.toLowerCase(Locale.ROOT), tier);
            if (byName != null) {
                model = byName;
                if (badge.isEmpty()) {
                    badge = nameBadge(name);
                }
            }
        }
        // Masks and pets: by item id or name, coloured by their rarity.
        if (model == null && (id.contains("mask") || MASK.matcher(name).find())) {
            String theme = maskTheme(name + " " + id);
            model = theme != null ? "mask/" + theme : family("mask", tier != null ? tier : Tier.SIMPLE);
        } else if (model == null && (id.contains("pet") || PET.matcher(name).find())) {
            model = "pet/" + petCreature(name + " " + id) + "_" + (tier != null ? tier : Tier.SIMPLE).id;
        } else if (model == null && (id.contains("reroll") || id.contains("re_roll") || REROLL.matcher(name).find())) {
            model = family("reroll", tier != null ? tier : Tier.SIMPLE);
        }
        return info(model, tier, badge);
    }

    /**
     * The look of an enchant orb: "spear_orb" for spear orbs, "enchant_orb" for tool / pickaxe orbs, null when the
     * item is no enchant orb (charge and energy orbs have their own looks).
     */
    static @Nullable String orbFamily(String name, String id) {
        String n = (name + " " + id).toLowerCase(Locale.ROOT);
        if (n.contains("charge orb") || n.contains("charge_orb") || n.contains("energy orb") || n.contains("modifier")
                || n.contains("mastery") || !ORB.matcher(n).find()) {
            return null;
        }
        if (!(n.contains("enchant") || n.contains("tool") || n.contains("pickaxe") || n.contains("spear")
                || n.contains("mystery"))) {
            return null;
        }
        return n.contains("spear") ? "spear_orb" : "enchant_orb";
    }

    private static Info info(@Nullable String model, @Nullable Tier tier, String badge) {
        if (model == null && tier == null && badge.isEmpty()) {
            return Info.NONE;
        }
        Identifier id = model != null ? Identifier.of("theprisons", "prisons/" + model) : null;
        return new Info(id, tier, badge, model != null ? Identifier.of("theprisons", "prisons/" + plain(model)) : null);
    }

    /** The look of the plain base item: the Simple tier of a tiered family, the first stage / level otherwise. */
    static String plain(String model) {
        int slash = model.indexOf('/');
        String family = model.substring(0, slash);
        String variant = model.substring(slash + 1);
        if (Tier.parse(variant) != null && variant.equals(Tier.parse(variant).id)) {
            return family + "/" + Tier.SIMPLE.id;
        }
        return switch (family) {
            case "charge_orb" -> "charge_orb/stage_1";
            case "prestige_token" -> "prestige_token/level_1";
            default -> model;
        };
    }

    private static @Nullable String family(String family, @Nullable Tier tier) {
        return tier != null ? family + "/" + tier.id : null;
    }

    private static String str(NbtCompound values, String key) {
        return values.getString(NS + key, "");
    }

    /** Any other tiered item: the first {@code cosmicprisons:*tier*} value that names a tier. */
    private static @Nullable Tier anyTier(NbtCompound values) {
        for (String key : values.getKeys()) {
            if (key.startsWith(NS) && key.contains("tier")) {
                Tier tier = Tier.parse(values.getString(key, ""));
                if (tier != null) {
                    return tier;
                }
            }
        }
        return null;
    }

    /** "Elite Pickaxe Dust", "Godly Shard": a tier word at the start of the name. */
    private static @Nullable Tier nameTier(String name) {
        String first = name.trim().split("\\s+", 2)[0].toUpperCase(Locale.ROOT);
        for (Tier tier : Tier.values()) {
            if (first.equals(tier.name())) {
                return tier;
            }
        }
        return null;
    }

    static String roman(int n) {
        if (n <= 0 || n >= 40) {
            return Integer.toString(n);
        }
        String[] tens = {"", "X", "XX", "XXX"};
        String[] ones = {"", "I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX"};
        return tens[n / 10] + ones[n % 10];
    }
}
