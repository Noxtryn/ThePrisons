package io.theprisons.testing;

import io.theprisons.core.cosmic.data.Raw;

import java.util.List;
import java.util.Map;

/** Small builders for synthetic {@link Raw.Frame}s (what a sensor would have read). */
public final class TestFrames {
    private TestFrames() {
    }

    public static Raw.Stack stack(String id, String name, List<String> lore) {
        return new Raw.Stack(id, name, 1, lore, 0, 0, "", "");
    }

    public static Raw.Stack stack(String id, String name, List<String> lore, String itemClass, String tier) {
        return new Raw.Stack(id, name, 1, lore, 0, 0, itemClass, tier);
    }

    public static Raw.Player player(String name, Raw.Stack held) {
        return new Raw.Player(name, 100.5, 64.0, -20.5, 0.0, 0.0, 0.1, 90.0F, 10.0F, 20.0F, 20.0F, 20, held, Raw.Stack.EMPTY,
                List.of(Raw.Stack.EMPTY, Raw.Stack.EMPTY, Raw.Stack.EMPTY, Raw.Stack.EMPTY), List.of(), Raw.Input.NONE, true, -1.0F);
    }

    public static Raw.Entity entity(int id, String type, String name, boolean player, boolean hostile, double distance) {
        return new Raw.Entity(id, type, name, name.toLowerCase(), player, hostile, 0, 0, 0, distance, 20.0F, Raw.Stack.EMPTY);
    }

    public static Raw.Frame frame(Raw.Player player, List<Raw.Entity> entities, List<String> sidebar) {
        return new Raw.Frame(100L, 1_000_000L, new Raw.Where("play.cosmicprisons.com", "minecraft:overworld", false), player, entities,
                new Raw.Blocks(4, 729, Map.of("#air", 400, "#solid", 320, "minecraft:diamond_ore", 9), true, true), sidebar, List.of(),
                List.of(), Raw.Screen.NONE, List.of());
    }

    public static Raw.Frame simple() {
        return frame(player("Steve", stack("minecraft:diamond_pickaxe", "Cosmic Pickaxe",
                List.of("Cosmic Energy", "||||||| 82.0%", "(242,159 / 293,135)"))), List.of(), List.of("Account Steve", "Guard XP Tax 10%"));
    }
}
