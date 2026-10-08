package io.theprisons.modules.qol.items;

import io.theprisons.core.module.Category;
import io.theprisons.core.module.Module;
import io.theprisons.core.setting.Settings;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.item.ItemStack;
import net.minecraft.util.Identifier;
import org.jspecify.annotations.Nullable;

import java.util.EnumMap;
import java.util.Map;

/**
 * Always-on item look for Cosmic Prisons: own textures for tiered items (shards, dust, XP bottles, pages, keys, clue
 * scrolls, books, randomization scrolls, contraband, charge orbs, prestige tokens, boosters), a frame in the tier
 * colour behind tiered items in every slot and small badges (charge orb %, prestige level, enchant level).
 *
 * <p>Works next to the Cosmic Textures mod: by default its textures win and ours fill in where it has none; the
 * "Texture source" setting can put ours first.
 */
public final class ItemLookModule extends Module {
    public enum Source {
        COSMIC_FIRST("Cosmic Textures first"), THEPRISONS_FIRST("ThePrisons first"), OFF("Off (vanilla / other mods)");

        private final String label;

        Source(String label) {
            this.label = label;
        }
    }

    /** Which ThePrisons art the item models show. Both HD overlays replace only their approved PNG paths; all other items stay Classic. */
    public enum TexturePack {
        CLASSIC("Classic"), HD_V2("HD V2"), HD_V4("HD V4 (validated)");

        private final String label;

        TexturePack(String label) {
            this.label = label;
        }
    }

    public enum FrameScope {
        EVERYWHERE("Inventories and hotbar"), SCREENS("Inventories only"), OFF("Off");

        private final String label;

        FrameScope(String label) {
            this.label = label;
        }
    }

    private static final boolean COSMIC_TEXTURES = FabricLoader.getInstance().isModLoaded("cosmic-textures");
    private static @Nullable ItemLookModule instance;

    private final Settings.EnumSetting<Source> source;
    private final Settings.EnumSetting<TexturePack> texturePack;
    private final Settings.EnumSetting<FrameScope> frames;
    private final Settings.BoolSetting badges;
    private final Settings.BoolSetting pickaxes;
    private final Settings.BoolSetting plainItems;
    private final Settings.BoolSetting gear;
    private static final Identifier FRAME = Identifier.of("theprisons", "tier_frame/tint");
    /** The tier colours of the textures (tools/textures/generate_item_textures.py TIERS). */
    private static final Map<PrisonsItems.Tier, Integer> TIER_RGB = new EnumMap<>(Map.of(
            PrisonsItems.Tier.SIMPLE, 0xD8DEE8, PrisonsItems.Tier.UNCOMMON, 0x5DE86B, PrisonsItems.Tier.ELITE, 0x4FD8F0,
            PrisonsItems.Tier.ULTIMATE, 0xFFE04A, PrisonsItems.Tier.LEGENDARY, 0xFF9A2E, PrisonsItems.Tier.GODLY, 0xFF3D6E));
    /** Main colour of the material textures (armour, tools, weapons) - the frame matches what the icon looks like. */
    private static final Map<String, Integer> MATERIAL_RGB = Map.ofEntries(
            Map.entry("netherite", 0x7A5A8C), Map.entry("diamond", 0x4FE8E0), Map.entry("golden", 0xFFD23C),
            Map.entry("iron", 0xE4E8F0), Map.entry("copper", 0xE38452), Map.entry("chainmail", 0xA8AEB8),
            Map.entry("leather", 0xA86A3C), Map.entry("stone", 0x9CA0A8), Map.entry("wooden", 0xB07A42),
            Map.entry("turtle", 0x5CB85C), Map.entry("trident", 0x5CC8B0), Map.entry("mace", 0x8C8C9C),
            Map.entry("elytra", 0x9A8CC8), Map.entry("bow", 0xB07A42), Map.entry("shield", 0xB07A42),
            Map.entry("shears", 0xE4E8F0), Map.entry("fishing_rod", 0xB07A42), Map.entry("flint_and_steel", 0xE4E8F0));
    /** Pet animals: their fur / skin colour. */
    private static final Map<String, Integer> PET_RGB = Map.ofEntries(Map.entry("anti_xp_tax", 0x7CF05C),
            Map.entry("lucky", 0xFFF6E8), Map.entry("wormhole", 0x8A4CF0), Map.entry("shockwave", 0x3CC8FF),
            Map.entry("cleanse", 0x6CC8FF), Map.entry("signal_jammer", 0xA8AEB8), Map.entry("blacksmith", 0x7A5038),
            Map.entry("bandit_king", 0x8C8C9C), Map.entry("slime", 0x6CE05A), Map.entry("fox", 0xFF7A2E),
            Map.entry("rabbit", 0xF4F4FA), Map.entry("owl", 0x8A5A3A), Map.entry("dragon", 0x3CB89C), Map.entry("pig", 0xFF8FB8));
    private final Map<net.minecraft.item.Item, Integer> materialCache = new java.util.IdentityHashMap<>();

