package io.theprisons.core.cosmic.model;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/** The six registries with their lookup rules. Each is a thin layer over {@link KnowledgeRegistry}. */
public final class Registries {
    private Registries() {
    }

    /** Ores by vanilla block id: "minecraft:deepslate_redstone_ore" and "minecraft:redstone_ore" are the ore "redstone". */
    public static final class OreRegistry extends KnowledgeRegistry {
        public OreRegistry(List<Entry> entries) {
            super("ores", entries);
        }

        public static String oreIdOfBlock(String blockId) {
            String id = blockId.contains(":") ? blockId.substring(blockId.indexOf(':') + 1) : blockId;
            if (id.startsWith("deepslate_")) {
                id = id.substring("deepslate_".length());
            }
            return id.endsWith("_ore") ? id.substring(0, id.length() - "_ore".length()) : "";
        }

        public boolean isDeepslateVariant(String blockId) {
            return blockId.contains("deepslate_");
        }

        /** The ore a block is, or a placeholder (id "") when the block is no ore. */
        public Entry forBlock(String blockId) {
            String ore = oreIdOfBlock(blockId);
            return ore.isEmpty() ? Entry.unknown("") : get(ore);
        }
    }

    /** Pickaxes by item id: "minecraft:diamond_pickaxe" is the material "diamond". */
    public static final class PickaxeRegistry extends KnowledgeRegistry {
        public PickaxeRegistry(List<Entry> entries) {
            super("pickaxes", entries);
        }

        public Optional<Entry> forItem(String itemId) {
            String id = itemId.contains(":") ? itemId.substring(itemId.indexOf(':') + 1) : itemId;
            return id.endsWith("_pickaxe") ? Optional.of(get(id.substring(0, id.length() - "_pickaxe".length()))) : Optional.empty();
        }
    }

    /** Enchants by the name a lore line starts with ("Fractured III" is "fractured"). */
    public static final class EnchantRegistry extends KnowledgeRegistry {
        public EnchantRegistry(List<Entry> entries) {
            super("enchants", entries);
        }

        public Optional<Entry> forLoreLine(String line) {
            String lower = line.toLowerCase(Locale.ROOT).trim();
            for (Entry entry : all()) {
                if (lower.startsWith(entry.name().toLowerCase(Locale.ROOT))) {
                    return Optional.of(entry);
                }
            }
            return Optional.empty();
        }
    }

    /** Bandit kinds by {@code BanditClassifier.Kind} name in lower case. */
    public static final class BanditRegistry extends KnowledgeRegistry {
        public BanditRegistry(List<Entry> entries) {
            super("bandits", entries);
        }

        public Entry forKind(String kind) {
            return get(kind.toLowerCase(Locale.ROOT));
        }
    }

    /** Zones by the name the chat gives ("diamond", "spawn", "mine"); an unlisted name is the unknown zone. */
    public static final class ZoneRegistry extends KnowledgeRegistry {
        public ZoneRegistry(List<Entry> entries) {
            super("zones", entries);
        }

        public Entry forZone(String zone) {
            String key = zone.toLowerCase(Locale.ROOT).trim();
            return key.isEmpty() ? get("unknown") : find(key).orElseGet(() -> get("unknown"));
        }
    }

    /** Item classes (the mod's own classification of Cosmic items); an unrecognised item is "unknown_cosmic". */
    public static final class ItemRegistry extends KnowledgeRegistry {
        public ItemRegistry(List<Entry> entries) {
            super("items", entries);
        }

        public Entry forClass(String itemClass) {
            return itemClass.isEmpty() ? get("unknown_cosmic") : find(itemClass).orElseGet(() -> get("unknown_cosmic"));
        }
    }
}
