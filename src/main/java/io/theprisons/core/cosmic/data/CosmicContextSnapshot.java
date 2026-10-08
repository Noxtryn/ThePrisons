package io.theprisons.core.cosmic.data;

import io.theprisons.core.cosmic.parse.BanditClassifier;
import io.theprisons.core.cosmic.value.GameValue;
import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 * The observable client state at one moment, immutable. Features read this instead of asking {@code MinecraftClient}; it is
 * built from one {@link Raw.Frame} by {@link SnapshotBuilder}, so the same frame always gives the same snapshot.
 */
public record CosmicContextSnapshot(int schema, long tick, long takenAtMs, ContextKey key, PlayerState player, WorldState world,
                                    CosmicState cosmic, CombatState combat, UiState ui) {
    public static final int SCHEMA = 1;

    /** Which game state values belong to: the server, the dimension and the season / map (unknown until detectable). */
    public record ContextKey(String server, String dimension, String season) {
        public static final ContextKey NONE = new ContextKey("", "", "unknown");

        public String asText() {
            return server + "|" + dimension + "|" + season;
        }
    }

    /** {@code present} = false in the menu / loading screen (no player yet). */
    public record PlayerState(boolean present, double x, double y, double z, double vx, double vy, double vz, float yaw, float pitch,
                              float health, float maxHealth, Raw.Stack held, Raw.Stack offHand, List<Raw.Stack> armor,
                              List<Raw.Effect> effects, Raw.Input input, boolean onGround) {
        public static final PlayerState ABSENT = new PlayerState(false, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, Raw.Stack.EMPTY,
                Raw.Stack.EMPTY, List.of(), List.of(), Raw.Input.NONE, false);

        public PlayerState {
            armor = List.copyOf(armor);
            effects = List.copyOf(effects);
        }

        /** True when the physical keys say the human is moving (a macro must yield). */
        public boolean manualInput() {
            return input.anyMovement();
        }
    }

    public record WorldState(boolean loaded, String dimension, Raw.@Nullable Blocks blocks, int nearbyEntities) {
    }

    /**
     * What the server's own UI says. Every number is a {@link GameValue}: UNKNOWN when the sidebar / lore does not show it.
     *
     * @param sidebar the sidebar lines (empty when there is none); {@code sidebarPresent} tells the two apart
     */
    public record CosmicState(GameValue<String> zone, GameValue<String> event, GameValue<String> mine, GameValue<String> season,
                              boolean sidebarPresent, List<String> sidebar, List<String> bossBars, List<Raw.Line> actionBar,
                              GameValue<Double> taxPercent, GameValue<Long> energyNow, GameValue<Long> energyCapacity,
                              GameValue<Integer> energyLevel, GameValue<Long> totalXp, GameValue<Long> heldPickaxeEnergy,
                              GameValue<Long> heldPickaxeCapacity, GameValue<Double> heldDurability) {
        public CosmicState {
            sidebar = List.copyOf(sidebar);
            bossBars = List.copyOf(bossBars);
            actionBar = List.copyOf(actionBar);
        }
    }

    /** A nearby entity and what the classifiers made of it. */
    public record ClassifiedEntity(Raw.Entity entity, String kind, io.theprisons.core.cosmic.value.Confidence confidence,
                                   String reason) {
    }

    /** Spear state: {@code recognised} = the held item is a spear; cooldown 0..1 (1 = just used) when observable. */
    public record SpearState(GameValue<Boolean> recognised, GameValue<Double> cooldown) {
    }

    public record CombatState(List<ClassifiedEntity> entities, int bandits, int players, int hostiles, SpearState spear) {
        public CombatState {
            entities = List.copyOf(entities);
        }

        public static final String PLAYER = "PLAYER";
        public static final String HOSTILE = "HOSTILE";
        public static final String OTHER = "OTHER";

        public static boolean isBanditKind(String kind) {
            for (BanditClassifier.Kind k : BanditClassifier.Kind.values()) {
                if (k != BanditClassifier.Kind.NOT_BANDIT && k.name().equals(kind)) {
                    return true;
                }
            }
            return false;
        }
    }

    public record UiState(boolean screenOpen, @Nullable String title, String screenClass, boolean container, List<Raw.Slot> slots) {
        public UiState {
            slots = List.copyOf(slots);
        }
    }
}