    public ItemLookModule() {
        super("item_look", "Item Textures", Category.QOL, "Items",
                "Own textures for Cosmic Prisons items, pickaxes and their plain base items, tier frames and badges. Always active.",
                Settings.KeybindSetting.NONE);
        source = choice("source", "Texture source", Source.COSMIC_FIRST, s -> s.label)
                .description("Which textures win when the Cosmic Textures mod is installed too.").group("Textures");
        texturePack = choice("texture_pack", "Texture pack", TexturePack.CLASSIC, p -> p.label)
                .description("Classic art, the existing HD V2 overlay, or HD V4 when validated artwork is bundled. Missing HD textures always keep the classic one. "
                        + "Changing it reloads the resources once.").group("Textures");
        HdPackSync.setReloader(() -> MinecraftClient.getInstance().reloadResources());
        HdPackSync.choose(packChoice(texturePack.get()));
        texturePack.onChange(p -> {
            HdPackSync.choose(packChoice(p));
            MinecraftClient client = MinecraftClient.getInstance();
            if (client != null) {
                client.execute(HdPackSync.shared()::reconcile);       // at most one reload, on the client thread; loading the config at start only sets the choice
            }
        });
        frames = choice("frames", "Tier frames", FrameScope.EVERYWHERE, s -> s.label)
                .description("A frame in the tier colour (Simple ... Godly) behind tiered items.").group("Display");
        badges = bool("badges", "Badges", true)
                .description("Charge orb %, prestige token level and enchant book level in the slot corner.").group("Display");
        pickaxes = bool("pickaxes", "Pickaxe textures", true)
                .description("Own textures for wooden ... netherite pickaxes (Cosmic pickaxes included).").group("Textures");
        gear = bool("gear", "Sword & spear textures", true)
                .description("MMORPG-style icons for wooden ... netherite swords and spears.")
                .group("Textures");
        plainItems = bool("plain_items", "Plain base items too", true)
                .description("The Minecraft item a Cosmic item is made of (e.g. the shard's item without its data) "
                        + "gets the same look. Learned from the Cosmic items you see.").group("Textures");
        instance = this;
    }

    public static @Nullable ItemLookModule get() {
        return instance;
    }

    /** Whether the optional HD V2 overlay pack belongs in the next resource-pack list; false (classic) until the module exists. */
    public static boolean hdV2Enabled() {
        return HdPackSync.hdV2Chosen();
    }

    private static HdPackSync.Choice packChoice(TexturePack pack) {
        return switch (pack) {
            case CLASSIC -> HdPackSync.Choice.CLASSIC;
            case HD_V2 -> HdPackSync.Choice.V2;
            case HD_V4 -> HdPackSync.Choice.V4;
        };
    }

    /** The reload bookkeeping of the HD pack (the resource-pack mixin reports what it built; the tick below reloads once when it differs). */
    public static HdPackSync hdSync() {
        return HdPackSync.shared();
    }

