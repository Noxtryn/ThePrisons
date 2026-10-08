package io.theprisons.modules.qol.items;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HdPackSyncTest {
    private final AtomicReference<HdPackSync.Choice> wanted = new AtomicReference<>(HdPackSync.Choice.CLASSIC);
    private final AtomicInteger reloads = new AtomicInteger();
    private HdPackSync sync;

    @BeforeEach
    void fresh() {
        HdPackSync.choose(HdPackSync.Choice.CLASSIC);
        wanted.set(HdPackSync.Choice.CLASSIC);
        sync = new HdPackSync(wanted::get, reloads::incrementAndGet);
    }

    @AfterEach
    void restore() {
        HdPackSync.choose(HdPackSync.Choice.CLASSIC);
    }

    // ── the accessor ─────────────────────────────────────────────────────────

    @Test
    void theAccessorIsClassicBeforeTheModuleHasChosenAnything() {
        assertFalse(HdPackSync.hdV2Chosen());
        HdPackSync.choose(HdPackSync.Choice.V2);
        assertTrue(HdPackSync.hdV2Chosen());
        HdPackSync.choose(HdPackSync.Choice.CLASSIC);
        assertFalse(HdPackSync.hdV2Chosen());
    }

    // ── the list the pack manager returns ───────────────────────────────────

    @Test
    void classicDoesNotAppendTheHdPackAndKeepsTheLookPackLast() {
        List<String> out = HdPackSync.assemble(List.of("vanilla", "mod", "user"), HdPackSync.Choice.CLASSIC, "v2", "v4", "look");
        assertEquals(List.of("vanilla", "mod", "user", "look"), out);
    }

    @Test
    void hdV2IsAppendedExactlyOnceBeforeTheLookPack() {
        List<String> once = HdPackSync.assemble(List.of("vanilla", "mod", "user"), HdPackSync.Choice.V2, "hd", "v4", "look");
        assertEquals(List.of("vanilla", "mod", "user", "hd", "look"), once);
        List<String> again = HdPackSync.assemble(once, HdPackSync.Choice.V2, "hd", "v4", "look");
        assertEquals(once, again, "building the list from an already built list adds nothing twice");
        assertEquals(1, again.stream().filter("hd"::equals).count());
    }

    @Test
    void hdV4IsSelectedInsteadOfV2AndNeverChangesClassicOrTheLookPackOrder() {
        List<String> out = HdPackSync.assemble(List.of("vanilla", "mod", "user"), HdPackSync.Choice.V4, "v2", "v4", "look");
        assertEquals(List.of("vanilla", "mod", "user", "v4", "look"), out);
        assertFalse(out.contains("v2"));
    }

    @Test
    void aMissingHdPackIsSimplySkipped() {
        assertEquals(List.of("mod", "look"), HdPackSync.assemble(List.of("mod"), HdPackSync.Choice.V2, null, "v4", "look"));
    }

    @Test
    void aMissingLookPackChangesNothingElse() {
        assertEquals(List.of("mod", "hd"), HdPackSync.assemble(List.of("mod"), HdPackSync.Choice.V2, "hd", "v4", null));
    }

    // ── one reload, never a loop ─────────────────────────────────────────────

    @Test
    void switchingTheSettingReloadsOnce() {
        sync.packsComputed(HdPackSync.Choice.CLASSIC, false); // the game started classic
        wanted.set(HdPackSync.Choice.V2);
        assertTrue(sync.reconcile());
        assertEquals(1, reloads.get());
        // the setting listener and the tick both ask again before the reload finished
        assertFalse(sync.reconcile());
        assertFalse(sync.reconcile());
        sync.packsComputed(HdPackSync.Choice.V2, true); // the reload built the list with the pack
        for (int i = 0; i < 100; i++) {
            assertFalse(sync.reconcile());
        }
        assertEquals(1, reloads.get());
        assertEquals(HdPackSync.Choice.V2, sync.applied());
    }

    @Test
    void switchingBackReloadsOnceMore() {
        sync.packsComputed(HdPackSync.Choice.V2, true);
        wanted.set(HdPackSync.Choice.CLASSIC);
        assertTrue(sync.reconcile());
        sync.packsComputed(HdPackSync.Choice.CLASSIC, false);
        assertFalse(sync.reconcile());
        assertEquals(1, reloads.get());
    }

    @Test
    void aSettingLoadedBeforeTheFirstPackListNeverReloads() {
        wanted.set(HdPackSync.Choice.V2);              // the config was read first: the first list is built with HD
        for (int i = 0; i < 50; i++) {
            assertFalse(sync.reconcile(), "no list built yet: nothing to compare with");
        }
        sync.packsComputed(HdPackSync.Choice.V2, true);
        assertFalse(sync.reconcile());
        assertEquals(0, reloads.get());
    }

    @Test
    void aSettingLoadedAfterTheFirstPackListReloadsExactlyOnce() {
        sync.packsComputed(HdPackSync.Choice.CLASSIC, false); // the first list was built before the config was read
        wanted.set(HdPackSync.Choice.V2);              // persisted HD V2 arrives late (even several times: set + tick + listener)
        assertTrue(sync.reconcile());
        assertFalse(sync.reconcile());
        sync.packsComputed(HdPackSync.Choice.V2, true);
        for (int i = 0; i < 100; i++) {
            sync.reconcile();
        }
        assertEquals(1, reloads.get());
    }

    @Test
    void listingThePacksWithoutAChangeNeverReloads() {
        sync.packsComputed(HdPackSync.Choice.CLASSIC, false);
        for (int i = 0; i < 20; i++) {
            sync.packsComputed(HdPackSync.Choice.CLASSIC, false); // the pack screen enumerates, a reload rebuilds the same list
            assertFalse(sync.reconcile());
        }
        assertEquals(0, reloads.get());
    }

    @Test
    void aTogglingBackWhileTheReloadRunsIsPickedUpAfterwardsOnce() {
        sync.packsComputed(HdPackSync.Choice.CLASSIC, false);
        wanted.set(HdPackSync.Choice.V2);
        assertTrue(sync.reconcile());                  // reload 1 running
        wanted.set(HdPackSync.Choice.CLASSIC);         // the player changes his mind
        assertFalse(sync.reconcile());                 // not while one is running
        sync.packsComputed(HdPackSync.Choice.V2, true); // reload 1 done with HD
        assertTrue(sync.reconcile());                  // now back to classic: reload 2
        sync.packsComputed(HdPackSync.Choice.CLASSIC, false);
        assertFalse(sync.reconcile());
        assertEquals(2, reloads.get());
    }

    @Test
    void aPackThatCannotBeFoundDoesNotReloadForever() {
        sync.packsComputed(HdPackSync.Choice.CLASSIC, false);
        wanted.set(HdPackSync.Choice.V4);
        assertTrue(sync.reconcile());
        sync.packsComputed(HdPackSync.Choice.V4, false); // wanted, but the folder is not in the jar: counts as handled
        for (int i = 0; i < 100; i++) {
            assertFalse(sync.reconcile());
        }
        assertEquals(1, reloads.get());
    }
}
