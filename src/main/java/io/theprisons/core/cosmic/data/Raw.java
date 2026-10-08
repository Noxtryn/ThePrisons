package io.theprisons.core.cosmic.data;

import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 * What the sensors read from the normal Minecraft client, as plain data (no Minecraft classes). Parsers, the snapshot builder,
 * captures and the replay tests all work on these records, so they run without a game.
 */
public final class Raw {
    private Raw() {
    }

    static <T> List<T> nz(@Nullable List<T> list) {
        return list == null ? List.of() : List.copyOf(list);
    }

    static String nz(@Nullable String text) {
        return text == null ? "" : text;
    }

    /**
     * An item stack.
     *
     * @param itemId    registry id ("minecraft:diamond_pickaxe")
     * @param name      display name, formatting stripped
     * @param lore      lore lines, formatting stripped
     * @param itemClass the mod's classification of the item ("satchel", "mask", ... ; "" = not recognised)
     * @param itemTier  the tier the classifier found ("" = none)
     */
    public record Stack(String itemId, String name, int count, List<String> lore, int damage, int maxDamage, String itemClass,
                        String itemTier) {
        public static final Stack EMPTY = new Stack("minecraft:air", "", 0, List.of(), 0, 0, "", "");

        public Stack {
            itemId = nz(itemId);
            name = nz(name);
            itemClass = nz(itemClass);
            itemTier = nz(itemTier);
            lore = nz(lore);
        }

        public boolean isEmpty() {
            return count <= 0 || "minecraft:air".equals(itemId);
        }

        /** Remaining durability share 0..1, or -1 when the item has none. */
        public double durabilityShare() {
            return maxDamage > 0 ? 1.0D - damage / (double) maxDamage : -1.0D;
        }
    }

    public record Slot(int index, Stack stack) {
    }

    public record Effect(String id, int amplifier, int ticksLeft) {
    }

    /** The movement / action keys as the player holds them (physical state, not what a macro presses). */
    public record Input(boolean forward, boolean back, boolean left, boolean right, boolean jump, boolean sneak, boolean sprint,
                        boolean attack, boolean use) {
        public static final Input NONE = new Input(false, false, false, false, false, false, false, false, false);

        public boolean anyMovement() {
            return forward || back || left || right || jump;
        }
    }

    public record Player(String name, double x, double y, double z, double vx, double vy, double vz, float yaw, float pitch,
                         float health, float maxHealth, int food, Stack held, Stack offHand, List<Stack> armor, List<Effect> effects,
                         Input input, boolean onGround, float spearCooldown) {
        public Player {
            name = nz(name);
            held = held == null ? Stack.EMPTY : held;
            offHand = offHand == null ? Stack.EMPTY : offHand;
            armor = nz(armor);
            effects = nz(effects);
            input = input == null ? Input.NONE : input;
        }
    }

    /**
     * A living entity near the player.
     *
     * @param type     entity type id ("minecraft:player", "minecraft:zombie")
     * @param name     own name, formatting stripped
     * @param shown    every other shown name (tab list, display name) lower case, space separated; "" = none
     * @param distance distance to the player in blocks
     */
    public record Entity(int id, String type, String name, String shown, boolean player, boolean hostile, double x, double y, double z,
                         double distance, float health, Stack held) {
        public Entity {
            type = nz(type);
            name = nz(name);
            shown = nz(shown);
            held = held == null ? Stack.EMPTY : held;
        }
    }

    /** How many blocks of each kind lie in the bounded cube around the player; {@code scanned} = blocks looked at. */
    public record Blocks(int radius, int scanned, java.util.Map<String, Integer> counts, boolean solidBelow, boolean headroom) {
        public Blocks {
            counts = counts == null ? java.util.Map.of() : java.util.Collections.unmodifiableMap(new java.util.TreeMap<>(counts));
        }
    }

    public record Screen(@Nullable String title, String screenClass, boolean container, List<Slot> slots) {
        public static final Screen NONE = new Screen(null, "", false, List.of());

        public Screen {
            screenClass = nz(screenClass);
            slots = nz(slots);
        }
    }

    /** A text line with the time it arrived (epoch ms). */
    public record Line(long atMs, String text) {
    }

    /** Where the player is. {@code server} is the address for Cosmic Prisons, "other" for every other server. */
    public record Where(String server, String dimension, boolean singleplayer) {
        public static final Where NONE = new Where("", "", false);

        public Where {
            server = nz(server);
            dimension = nz(dimension);
        }
    }

    /** Everything one sample reads. {@code null} lists mean "the sensor had nothing" (no sidebar, no world). */
    public record Frame(long tick, long nowMs, Where where, @Nullable Player player, List<Entity> entities, @Nullable Blocks blocks,
                        @Nullable List<String> sidebar, List<String> bossBars, List<Line> actionBar, Screen screen,
                        List<Stack> inventory) {
        public Frame {
            where = where == null ? Where.NONE : where;
            entities = nz(entities);
            bossBars = nz(bossBars);
            actionBar = nz(actionBar);
            sidebar = sidebar == null ? null : List.copyOf(sidebar);
            screen = screen == null ? Screen.NONE : screen;
            inventory = nz(inventory);
        }
    }
}