    @Override
    protected void onEnable() {
        // The config may be read after the resource packs were first built: one check per tick closes that gap with at most one reload.
        on(io.theprisons.core.event.CoreEvents.TickEnd.class, event -> HdPackSync.shared().reconcile());
    }

    @Override
    public boolean toggleable() {
        return false;
    }

    /**
     * The item model to render ({@code current} is what vanilla, the server and other mods - Cosmic Textures - chose).
     * Order: recognised Cosmic item, pickaxe by material, plain base item of a Cosmic item.
     */
    public Identifier model(ItemStack stack, Identifier current) {
        if (source.get() == Source.OFF) {
            return current;
        }
        boolean changedByOthers = !current.equals(stack.get(DataComponentTypes.ITEM_MODEL));
        PrisonsItems.Info info = PrisonsItems.info(stack);
        if (info.model() != null) {
            if (source.get() == Source.COSMIC_FIRST && COSMIC_TEXTURES && changedByOthers) {
                return current; // Cosmic Textures has its own texture for this item
            }
            return info.model();
        }
        if (changedByOthers) {
            return current;
        }
        if (pickaxes.on()) {
            Identifier pickaxe = PICKAXES.get(stack.getItem());
            if (pickaxe != null) {
                return pickaxe;
            }
        }
        if (gear.on()) {
            Identifier icon = GEAR.get(stack.getItem());
            if (icon != null) {
                return icon;
            }
        }
        if (plainItems.on() && !stack.contains(DataComponentTypes.CUSTOM_DATA)) {
            Identifier plain = VanillaBases.plain(stack.getItem());
            if (plain != null) {
                return plain;
            }
        }
        return current;
    }

    private static final Map<net.minecraft.item.Item, Identifier> GEAR = gear();

    /** Spears and swords by material - every id that exists in this version. */
    private static Map<net.minecraft.item.Item, Identifier> gear() {
        Map<net.minecraft.item.Item, Identifier> map = new java.util.IdentityHashMap<>();
        for (String material : new String[]{"wooden", "stone", "copper", "iron", "golden", "diamond", "netherite"}) {
            put(map, material + "_spear", "spear/" + material);
            put(map, material + "_sword", "sword/" + material);
        }
        return map;
    }

    private static void put(Map<net.minecraft.item.Item, Identifier> map, String itemId, String model) {
        net.minecraft.registry.Registries.ITEM.getOptionalValue(Identifier.ofVanilla(itemId))
                .ifPresent(item -> map.put(item, Identifier.of("theprisons", "prisons/" + model)));
    }

    private static final Map<net.minecraft.item.Item, Identifier> PICKAXES = Map.of(
            net.minecraft.item.Items.WOODEN_PICKAXE, Identifier.of("theprisons", "prisons/pickaxe/wooden"),
            net.minecraft.item.Items.STONE_PICKAXE, Identifier.of("theprisons", "prisons/pickaxe/stone"),
            net.minecraft.item.Items.COPPER_PICKAXE, Identifier.of("theprisons", "prisons/pickaxe/copper"),
            net.minecraft.item.Items.IRON_PICKAXE, Identifier.of("theprisons", "prisons/pickaxe/iron"),
            net.minecraft.item.Items.GOLDEN_PICKAXE, Identifier.of("theprisons", "prisons/pickaxe/golden"),
            net.minecraft.item.Items.DIAMOND_PICKAXE, Identifier.of("theprisons", "prisons/pickaxe/diamond"),
            net.minecraft.item.Items.NETHERITE_PICKAXE, Identifier.of("theprisons", "prisons/pickaxe/netherite"));

    /**
     * Before an item is drawn in a GUI: the rarity frame behind it. Every item has a rarity - the Cosmic tier, else
     * the vanilla rarity. Tools, armour, weapons and pets are framed in the main colour of their texture,
     * everything else in the tier colour.
     */
    /** Set while an item is drawn as a "base" texture (the item list's collapsed cells): no rarity frame. */
    public static boolean noFrame;

