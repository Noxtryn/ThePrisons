package io.theprisons.core.cosmic;

import com.google.gson.JsonObject;
import io.theprisons.core.cosmic.model.CosmicGameModel;
import io.theprisons.core.cosmic.model.Entry;
import io.theprisons.core.cosmic.model.Knowledge;
import io.theprisons.core.cosmic.model.KnowledgeRegistry;
import io.theprisons.core.cosmic.model.RegistryData;
import io.theprisons.core.cosmic.value.Confidence;
import io.theprisons.core.cosmic.value.GameValue;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModelTest {
    private static final CosmicGameModel MODEL = CosmicGameModel.loadDefault();

    private static java.io.InputStream json(String text) {
        return new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void shippedDataLoadsAndEveryRegistryHasEntries() {
        assertTrue(MODEL.ores().size() >= 7);
        assertTrue(MODEL.pickaxes().size() >= 1);
        assertTrue(MODEL.enchants().size() >= 1);
        assertTrue(MODEL.bandits().size() >= 4);
        assertTrue(MODEL.zones().size() >= 4);
        assertTrue(MODEL.items().size() > 20);
    }

    @Test
    void unconfirmedGameValuesStayUnknown() {
        // The owner has not given these numbers: nothing may invent them.
        assertFalse(MODEL.mining().value("speedRequirement").isKnown());
        assertFalse(MODEL.banditsArea().value("aggroRange").isKnown());
        assertFalse(MODEL.weapons().value("spearJabCooldownSeconds").isKnown());
        assertFalse(MODEL.season().value("current").isKnown());
        assertTrue(MODEL.mining().value("speedRequirement", Double.class).asDouble(Confidence.OBSERVED).isEmpty(), "unknown never reads as 0");
        assertFalse(MODEL.mining().value("noSuchKey").isKnown());
    }

    @Test
    void whatTheRepositoryHasSeenIsMarkedObservedNotVerified() {
        GameValue<Long> countdown = MODEL.knowledge().get("travel.teleportCountdownSeconds", Long.class);
        assertEquals(18L, countdown.value());
        assertEquals(Confidence.OBSERVED, countdown.confidence());
        assertFalse(countdown.source().isEmpty());
    }

    @Test
    void orePickaxeAndZoneLookups() {
        assertEquals("redstone", MODEL.ores().forBlock("minecraft:deepslate_redstone_ore").id());
        assertEquals("diamond", MODEL.ores().forBlock("minecraft:diamond_ore").id());
        assertTrue(MODEL.ores().forBlock("minecraft:stone").isPlaceholder(), "stone is no ore");
        assertTrue(MODEL.ores().forBlock("minecraft:diamond_ore").attribute("energyPerOre").confidence() == Confidence.UNKNOWN);
        assertTrue(MODEL.pickaxes().forItem("minecraft:diamond_pickaxe").isPresent());
        assertTrue(MODEL.pickaxes().forItem("minecraft:diamond_sword").isEmpty());
        assertEquals("diamond", MODEL.zones().forZone("Diamond").id());
        assertEquals("unknown", MODEL.zones().forZone("not-a-zone").id());
        assertEquals("unknown", MODEL.zones().forZone("").id());
        assertEquals(Boolean.FALSE, MODEL.zones().forZone("badlands").attribute("marketEnabled", Boolean.class).value());
        assertFalse(MODEL.zones().forZone("bandit_lands").attribute("sameAsBadlands").isKnown(), "owner's term, link to Badlands unverified");
    }

    @Test
    void banditAndItemRegistries() {
        assertTrue(MODEL.bandits().forKind("ORE_BANDIT").attribute("identification").isKnown());
        assertFalse(MODEL.bandits().forKind("ELITE").attribute("identification").isKnown(), "no confirmed way to tell elite bandits apart");
        assertEquals("satchel", MODEL.items().forClass("satchel").id());
        assertEquals("unknown_cosmic", MODEL.items().forClass("").id());
        assertEquals("unknown_cosmic", MODEL.items().forClass("brand-new-thing").id());
        assertEquals("fractured", MODEL.enchants().forLoreLine("Fractured III").orElseThrow().id());
        assertTrue(MODEL.enchants().forLoreLine("Efficiency VI").isEmpty());
    }

    @Test
    void emptyModelKnowsNothing() {
        CosmicGameModel empty = CosmicGameModel.empty();
        assertFalse(empty.mining().value("speedRequirement").isKnown());
        assertEquals("unknown", empty.zones().forZone("diamond").id());
        assertTrue(empty.ores().forBlock("minecraft:diamond_ore").isPlaceholder());
    }

    // ── data errors must be loud, not guessed ───────────────────────────────

    @Test
    void unknownConfidenceNameIsAnError() {
        String bad = "{\"schema\":1,\"values\":[{\"key\":\"a.b\",\"value\":1,\"confidence\":\"PROBABLY\"}]}";
        assertThrows(IllegalArgumentException.class, () -> Knowledge.load(json(bad), "test"));
    }

    @Test
    void aValueNeedsARealConfidence() {
        String bad = "{\"schema\":1,\"values\":[{\"key\":\"a.b\",\"value\":1}]}";
        assertThrows(IllegalStateException.class, () -> Knowledge.load(json(bad), "test"));
    }

    @Test
    void wrongSchemaAndDuplicatesAreErrors() {
        assertThrows(IllegalStateException.class, () -> RegistryData.read(json("{\"schema\":99}"), "x"));
        assertThrows(IllegalStateException.class, () -> RegistryData.read(json("{}"), "x"));
        List<Entry> twice = KnowledgeRegistry.load(json("{\"schema\":1,\"entries\":[{\"id\":\"a\"},{\"id\":\"a\"}]}"), "x");
        assertThrows(IllegalStateException.class, () -> new KnowledgeRegistry("x", twice));
    }

    @Test
    void numbersAndListsKeepTheirTypes() {
        JsonObject root = RegistryData.read(json("{\"schema\":1,\"entries\":[{\"id\":\"a\",\"attributes\":{\"n\":{\"value\":3,\"confidence\":\"OBSERVED\"},"
                + "\"d\":{\"value\":2.5,\"confidence\":\"OBSERVED\"},\"l\":{\"value\":[\"x\",\"y\"],\"confidence\":\"OBSERVED\"},"
                + "\"u\":{\"confidence\":\"UNKNOWN\"}}}]}"), "x");
        Entry e = RegistryData.entries(root, "x").get(0);
        assertEquals(3L, e.attribute("n", Long.class).value());
        assertEquals(3.0D, e.attribute("n", Double.class).value());
        assertEquals(2.5D, e.attribute("d", Double.class).value());
        assertFalse(e.attribute("d", Long.class).isKnown(), "2.5 is no whole number");
        assertEquals(List.of("x", "y"), e.attribute("l").value());
        assertFalse(e.attribute("u").isKnown());
        assertFalse(e.attribute("missing").isKnown());
        assertFalse(e.attribute("l", Long.class).isKnown(), "wrong type is unknown, not a crash");
    }
}
