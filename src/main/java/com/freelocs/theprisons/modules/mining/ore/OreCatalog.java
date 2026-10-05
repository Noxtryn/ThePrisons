package com.freelocs.theprisons.modules.mining.ore;

import com.freelocs.theprisons.core.setting.Settings;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.registry.Registries;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * The ore packages of the Ore Macro, two per ore as the prison mines are laid out: "Redstone" = the ore only,
 * "Deepslate Redstone" = the deepslate ore and the ore block. All are selected by default. Ores of packages that are
 * not selected mark the border of the mine: the macro turns back where it sees them.
 */
public final class OreCatalog {
    private record Pack(String id, String label, String section, int color, List<Block> blocks) {
    }

    private static final List<Pack> PACKS = List.of(
            new Pack("coal", "Coal", "Coal", 0xFF9AA0B8, List.of(Blocks.COAL_ORE)),
            new Pack("deepslate_coal", "Deepslate Coal", "Coal", 0xFF9AA0B8, List.of(Blocks.DEEPSLATE_COAL_ORE, Blocks.COAL_BLOCK)),
            new Pack("iron", "Iron", "Iron", 0xFFE3C4A8, List.of(Blocks.IRON_ORE)),
            new Pack("deepslate_iron", "Deepslate Iron", "Iron", 0xFFE3C4A8, List.of(Blocks.DEEPSLATE_IRON_ORE, Blocks.IRON_BLOCK)),
            new Pack("copper", "Copper", "Copper", 0xFFE8915A, List.of(Blocks.COPPER_ORE)),
            new Pack("deepslate_copper", "Deepslate Copper", "Copper", 0xFFE8915A, List.of(Blocks.DEEPSLATE_COPPER_ORE, Blocks.COPPER_BLOCK)),
            new Pack("lapis", "Lapis", "Lapis", 0xFF4D8DFF, List.of(Blocks.LAPIS_ORE)),
            new Pack("deepslate_lapis", "Deepslate Lapis", "Lapis", 0xFF4D8DFF, List.of(Blocks.DEEPSLATE_LAPIS_ORE, Blocks.LAPIS_BLOCK)),
            new Pack("redstone", "Redstone", "Redstone", 0xFFFF5E6C, List.of(Blocks.REDSTONE_ORE)),
            new Pack("deepslate_redstone", "Deepslate Redstone", "Redstone", 0xFFFF5E6C, List.of(Blocks.DEEPSLATE_REDSTONE_ORE, Blocks.REDSTONE_BLOCK)),
            new Pack("gold", "Gold", "Gold", 0xFFFFC14D, List.of(Blocks.GOLD_ORE)),
            new Pack("deepslate_gold", "Deepslate Gold", "Gold", 0xFFFFC14D, List.of(Blocks.DEEPSLATE_GOLD_ORE, Blocks.GOLD_BLOCK)),
            new Pack("diamond", "Diamond", "Diamond", 0xFF00E5FF, List.of(Blocks.DIAMOND_ORE)),
            new Pack("deepslate_diamond", "Deepslate Diamond", "Diamond", 0xFF00E5FF, List.of(Blocks.DEEPSLATE_DIAMOND_ORE, Blocks.DIAMOND_BLOCK)),
            new Pack("emerald", "Emerald", "Emerald", 0xFF65F59B, List.of(Blocks.EMERALD_ORE)),
            new Pack("deepslate_emerald", "Deepslate Emerald", "Emerald", 0xFF65F59B, List.of(Blocks.DEEPSLATE_EMERALD_ORE, Blocks.EMERALD_BLOCK)));

    /** All packages. */
    public static final List<String> DEFAULT_SELECTION = PACKS.stream().map(Pack::id).toList();

    private OreCatalog() {
    }

    public static List<Settings.Option> options() {
        List<Settings.Option> options = new ArrayList<>();
        for (Pack pack : PACKS) {
            options.add(new Settings.Option(pack.id(), pack.label(), pack.section(), pack.color()));
        }
        return options;
    }

    /** Every block of the selected packages. */
    public static List<Block> resolve(Collection<String> selected) {
        List<Block> blocks = new ArrayList<>();
        for (Pack pack : PACKS) {
            if (selected.contains(pack.id())) {
                blocks.addAll(pack.blocks());
            }
        }
        return blocks;
    }

    /** Every block of every package (the scanner indexes all of them, to see the border of the mine). */
    public static List<Block> allBlocks() {
        List<Block> blocks = new ArrayList<>();
        for (Pack pack : PACKS) {
            blocks.addAll(pack.blocks());
        }
        return blocks;
    }

    /** Package id of a block, or {@code null} when it belongs to none. */
    public static @org.jspecify.annotations.Nullable String packOf(Block block) {
        for (Pack pack : PACKS) {
            if (pack.blocks().contains(block)) {
                return pack.id();
            }
        }
        return null;
    }

    /** Display name of a package ("Deepslate Redstone"); the id itself when unknown. */
    public static String label(String packId) {
        for (Pack pack : PACKS) {
            if (pack.id().equals(packId)) {
                return pack.label();
            }
        }
        return packId;
    }

    public static int color(String packId) {
        for (Pack pack : PACKS) {
            if (pack.id().equals(packId)) {
                return pack.color();
            }
        }
        return 0xFF9AA0B8;
    }

    public static String id(Block block) {
        return Registries.BLOCK.getId(block).toString();
    }
}
