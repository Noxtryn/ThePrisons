package io.theprisons.core.cosmic;

import io.theprisons.core.cosmic.capture.Capture;
import io.theprisons.core.cosmic.capture.CaptureBuilder;
import io.theprisons.core.cosmic.capture.CaptureIO;
import io.theprisons.core.cosmic.data.Raw;
import io.theprisons.core.cosmic.model.CosmicGameModel;
import io.theprisons.testing.CosmicFixtures;
import io.theprisons.testing.TestFrames;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Writes the SYNTHETIC starter fixtures (hand-made frames in the formats known from the game's text, flagged
 * {@code "synthetic": true}). They are not recordings: real ones from capture mode replace them. Run once with
 * {@code ./gradlew test --tests '*FixtureGenerator' -Dcosmic.fixtures.write=true}; normal test runs skip this class.
 */
@EnabledIfSystemProperty(named = "cosmic.fixtures.write", matches = "true")
class FixtureGenerator {
    private static final CosmicGameModel MODEL = CosmicGameModel.loadDefault();

    private static void write(String category, String name, String note, Raw.Frame frame, String zone, String event, List<Raw.Line> system) throws IOException {
        Capture built = CaptureBuilder.build(frame, zone, event, system, MODEL, "synthetic", "1.21.11", note, category);
        Capture.Meta meta = new Capture.Meta("synthetic", "synthetic", "1.21.11", note, true, category);
        Capture capture = new Capture(built.schema(), built.kind(), meta, built.frame(), built.memory(), built.expected());
        Path file = Path.of(System.getProperty("user.dir"), "src/test/resources/cosmic", category, name + ".json");
        CaptureIO.write(file, capture);
    }

