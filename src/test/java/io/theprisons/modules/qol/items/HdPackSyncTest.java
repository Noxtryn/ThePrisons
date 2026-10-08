package io.theprisons.modules.qol.items;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HdPackSyncTest {
    private final AtomicBoolean wanted = new AtomicBoolean();
    private final AtomicInteger reloads = new AtomicInteger();
    private HdPackSync sync;

    @BeforeEach
    void fresh() {
        HdPackSync.choose(false);
        sync = new HdPackSync(wanted::get, reloads::incrementAndGet);
    }

    @AfterEach
    void restore() {
        HdPackSync.choose(false);
    }

    // ── the accessor ─────────────────────────────────────────────────────────

    @Test
    void theAccessorIsClassicBeforeTheModuleHasChosenAnything() {
        assertFalse(HdPackSync.hdV2Chosen());
        HdPackSync.choose(true);
        assertTrue(HdPackSync.hdV2Chosen());
        HdPackSync.choose(false);
        assertFalse(HdPackSync.hdV2Chosen());
    }

    // ── the list the pack manager returns ───────────────────────────────────

    @Test
    void classicDoesNotAppendTheHdPackAndKeepsTheLookPackLast() {
        List<String> out = HdPackSync.assemble(List.of("vanilla", "mod", "user"), false, "hd", "look");
        assertEquals(List.of("vanilla", "mod", "user", "look"), out);
    }

    @Test
    void hdV2IsAppendedExactlyOnceBeforeTheLookPack() {
        List<String> once = HdPackSync.assemble(List.of("vanilla", "mod", "user"), true, "hd", "look");
        assertEquals(List.of("vanilla", "mod", "user", "hd", "look"), once);
        List<String> again = HdPackSync.assemble(once, true, "hd", "look");
        assertEquals(once, again, "building the list from an already built list adds nothing twice");
        assertEquals(1, again.stream().filter("hd"::equals).count());
    }

    @Test
    void aMissingHdPackIsSimplySkipped() {
        assertEquals(List.of("mod", "look"), HdPackSync.assemble(List.of("mod"), true, null, "look"));
    }

    @Test
    void aMissingLookPackChangesNothingElse() {
        assertEquals(List.of("mod", "hd"), HdPackSync.assemble(List.of("mod"), true, "hd", null));
    }

    // ── one reload, never a loop ─────────────────────────────────────────────

    @Test
    void switchingTheSettingReloadsOnce() {
        sync.packsComputed(false, false);              // the game started classic
        wanted.set(true);
        assertTrue(sync.reconcile());
        assertEquals(1, reloads.get());
        // the setting listener and the tick both ask again before the reload finished
        assertFalse(sync.reconcile());
        assertFalse(sync.reconcile());
        sync.packsComputed(true, true);                // the reload built the list with the pack
        for (int i = 0; i < 100; i++) {
            assertFalse(sync.reconcile());
        }
        assertEquals(1, reloads.get());
        assertTrue(sync.applied());
    }

    @Test
    void switchingBackReloadsOnceMore() {
        sync.packsComputed(true, true);
        wanted.set(false);
        assertTrue(sync.reconcile());
        sync.packsComputed(false, false);
        assertFalse(sync.reconcile());
        assertEquals(1, reloads.get());
    }

    @Test
    void aSettingLoadedBeforeTheFirstPackListNeverReloads() {
        wanted.set(true);                              // the config was read first: the first list is built with HD
        for (int i = 0; i < 50; i++) {
            assertFalse(sync.reconcile(), "no list built yet: nothing to compare with");
        }
        sync.packsComputed(true, true);
        assertFalse(sync.reconcile());
        assertEquals(0, reloads.get());
    }

    @Test
    void aSettingLoadedAfterTheFirstPackListReloadsExactlyOnce() {
        sync.packsComputed(false, false);              // the first list was built before the config was read
        wanted.set(true);                              // persisted HD V2 arrives late (even several times: set + tick + listener)
        assertTrue(sync.reconcile());
        assertFalse(sync.reconcile());
        sync.packsComputed(true, true);
        for (int i = 0; i < 100; i++) {
            sync.reconcile();
        }
        assertEquals(1, reloads.get());
    }

    @Test
    void listingThePacksWithoutAChangeNeverReloads() {
        sync.packsComputed(false, false);
        for (int i = 0; i < 20; i++) {
            sync.packsComputed(false, false);          // the pack screen enumerates, a reload rebuilds the same list
            assertFalse(sync.reconcile());
        }
        assertEquals(0, reloads.get());
    }

    @Test
    void aTogglingBackWhileTheReloadRunsIsPickedUpAfterwardsOnce() {
        sync.packsComputed(false, false);
        wanted.set(true);
        assertTrue(sync.reconcile());                  // reload 1 running
        wanted.set(false);                             // the player changes his mind
        assertFalse(sync.reconcile());                 // not while one is running
        sync.packsComputed(true, true);                // reload 1 done with HD
        assertTrue(sync.reconcile());                  // now back to classic: reload 2
        sync.packsComputed(false, false);
        assertFalse(sync.reconcile());
        assertEquals(2, reloads.get());
    }

    @Test
    void aPackThatCannotBeFoundDoesNotReloadForever() {
        sync.packsComputed(false, false);
        wanted.set(true);
        assertTrue(sync.reconcile());
        sync.packsComputed(true, false);               // wanted, but the folder is not in the jar: counts as handled
        for (int i = 0; i < 100; i++) {
            assertFalse(sync.reconcile());
        }
        assertEquals(1, reloads.get());
    }
}
