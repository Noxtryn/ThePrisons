package io.theprisons.core.cosmic;

import io.theprisons.core.cosmic.data.CosmicContextSnapshot;
import io.theprisons.core.cosmic.data.Raw;
import io.theprisons.core.cosmic.data.SnapshotBuilder;
import io.theprisons.core.cosmic.model.CosmicGameModel;
import io.theprisons.core.cosmic.state.CosmicMemory;
import io.theprisons.core.cosmic.state.CosmicStateStore;
import io.theprisons.core.cosmic.value.Confidence;
import io.theprisons.modules.hud.CosmicStats;
import io.theprisons.testing.TestFrames;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SnapshotAndStoreTest {
    private static final CosmicGameModel MODEL = CosmicGameModel.loadDefault();

    private static CosmicContextSnapshot build(Raw.Frame frame) {
        return SnapshotBuilder.build(frame, SnapshotBuilder.Memory.NONE, MODEL);
    }

    @Test
    void snapshotFromAFrame() {
        CosmicContextSnapshot s = build(TestFrames.simple());
        assertEquals(CosmicContextSnapshot.SCHEMA, s.schema());
        assertTrue(s.player().present());
        assertEquals("minecraft:diamond_pickaxe", s.player().held().itemId());
        assertEquals(10.0D, s.cosmic().taxPercent().value());
        assertEquals(Confidence.VERIFIED_LIVE, s.cosmic().taxPercent().confidence());
        assertEquals(242_159L, s.cosmic().heldPickaxeEnergy().value());
        assertEquals(293_135L, s.cosmic().heldPickaxeCapacity().value());
        assertEquals("play.cosmicprisons.com|minecraft:overworld|unknown", s.key().asText());
        assertFalse(s.cosmic().season().isKnown());
        assertFalse(s.cosmic().mine().isKnown());
    }

    @Test
    void sameFrameGivesTheSameSnapshot() {
        Raw.Frame frame = TestFrames.simple();
        assertEquals(build(frame), build(frame));
    }

    @Test
    void missingScoreboardIsUnknownNotZero() {
        Raw.Frame frame = TestFrames.frame(TestFrames.player("Steve", Raw.Stack.EMPTY), List.of(), null);
        CosmicContextSnapshot s = build(frame);
        assertFalse(s.cosmic().sidebarPresent());
        assertFalse(s.cosmic().taxPercent().isKnown());
        assertFalse(s.cosmic().energyNow().isKnown());
        assertFalse(s.cosmic().totalXp().isKnown());
        assertTrue(s.cosmic().sidebar().isEmpty());
    }

    @Test
    void emptyInventoryAndNoPlayer() {
        Raw.Frame noPlayer = new Raw.Frame(1, 1, Raw.Where.NONE, null, List.of(), null, null, List.of(), List.of(), Raw.Screen.NONE, List.of());
        CosmicContextSnapshot s = build(noPlayer);
        assertFalse(s.player().present());
        assertFalse(s.world().loaded());
        assertEquals(0, s.combat().entities().size());
        assertFalse(s.combat().spear().recognised().isKnown(), "no player = unknown, not 'no spear'");
        assertEquals(CosmicContextSnapshot.ContextKey.NONE, s.key());
        Raw.Frame emptyHands = TestFrames.frame(TestFrames.player("Steve", Raw.Stack.EMPTY), List.of(), List.of());
        CosmicContextSnapshot e = build(emptyHands);
        assertFalse(e.cosmic().heldPickaxeEnergy().isKnown());
        assertEquals(Boolean.FALSE, e.combat().spear().recognised().value());
    }

    @Test
    void entityClassification() {
        List<Raw.Entity> entities = List.of(
                TestFrames.entity(1, "minecraft:player", "bandit_ae_821e4c", true, false, 12.0),
                TestFrames.entity(2, "minecraft:player", "Alex", true, false, 5.0),
                TestFrames.entity(3, "minecraft:zombie", "Zombie", false, true, 8.0),
                TestFrames.entity(4, "minecraft:cow", "Cow", false, false, 3.0),
                TestFrames.entity(5, "minecraft:player", "Gold Bandit", true, false, 20.0),
                TestFrames.entity(6, "minecraft:villager", "", false, false, 30.0));
        CosmicContextSnapshot s = build(TestFrames.frame(TestFrames.player("Steve", Raw.Stack.EMPTY), entities, List.of()));
        assertEquals(2, s.combat().bandits());
        assertEquals(1, s.combat().players());
        assertEquals(1, s.combat().hostiles());
        // sorted by distance, nearest first
        assertEquals(List.of(4, 2, 3, 1, 5, 6), s.combat().entities().stream().map(e -> e.entity().id()).toList());
        assertEquals("OTHER", s.combat().entities().get(0).kind());
        assertEquals("PLAYER", s.combat().entities().get(1).kind());
        assertEquals("ORE_BANDIT", s.combat().entities().get(3).kind());
        assertEquals("SPECIAL", s.combat().entities().get(4).kind());
        assertTrue(CosmicContextSnapshot.CombatState.isBanditKind("ORE_BANDIT"));
        assertFalse(CosmicContextSnapshot.CombatState.isBanditKind("PLAYER"));
    }

    @Test
    void unknownEntityTypesAreOtherNotCrashes() {
        Raw.Entity weird = new Raw.Entity(9, null, null, null, false, false, 0, 0, 0, 1.0, 0.0F, null);
        CosmicContextSnapshot s = build(TestFrames.frame(TestFrames.player("Steve", Raw.Stack.EMPTY), List.of(weird), List.of()));
        assertEquals("OTHER", s.combat().entities().get(0).kind());
    }

    @Test
    void entityListIsBounded() {
        List<Raw.Entity> many = new ArrayList<>();
        for (int i = 0; i < 300; i++) {
            many.add(TestFrames.entity(i, "minecraft:zombie", "Zombie", false, true, 300 - i));
        }
        CosmicContextSnapshot s = build(TestFrames.frame(TestFrames.player("Steve", Raw.Stack.EMPTY), many, List.of()));
        assertEquals(SnapshotBuilder.MAX_ENTITIES, s.combat().entities().size());
        assertEquals(300, s.world().nearbyEntities());
        assertEquals(1.0D, s.combat().entities().get(0).entity().distance(), "the nearest are kept");
    }

    @Test
    void manualInputIsVisible() {
        Raw.Player p = TestFrames.player("Steve", Raw.Stack.EMPTY);
        Raw.Player walking = new Raw.Player(p.name(), p.x(), p.y(), p.z(), p.vx(), p.vy(), p.vz(), p.yaw(), p.pitch(), p.health(), p.maxHealth(),
                p.food(), p.held(), p.offHand(), p.armor(), p.effects(), new Raw.Input(true, false, false, false, false, false, false, false, false),
                p.onGround(), -1.0F);
        assertTrue(build(TestFrames.frame(walking, List.of(), List.of())).player().manualInput());
        assertFalse(build(TestFrames.frame(p, List.of(), List.of())).player().manualInput());
    }

    @Test
    void spearCooldownOnlyWhenASpearIsHeld() {
        Raw.Player p = TestFrames.player("Steve", TestFrames.stack("minecraft:trident", "Spear", List.of()));
        Raw.Player cooling = new Raw.Player(p.name(), p.x(), p.y(), p.z(), p.vx(), p.vy(), p.vz(), p.yaw(), p.pitch(), p.health(), p.maxHealth(),
                p.food(), p.held(), p.offHand(), p.armor(), p.effects(), p.input(), p.onGround(), 0.4F);
        CosmicContextSnapshot s = build(TestFrames.frame(cooling, List.of(), List.of()));
        assertEquals(Boolean.TRUE, s.combat().spear().recognised().value());
        assertEquals(0.4D, s.combat().spear().cooldown().value(), 1e-6);
        CosmicContextSnapshot none = build(TestFrames.frame(p, List.of(), List.of()));
        assertFalse(none.combat().spear().cooldown().isKnown(), "cooldown -1 = not readable");
    }

    @Test
    void snapshotsAreImmutable() {
        CosmicContextSnapshot s = build(TestFrames.simple());
        assertThrows(UnsupportedOperationException.class, () -> s.cosmic().sidebar().add("x"));
        assertThrows(UnsupportedOperationException.class, () -> s.combat().entities().clear());
        assertThrows(UnsupportedOperationException.class, () -> s.player().armor().add(Raw.Stack.EMPTY));
    }

    @Test
    void malformedLoreDoesNotBreakTheSnapshot() {
        Raw.Frame frame = TestFrames.frame(TestFrames.player("Steve", TestFrames.stack("minecraft:diamond_pickaxe", "Pickaxe",
                List.of("Cosmic Energy", "(x / y)", "(,, / ,,)"))), List.of(), List.of());
        CosmicContextSnapshot s = build(frame);
        assertFalse(s.cosmic().heldPickaxeEnergy().isKnown());
    }

    // ── memory (zone, event) ─────────────────────────────────────────────────

    @Test
    void zoneAndEventComeFromMessages() {
        CosmicMemory memory = new CosmicMemory();
        memory.onMessage("You entered a Diamond Zone", false, false, 1_000L);
        memory.onMessage("A meteor has crashed at spawn!", false, false, 2_000L);
        assertEquals("diamond", memory.zone());
        assertEquals("meteor", memory.event(3_000L));
        assertNull(memory.event(2_000L + 11 * 60_000L), "the event expires");
        CosmicContextSnapshot s = SnapshotBuilder.build(TestFrames.simple(), new SnapshotBuilder.Memory(memory.zone(), "meteor", List.of()), MODEL);
        assertEquals("diamond", s.cosmic().zone().value());
        assertEquals(Confidence.OBSERVED, s.cosmic().zone().confidence(), "a zone from a past message is observed, not live");
    }

    @Test
    void playerChatAndPrivateMessagesAreNotStored() {
        CosmicMemory memory = new CosmicMemory();
        memory.onMessage("<Steve> my password is hunter2", false, true, 1L);
        memory.onMessage("[Alex -> me] secret plan", false, false, 2L);
        memory.onMessage("access_token: abcdef123456", true, false, 3L);
        memory.onMessage("Sold 3 diamonds", false, false, 4L);
        List<String> stored = new ArrayList<>();
        memory.systemLines().forEach(l -> stored.add(l.text()));
        memory.actionBar().forEach(l -> stored.add(l.text()));
        assertEquals(2, stored.size(), stored.toString());
        assertTrue(stored.contains("Sold 3 diamonds"));
        assertFalse(stored.stream().anyMatch(t -> t.contains("hunter2") || t.contains("secret plan") || t.contains("abcdef123456")));
    }

    @Test
    void memoryRingsAreBounded() {
        CosmicMemory memory = new CosmicMemory();
        for (int i = 0; i < 500; i++) {
            memory.onMessage("line " + i, i % 2 == 0, false, i);
        }
        assertTrue(memory.systemLines().size() <= CosmicMemory.SYSTEM_LINES);
        assertTrue(memory.actionBar().size() <= CosmicMemory.ACTION_BAR_LINES);
    }

    @Test
    void worldChangeKeepsTheZoneLikeTheOldStaticDid() {
        CosmicMemory memory = new CosmicMemory();
        memory.onMessage("You entered a Diamond Zone", false, false, 1L);
        memory.onMessage("Sold 3 diamonds", false, false, 2L);
        memory.onWorldChanged();
        assertEquals("diamond", memory.zone());
        assertTrue(memory.systemLines().isEmpty());
        memory.reset();
        assertEquals("", memory.zone());
    }

    // ── store ────────────────────────────────────────────────────────────────

    @Test
    void storeKeepsTheLatestAndABoundedHistory() {
        CosmicStateStore store = new CosmicStateStore();
        assertNull(store.latest());
        CosmicContextSnapshot s = build(TestFrames.simple());
        for (int i = 0; i < CosmicStateStore.HISTORY + 50; i++) {
            store.publish(s);
        }
        assertSame(s, store.latest());
        assertEquals(CosmicStateStore.HISTORY, store.historySize());
    }

    @Test
    void storeStartsANewHistoryWhenTheContextChanges() {
        CosmicStateStore store = new CosmicStateStore();
        store.publish(build(TestFrames.simple()));
        store.publish(build(TestFrames.simple()));
        assertEquals(2, store.historySize());
        Raw.Frame other = new Raw.Frame(5, 5, new Raw.Where("other-123abc", "minecraft:the_nether", false), TestFrames.simple().player(),
                List.of(), null, List.of(), List.of(), List.of(), Raw.Screen.NONE, List.of());
        store.publish(build(other));
        assertEquals(1, store.historySize(), "another server / dimension: nothing of the old context stays");
    }

    @Test
    void resetOnWorldChangeAndDisconnect() {
        CosmicStateStore store = new CosmicStateStore();
        long before = store.version();
        store.publish(build(TestFrames.simple()));
        store.reset();
        assertNull(store.latest());
        assertEquals(0, store.historySize());
        assertTrue(store.version() > before);
    }

    @Test
    void listenersAreCalledOncePerSnapshot() {
        CosmicStateStore store = new CosmicStateStore();
        List<CosmicContextSnapshot> seen = new ArrayList<>();
        store.subscribe(seen::add);
        store.publish(build(TestFrames.simple()));
        assertEquals(1, seen.size());
        assertNotNull(seen.get(0));
    }

    // ── a latent crash the shared reader exposed ─────────────────────────────

    @Test
    void taxLineWithoutANumberAtTheEndOfTheSidebarDoesNotThrow() {
        CosmicStats stats = new CosmicStats(0L, line -> {
        });
        stats.sidebar(List.of("Account Steve", "Guard XP Tax"), 1_000L); // used to throw IllegalStateException: No match found
        assertTrue(true);
    }
}
