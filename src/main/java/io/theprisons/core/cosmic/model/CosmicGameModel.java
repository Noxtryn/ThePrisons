package io.theprisons.core.cosmic.model;

import io.theprisons.core.cosmic.value.GameValue;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * What ThePrisons knows about Cosmic Prisons as a game: rules and facts by area (mining, pickaxe, energy ...) and the six
 * registries. It is knowledge, not state: what the client sees right now is the {@code CosmicContextSnapshot}.
 *
 * <p>Everything is a {@link GameValue}; what nobody has confirmed stays UNKNOWN. The data is in
 * {@code assets/theprisons/cosmic/*.json} and grows by editing those files.
 */
public final class CosmicGameModel {
    /** The areas of the game the model is organised by. */
    public enum Area {
        MINING, PLAYER, PICKAXE, ENERGY, ENCHANTS, BANDITS, WEAPONS, ARMOR, ITEMS, ZONES, EVENTS, SEASON, SOCIAL, ECONOMY;

        /** The key prefix: "mining", "weapons" ... */
        public String prefix() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }
    }

    /** One area's view of the knowledge file. */
    public static final class AreaView {
        private final Area area;
        private final Knowledge knowledge;

        AreaView(Area area, Knowledge knowledge) {
            this.area = area;
            this.knowledge = knowledge;
        }

        public Area area() {
            return area;
        }

        /** {@code key} without the area prefix: area MINING + "speedRequirement" = "mining.speedRequirement". */
        public GameValue<Object> value(String key) {
            return knowledge.get(area.prefix() + "." + key);
        }

        public <T> GameValue<T> value(String key, Class<T> type) {
            return knowledge.get(area.prefix() + "." + key, type);
        }
    }

    private final Knowledge knowledge;
    private final Registries.OreRegistry ores;
    private final Registries.PickaxeRegistry pickaxes;
    private final Registries.EnchantRegistry enchants;
    private final Registries.BanditRegistry bandits;
    private final Registries.ZoneRegistry zones;
    private final Registries.ItemRegistry items;
    private final Map<Area, AreaView> areas = new EnumMap<>(Area.class);

    public CosmicGameModel(Knowledge knowledge, List<Entry> ores, List<Entry> pickaxes, List<Entry> enchants, List<Entry> bandits,
                           List<Entry> zones, List<Entry> items) {
        this.knowledge = knowledge;
        this.ores = new Registries.OreRegistry(ores);
        this.pickaxes = new Registries.PickaxeRegistry(pickaxes);
        this.enchants = new Registries.EnchantRegistry(enchants);
        this.bandits = new Registries.BanditRegistry(bandits);
        this.zones = new Registries.ZoneRegistry(zones);
        this.items = new Registries.ItemRegistry(items);
        for (Area area : Area.values()) {
            areas.put(area, new AreaView(area, knowledge));
        }
    }

    /** The model shipped with the mod (the JSON files under assets/theprisons/cosmic). */
    public static CosmicGameModel loadDefault() {
        String path = "/assets/theprisons/cosmic/knowledge.json";
        Knowledge knowledge = Knowledge.load(CosmicGameModel.class.getResourceAsStream(path), path);
        return new CosmicGameModel(knowledge, KnowledgeRegistry.load("ores.json"), KnowledgeRegistry.load("pickaxes.json"),
                KnowledgeRegistry.load("enchants.json"), KnowledgeRegistry.load("bandits.json"), KnowledgeRegistry.load("zones.json"),
                KnowledgeRegistry.load("items.json"));
    }

    /** A model that knows nothing (everything UNKNOWN), for tests and as the fallback when the data cannot be read. */
    public static CosmicGameModel empty() {
        return new CosmicGameModel(Knowledge.empty(), List.of(), List.of(), List.of(), List.of(),
                List.of(Entry.unknown("unknown")), List.of(Entry.unknown("unknown_cosmic")));
    }

    public AreaView area(Area area) {
        return areas.get(area);
    }

    public AreaView mining() {
        return area(Area.MINING);
    }

    public AreaView player() {
        return area(Area.PLAYER);
    }

    public AreaView pickaxe() {
        return area(Area.PICKAXE);
    }

    public AreaView energy() {
        return area(Area.ENERGY);
    }

    public AreaView enchantsArea() {
        return area(Area.ENCHANTS);
    }

    public AreaView banditsArea() {
        return area(Area.BANDITS);
    }

    public AreaView weapons() {
        return area(Area.WEAPONS);
    }

    public AreaView armor() {
        return area(Area.ARMOR);
    }

    public AreaView itemsArea() {
        return area(Area.ITEMS);
    }

    public AreaView zonesArea() {
        return area(Area.ZONES);
    }

    public AreaView events() {
        return area(Area.EVENTS);
    }

    public AreaView season() {
        return area(Area.SEASON);
    }

    public AreaView social() {
        return area(Area.SOCIAL);
    }

    public AreaView economy() {
        return area(Area.ECONOMY);
    }

    public Knowledge knowledge() {
        return knowledge;
    }

    public Registries.OreRegistry ores() {
        return ores;
    }

    public Registries.PickaxeRegistry pickaxes() {
        return pickaxes;
    }

    public Registries.EnchantRegistry enchants() {
        return enchants;
    }

    public Registries.BanditRegistry bandits() {
        return bandits;
    }

    public Registries.ZoneRegistry zones() {
        return zones;
    }

    public Registries.ItemRegistry items() {
        return items;
    }
}
