package io.theprisons.core.cosmic;

import io.theprisons.core.cosmic.capture.Capture;
import io.theprisons.core.cosmic.capture.CaptureIO;
import io.theprisons.core.cosmic.capture.Replay;
import io.theprisons.core.cosmic.model.CosmicGameModel;
import io.theprisons.testing.CosmicFixtures;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Replays every fixture under src/test/resources/cosmic: parsers are tested without a game and without the server. */
class CosmicFixtureReplayTest {
    private static final CosmicGameModel MODEL = CosmicGameModel.loadDefault();

    @Test
    void everyFixtureReplaysToItsRecordedResult() throws Exception {
        List<Path> files = CosmicFixtures.all();
        assertFalse(files.isEmpty(), "no fixtures found");
        for (Path file : files) {
            Capture capture = CaptureIO.read(file);
            Replay.Result result = Replay.run(capture, MODEL);
            assertTrue(result.matches(), file.getFileName() + ": " + result.differences());
        }
    }

    @Test
    void everyFixtureFolderExists() {
        for (String category : CosmicFixtures.CATEGORIES) {
            assertTrue(CosmicFixtures.root().resolve(category).toFile().isDirectory(), category);
        }
    }

    @Test
    void pickaxeFixturesReadTheEnergyThatIsThere() throws Exception {
        var cosmic = Replay.run(CosmicFixtures.load("pickaxes", "cosmic_pickaxe_energy"), MODEL).snapshot().cosmic();
        assertEquals(242_159L, cosmic.heldPickaxeEnergy().value(), "the Battery block below must not be read");
        assertEquals(293_135L, cosmic.heldPickaxeCapacity().value());
        assertEquals(456_410L, cosmic.energyNow().value(), "sidebar energy is a separate reading");
        assertEquals(1234L, Replay.run(CosmicFixtures.load("pickaxes", "old_energy_line"), MODEL).snapshot().cosmic().heldPickaxeEnergy().value());
        assertFalse(Replay.run(CosmicFixtures.load("pickaxes", "charge_orb_bonus_is_not_energy"), MODEL).snapshot().cosmic().heldPickaxeEnergy().isKnown());
    }

    @Test
    void banditFixtureFindsTheBandits() throws Exception {
        var combat = Replay.run(CosmicFixtures.load("bandits", "ore_bandit_near_players"), MODEL).snapshot().combat();
        assertEquals(2, combat.bandits(), "the fake player and the 'Gold Bandit'");
        assertEquals(1, combat.players());
        assertEquals(1, combat.hostiles());
        assertEquals(Boolean.TRUE, combat.spear().recognised().value());
    }

    @Test
    void spearZoneAndEventFixtures() throws Exception {
        assertEquals(0.4D, Replay.run(CosmicFixtures.load("spears", "spear_on_cooldown"), MODEL).snapshot().combat().spear().cooldown().value(), 1e-6);
        assertEquals("diamond", Replay.run(CosmicFixtures.load("zones", "diamond_zone_entered"), MODEL).snapshot().cosmic().zone().value());
        assertEquals("meteor", Replay.run(CosmicFixtures.load("events", "meteor_crashed"), MODEL).snapshot().cosmic().event().value());
        assertEquals("unknown", MODEL.zones().forZone(Replay.run(CosmicFixtures.load("zones", "unknown_zone_name"), MODEL).snapshot().cosmic().zone().value()).id());
    }

    @Test
    void itemFixtureKeepsUnknownItemsUnknown() throws Exception {
        var slots = Replay.run(CosmicFixtures.load("items", "satchel_and_unknown_item"), MODEL).snapshot().ui().slots();
        assertEquals("satchel", MODEL.items().forClass(slots.get(0).stack().itemClass()).id());
        assertEquals("unknown_cosmic", MODEL.items().forClass(slots.get(1).stack().itemClass()).id());
    }

    @Test
    void syntheticFixturesAreMarkedAsSuch() throws Exception {
        for (Path file : CosmicFixtures.all()) {
            Capture c = CaptureIO.read(file);
            if (c.meta().createdAt().equals("synthetic")) {
                assertTrue(c.meta().synthetic(), file.getFileName() + " claims no recording but is not marked synthetic");
            }
        }
    }
}
