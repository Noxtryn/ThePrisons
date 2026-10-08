package io.theprisons.core.cosmic;

import io.theprisons.core.cosmic.capture.Capture;
import io.theprisons.core.cosmic.capture.CaptureBuilder;
import io.theprisons.core.cosmic.capture.CaptureException;
import io.theprisons.core.cosmic.capture.CaptureIO;
import io.theprisons.core.cosmic.capture.Replay;
import io.theprisons.core.cosmic.data.Raw;
import io.theprisons.core.cosmic.model.CosmicGameModel;
import io.theprisons.testing.TestFrames;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CaptureTest {
    private static final CosmicGameModel MODEL = CosmicGameModel.loadDefault();
    private static final String JWT = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.dBjftJeZ4CVPmB92K27uhbUJU1p1r";

    private static Raw.Frame busyFrame() {
        Raw.Stack pick = TestFrames.stack("minecraft:diamond_pickaxe", "Steve's Pickaxe",
                List.of("Owner: Steve", "Cosmic Energy", "|||| 82.0%", "(242,159 / 293,135)", "token " + JWT));
        Raw.Player me = TestFrames.player("Steve", pick);
        List<Raw.Entity> entities = List.of(
                TestFrames.entity(1, "minecraft:player", "bandit_ae_821e4c", true, false, 10),
                TestFrames.entity(2, "minecraft:player", "AlexTheGreat", true, false, 6),
                TestFrames.entity(3, "minecraft:player", "Alex", true, false, 7));
        Raw.Screen screen = new Raw.Screen("Steve's vaults", "GenericContainerScreen", true,
                List.of(new Raw.Slot(0, TestFrames.stack("minecraft:chest", "AlexTheGreat's chest", List.of("Sold to AlexTheGreat at 10.1.2.3")))));
        return new Raw.Frame(7, 7_000L, new Raw.Where("play.cosmicprisons.com", "minecraft:overworld", false), me, entities, null,
                List.of("Account Steve", "Guard XP Tax 10%", "contact steve@example.com"), List.of("Boss bar for Alex"),
                List.of(new Raw.Line(1L, "access_token: SECRET123456")), screen, List.of(pick));
    }

    private static Capture capture(Raw.Frame frame) {
        return CaptureBuilder.build(frame, "diamond", null,
                List.of(new Raw.Line(1L, "[AlexTheGreat -> me] pst"), new Raw.Line(2L, "AlexTheGreat sold 3 diamonds"), new Raw.Line(3L, "Welcome Steve")),
                MODEL, "1.2.1", "1.21.11", "my note with 192.168.0.9", "test");
    }

    @Test
    void namesAreAnonymised() {
        String json = CaptureIO.toJson(capture(busyFrame()));
        assertFalse(json.contains("Steve"), "own name");
        assertFalse(json.contains("AlexTheGreat"), "other player");
        assertFalse(json.contains("\"Alex\""), "other player");
        assertTrue(json.contains("Self"));
        assertTrue(json.contains("Player_1"));
        assertTrue(json.contains("Player_2"));
        assertTrue(json.contains("bandit_ae_821e4c"), "bandits are the mechanic, they stay");
    }

    @Test
    void anythingCalledBanditKeepsItsName() {
        Raw.Frame f = TestFrames.frame(TestFrames.player("Steve", Raw.Stack.EMPTY), List.of(
                TestFrames.entity(1, "minecraft:player", "Gold Bandit", true, false, 5), TestFrames.entity(2, "minecraft:player", "Bob", true, false, 6)), List.of());
        Capture c = capture(f);
        assertEquals("Gold Bandit", c.frame().entities().get(0).name());
        assertEquals("Player_1", c.frame().entities().get(1).name());
        assertEquals(1, Replay.run(c, MODEL).snapshot().combat().bandits());
    }

    @Test
    void sameNameSameAlias() {
        Capture c = capture(busyFrame());
        List<Raw.Entity> e = c.frame().entities();
        assertEquals("bandit_ae_821e4c", e.get(0).name());
        assertEquals("Player_1", e.get(1).name());
        assertEquals("Player_2", e.get(2).name());
        assertEquals("Self", c.frame().player().name());
        assertTrue(c.frame().screen().title().startsWith("Self"));
        assertTrue(c.frame().screen().slots().get(0).stack().name().startsWith("Player_1"));
    }

    @Test
    void secretsNeverReachTheFile() {
        String json = CaptureIO.toJson(capture(busyFrame()));
        assertFalse(json.contains("eyJhbGci"), "token in lore");
        assertFalse(json.contains("SECRET123456"), "token in the action bar");
        assertFalse(json.contains("192.168.0.9"), "address in the note");
        assertFalse(json.contains("10.1.2.3"), "address in a slot lore");
        assertFalse(json.contains("steve@example.com"), "e-mail in the sidebar");
        assertFalse(json.contains("pst"), "private message");
        assertTrue(json.contains(io.theprisons.core.cosmic.parse.PrivacyFilter.REDACTED));
        assertTrue(json.contains("sold 3 diamonds"), "ordinary system lines stay");
    }

    @Test
    void theCaptureDoesNotReadAnythingBeyondTheFrame() {
        // The format has exactly these top-level parts; there is no place for account, launcher or network data.
        com.google.gson.JsonObject root = com.google.gson.JsonParser.parseString(CaptureIO.toJson(capture(busyFrame()))).getAsJsonObject();
        assertEquals(java.util.Set.of("schema", "kind", "meta", "frame", "memory", "expected"), root.keySet());
        assertEquals(java.util.Set.of("createdAt", "modVersion", "minecraftVersion", "note", "synthetic", "category"),
                root.getAsJsonObject("meta").keySet());
    }

    @Test
    void roundTripAndReplayMatch() throws CaptureException {
        Capture c = capture(busyFrame());
        Capture back = CaptureIO.fromJson(CaptureIO.toJson(c));
        assertEquals(c, back);
        Replay.Result result = Replay.run(back, MODEL);
        assertTrue(result.matches(), result.differences().toString());
        assertEquals(10.0D, result.snapshot().cosmic().taxPercent().value());
        assertEquals("diamond", result.snapshot().cosmic().zone().value());
    }

    @Test
    void replayNoticesAChangedParser() throws CaptureException {
        Capture c = capture(busyFrame());
        Map<String, String> tampered = new HashMap<>(c.expected());
        tampered.put("cosmic.taxPercent", "99.0 [VERIFIED_LIVE, sidebar tax line]");
        tampered.put("combat.bandits", "7");
        Capture changed = new Capture(c.schema(), c.kind(), c.meta(), c.frame(), c.memory(), tampered);
        Replay.Result result = Replay.run(changed, MODEL);
        assertFalse(result.matches());
        assertEquals(2, result.differences().size(), result.differences().toString());
        assertTrue(result.differences().stream().anyMatch(d -> d.startsWith("cosmic.taxPercent: expected 99.0")));
    }

    @Test
    void aCaptureWithoutExpectationJustParses() throws CaptureException {
        Capture c = capture(busyFrame());
        Capture bare = new Capture(c.schema(), c.kind(), c.meta(), c.frame(), c.memory(), null);
        assertTrue(Replay.run(CaptureIO.fromJson(CaptureIO.toJson(bare)), MODEL).matches());
    }

    // ── bad files ────────────────────────────────────────────────────────────

    @Test
    void staleCaptureIsRefusedWithAClearMessage() {
        CaptureException e = assertThrows(CaptureException.class, () -> CaptureIO.fromJson("{\"schema\":0,\"kind\":\"theprisons-capture\",\"frame\":{}}"));
        assertTrue(e.stale());
        assertTrue(e.getMessage().contains("stale capture"));
        assertTrue(e.getMessage().contains("record it again"));
    }

    @Test
    void newerCaptureIsRefused() {
        CaptureException e = assertThrows(CaptureException.class, () -> CaptureIO.fromJson("{\"schema\":99,\"kind\":\"theprisons-capture\",\"frame\":{}}"));
        assertFalse(e.stale());
        assertTrue(e.getMessage().contains("newer than this build"));
    }

    @Test
    void notACaptureOrNotJson() {
        assertThrows(CaptureException.class, () -> CaptureIO.fromJson("{\"schema\":1,\"kind\":\"something else\"}"));
        assertThrows(CaptureException.class, () -> CaptureIO.fromJson("[1,2,3]"));
        assertThrows(CaptureException.class, () -> CaptureIO.fromJson("{not json"));
        assertThrows(CaptureException.class, () -> CaptureIO.fromJson("{\"schema\":1,\"kind\":\"theprisons-capture\"}"), "no frame");
    }

    @Test
    void aSparseCaptureStillReplays() throws CaptureException {
        // Hand-edited / older files miss lists and fields: they load as empty, not as a crash.
        Capture c = CaptureIO.fromJson("{\"schema\":1,\"kind\":\"theprisons-capture\",\"frame\":{\"where\":{\"server\":\"x\"},"
                + "\"player\":{\"name\":\"n\",\"held\":{\"itemId\":\"minecraft:diamond_pickaxe\"}}}}");
        Replay.Result r = Replay.run(c, MODEL);
        assertNotNull(r.snapshot());
        assertTrue(r.snapshot().player().present());
        assertFalse(r.snapshot().cosmic().heldPickaxeEnergy().isKnown());
        assertTrue(r.snapshot().combat().entities().isEmpty());
    }

    // ── situations around a capture ─────────────────────────────────────────

    @Test
    void guiClosedMeansNoScreen() {
        Raw.Frame f = busyFrame();
        Raw.Frame closed = new Raw.Frame(f.tick(), f.nowMs(), f.where(), f.player(), f.entities(), f.blocks(), f.sidebar(), f.bossBars(),
                f.actionBar(), Raw.Screen.NONE, f.inventory());
        Capture c = capture(closed);
        assertTrue(c.frame().screen().slots().isEmpty());
        assertEquals(null, c.frame().screen().title());
        assertFalse(Replay.run(c, MODEL).snapshot().ui().screenOpen());
    }

    @Test
    void disconnectMeansNoPlayerAndNoWorld() {
        Raw.Frame gone = new Raw.Frame(1, 1, Raw.Where.NONE, null, List.of(), null, null, List.of(), List.of(), Raw.Screen.NONE, List.of());
        Capture c = capture(gone);
        Replay.Result r = Replay.run(c, MODEL);
        assertTrue(r.matches());
        assertFalse(r.snapshot().player().present());
        assertFalse(r.snapshot().world().loaded());
    }

    @Test
    void aTargetThatVanishedIsJustNotThere() {
        Raw.Frame f = busyFrame();
        Raw.Frame noEntities = new Raw.Frame(f.tick(), f.nowMs(), f.where(), f.player(), List.of(), f.blocks(), f.sidebar(), f.bossBars(),
                f.actionBar(), f.screen(), f.inventory());
        assertEquals(0, Replay.run(capture(noEntities), MODEL).snapshot().combat().entities().size());
    }

    @Test
    void emptyInventoryAndNoSidebarCapture() {
        Raw.Frame f = TestFrames.frame(TestFrames.player("Steve", Raw.Stack.EMPTY), List.of(), null);
        Capture c = capture(f);
        assertTrue(c.frame().inventory().isEmpty());
        assertEquals(null, c.frame().sidebar());
        assertTrue(Replay.run(c, MODEL).matches());
    }

    @Test
    void writeAndReadAFile(@TempDir Path dir) throws IOException, CaptureException {
        Capture c = capture(busyFrame());
        Path file = dir.resolve("captures").resolve("c.json");
        CaptureIO.write(file, c);
        assertTrue(Files.size(file) > 100);
        assertEquals(c, CaptureIO.read(file));
        assertThrows(CaptureException.class, () -> CaptureIO.read(dir.resolve("missing.json")));
    }
}