    public void beforeItem(DrawContext context, ItemStack stack, int x, int y) {
        if (noFrame) {
            return;
        }
        FrameScope scope = frames.get();
        if (scope == FrameScope.OFF || (scope == FrameScope.SCREENS && MinecraftClient.getInstance().currentScreen == null)) {
            return;
        }
        PrisonsItems.Info info = PrisonsItems.info(stack);
        PrisonsItems.Tier tier = info.tier() != null ? info.tier() : vanillaTier(stack.getRarity());
        int rgb = textureColour(stack, info);
        if (rgb < 0) {
            rgb = TIER_RGB.get(tier);
        }
        // Plain Simple items get a quieter frame so a full inventory does not glow everywhere.
        int alpha = tier == PrisonsItems.Tier.SIMPLE ? 0x90 : 0xFF;
        context.drawGuiTexture(RenderPipelines.GUI_TEXTURED, FRAME, x - 1, y - 1, 18, 18, (alpha << 24) | rgb);
    }

    static PrisonsItems.Tier vanillaTier(net.minecraft.util.Rarity rarity) {
        return switch (rarity) {
            case COMMON -> PrisonsItems.Tier.SIMPLE;
            case UNCOMMON -> PrisonsItems.Tier.UNCOMMON;
            case RARE -> PrisonsItems.Tier.ELITE;
            case EPIC -> PrisonsItems.Tier.LEGENDARY;
        };
    }

    /** The main colour of the item's texture for gear and pets; -1 for everything else. */
    private int textureColour(ItemStack stack, PrisonsItems.Info info) {
        Identifier model = info.model();
        if (model != null) {
            String path = model.getPath();
            if (path.startsWith("prisons/pet/")) {
                String variant = path.substring(path.lastIndexOf('/') + 1);
                int underscore = variant.lastIndexOf('_');
                Integer pet = underscore > 0 ? PET_RGB.get(variant.substring(0, underscore)) : null;
                if (pet != null) {
                    return pet;
                }
            }
        }
        if (!stack.contains(DataComponentTypes.EQUIPPABLE) && !stack.contains(DataComponentTypes.TOOL)
                && !stack.contains(DataComponentTypes.WEAPON)) {
            return -1;
        }
        return materialCache.computeIfAbsent(stack.getItem(), item -> {
            String id = net.minecraft.registry.Registries.ITEM.getId(item).getPath();
            for (Map.Entry<String, Integer> entry : MATERIAL_RGB.entrySet()) {
                if (id.contains(entry.getKey())) {
                    return entry.getValue();
                }
            }
            return -1;
        });
    }

    /**
     * The tooltip style of an item: the item's own style if it has one, else the MMORPG frame in its rarity colour
     * ({@code theprisons:tooltip/tier_<tier>_frame / _background}), else the default frame (null).
     */
    public static @Nullable Identifier tooltipStyle(ItemStack stack, @Nullable Identifier own) {
        if (own != null || stack.isEmpty()) {
            return own;
        }
        PrisonsItems.Tier tier = PrisonsItems.info(stack).tier();
        if (tier == null) {
            net.minecraft.util.Rarity rarity = stack.getRarity();
            // Common items get the Simple frame: every item has the mod's tooltip, not only the tiered ones.
            tier = vanillaTier(rarity);
        }
        return Identifier.of("theprisons", "tier_" + tier.id);
    }

    /** After the stack count: the badge in the top-left corner. */
    public void afterOverlay(DrawContext context, TextRenderer textRenderer, ItemStack stack, int x, int y) {
        if (!badges.on()) {
            return;
        }
        String badge = PrisonsItems.info(stack).badge();
        if (badge.isEmpty()) {
            return;
        }
        float scale = 0.6F;
        context.getMatrices().pushMatrix();
        context.getMatrices().translate(x, y);
        context.getMatrices().scale(scale, scale);
        context.drawText(textRenderer, badge, 0, 0, 0xFFFFE89E, true);
        context.getMatrices().popMatrix();
    }
}