    @Test
    void generate() throws IOException {
        // pickaxes: the current lore format, and the older one-line format
        write("pickaxes", "cosmic_pickaxe_energy", "Lore format 'Cosmic Energy' / bar / '(now / capacity)' with a Battery block below that must not count",
                TestFrames.frame(TestFrames.player("Steve", TestFrames.stack("minecraft:diamond_pickaxe", "Cosmic Pickaxe",
                        List.of("Fractured III", "Shatter II", "Cosmic Energy", "||||||||||| 82.0%", "(242,159 / 293,135)", "Battery", "(10 / 100)"),
                        "pickaxe", "")), List.of(), List.of("Account Steve", "Level", "95 (54,186,166 XP)", "Guard XP Tax", "10%", "Cosmic Energy",
                        "34% (level 71)", "(456,410 / 1,259,523)")), "mine", null, List.of());
        write("pickaxes", "old_energy_line", "Older one-line lore format 'Energy: now / capacity'",
                TestFrames.frame(TestFrames.player("Steve", TestFrames.stack("minecraft:iron_pickaxe", "Pickaxe", List.of("Energy: 1,234 / 50,000"), "pickaxe", "")),
                        List.of(), List.of()), "", null, List.of());
        write("pickaxes", "charge_orb_bonus_is_not_energy", "A charge orb bonus line must not be read as the pickaxe's energy",
                TestFrames.frame(TestFrames.player("Steve", TestFrames.stack("minecraft:diamond_pickaxe", "Cosmic Pickaxe",
                        List.of("+34% Energy Gain from 6 Charge Orbs"), "pickaxe", "")), List.of(), List.of()), "", null, List.of());
        // bandits: a bandit among players and mobs
        write("bandits", "ore_bandit_near_players", "A fake-player ore bandit, a real player and a zombie around the player",
                TestFrames.frame(TestFrames.player("Steve", TestFrames.stack("minecraft:trident", "Spear", List.of(), "spear", "")), List.of(
                        TestFrames.entity(1, "minecraft:player", "bandit_ae_821e4c", true, false, 9.0),
                        TestFrames.entity(2, "minecraft:player", "Alex", true, false, 14.0),
                        TestFrames.entity(3, "minecraft:zombie", "Zombie", false, true, 6.0),
                        TestFrames.entity(4, "minecraft:player", "Gold Bandit", true, false, 25.0)), List.of("Account Steve")), "mine", null, List.of());
        // spears
        Raw.Player me = TestFrames.player("Steve", TestFrames.stack("minecraft:trident", "Spear", List.of(), "spear", ""));
        Raw.Player cooling = new Raw.Player(me.name(), me.x(), me.y(), me.z(), me.vx(), me.vy(), me.vz(), me.yaw(), me.pitch(), me.health(), me.maxHealth(),
                me.food(), me.held(), me.offHand(), me.armor(), me.effects(), me.input(), me.onGround(), 0.4F);
        write("spears", "spear_on_cooldown", "A spear in the hand with a readable item cooldown of 0.4",
                TestFrames.frame(cooling, List.of(), List.of()), "", null, List.of());
        // zones
        write("zones", "diamond_zone_entered", "The chat line 'You entered a Diamond Zone' sets the zone",
                TestFrames.frame(TestFrames.player("Steve", Raw.Stack.EMPTY), List.of(), List.of("Account Steve", "Guard XP Tax 9%")), "diamond", null,
                List.of(new Raw.Line(1L, "(!) You entered a Diamond Zone")));
        write("zones", "unknown_zone_name", "A zone that is not in the registry stays a zone but the registry returns the unknown entry",
                TestFrames.frame(TestFrames.player("Steve", Raw.Stack.EMPTY), List.of(), List.of()), "frozen", null, List.of());
        // events
        write("events", "meteor_crashed", "A meteor announcement in the system lines",
                TestFrames.frame(TestFrames.player("Steve", Raw.Stack.EMPTY), List.of(), List.of()), "spawn", "meteor",
                List.of(new Raw.Line(1L, "(!) A meteor has crashed at spawn!")));
        // items: a satchel and an item the classifier does not know
        Raw.Frame items = TestFrames.frame(TestFrames.player("Steve", Raw.Stack.EMPTY), List.of(), List.of());
        write("items", "satchel_and_unknown_item", "A recognised satchel and an unrecognised item side by side in a container screen",
                new Raw.Frame(items.tick(), items.nowMs(), items.where(), items.player(), List.of(), null, List.of(), List.of(), List.of(),
                        new Raw.Screen("Private Vaults", "GenericContainerScreen", true, List.of(
                                new Raw.Slot(0, TestFrames.stack("minecraft:chest", "Diamond Ore Satchel 25 (7,130 / 57,600 Ores)", List.of("Automatically collects Diamond Ore"), "satchel", "")),
                                new Raw.Slot(1, TestFrames.stack("minecraft:stick", "Mystery Thing", List.of("???"), "", "")))), List.of()),
                "", null, List.of());
        // categories we have no capture for yet: only a README so the folders exist
        for (String category : CosmicFixtures.CATEGORIES) {
            Path dir = Path.of(System.getProperty("user.dir"), "src/test/resources/cosmic", category);
            Files.createDirectories(dir);
            Path readme = dir.resolve("README.md");
            if (!Files.exists(readme)) {
                Files.writeString(readme, "# cosmic/" + category + "\n\nCapture fixtures for `" + category + "`. Record one in the game with `/prisons capture <note>` "
                        + "(capture mode, see docs/cosmic/CAPTURE.md), drop the JSON file here and `CosmicFixtureReplayTest` replays it.\n"
                        + "Files with `\"synthetic\": true` are hand-made starters, not recordings.\n");
            }
        }
        Files.writeString(Path.of(System.getProperty("user.dir"), "src/test/resources/cosmic/README.md"),
                "# Cosmic fixtures\n\nOne folder per topic: " + String.join(", ", CosmicFixtures.CATEGORIES) + ".\n"
                        + "Every `*.json` is a capture (schema " + Capture.SCHEMA + ") that `CosmicFixtureReplayTest` feeds through the current parsers and compares with"
                        + " the result recorded in the file.\n\nStarter files marked `\"synthetic\": true` are hand-made from formats known from the game text; "
                        + "replace them with real captures as you collect them. Map: " + Map.of("ores", "ore blocks around the player", "masks", "mask items",
                        "ah", "auction house screens (market module is off)", "energy-extractor", "energy extractor menus (module off)") + ".\n");
    }
}
