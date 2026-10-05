package io.theprisons.core.world;

import io.theprisons.core.nav.Cell;
import io.theprisons.core.nav.Pos;
import io.theprisons.testing.TestMine;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SectionStoreTest {
    private static TestMine mine() {
        TestMine mine = new TestMine(0, 0, 0, 31, 31, 31);
        mine.carve(2, 2, 2, 20, 3, 20);
        mine.ore(5, 4, 5, TestMine.REDSTONE_ORE).ore(6, 4, 5, TestMine.REDSTONE_ORE).ore(25, 25, 25, TestMine.DIAMOND_ORE);
        return mine;
    }

    @Test
    void oreIndexFollowsSectionsIncrementally() {
        TestMine mine = mine();
        SectionStore store = mine.store();
        OreIndex ores = store.ores();
        int redstone = BlockKeys.key(TestMine.REDSTONE_ORE);
        assertEquals(3, ores.size());
        assertEquals(2, ores.countOf(redstone));
        assertEquals(redstone, ores.key(Pos.pack(5, 4, 5)));
        assertEquals(2, ores.density(5, 4, 5, 0), "both redstone ores share a 4³ bucket");

        // Mine resets / other players: the section is re-extracted with the ore gone.
        mine.mine(Pos.pack(5, 4, 5));
        store.put(mine.snapshot(0, 0, 0));
        assertEquals(2, ores.size());
        assertEquals(0, ores.key(Pos.pack(5, 4, 5)));
        assertEquals(1, ores.density(5, 4, 5, 0));

        // Unloading removes the section's ores.
        store.removeIf(section -> section.sx() == 1 && section.sy() == 1 && section.sz() == 1);
        assertEquals(1, ores.size());
        assertEquals(0, ores.countOf(BlockKeys.key(TestMine.DIAMOND_ORE)));
    }

    @Test
    void patchIsCopyOnWriteAndUpdatesIndex() {
        SectionStore store = mine().store();
        WorldSnapshot before = store.snapshot(0, 0, 64, 0, 31);
        long version = store.version();
        assertTrue(store.patch(Pos.pack(6, 4, 5), Cell.AIR, 0));
        assertTrue(store.version() > version);
        assertEquals(Cell.AIR, store.cell(6, 4, 5));
        assertEquals(0, store.ore(6, 4, 5));
        assertEquals(1, store.ores().countOf(BlockKeys.key(TestMine.REDSTONE_ORE)));
        // A snapshot taken earlier (a running worker job) still sees the old world.
        assertEquals(Cell.SOLID, before.cell(6, 4, 5));
        assertEquals(BlockKeys.key(TestMine.REDSTONE_ORE), before.ore(6, 4, 5));
        assertFalse(store.patch(Pos.pack(500, 4, 5), Cell.AIR, 0), "unknown section");
    }

    @Test
    void snapshotContainsOnlyTheRequestedAreaAndListsOres() {
        SectionStore store = mine().store();
        WorldSnapshot near = store.snapshot(4, 4, 8, 0, 15);
        assertEquals(1, near.sectionCount());
        assertEquals(Cell.UNKNOWN, near.cell(25, 25, 25));
        LongArrayList found = new LongArrayList();
        near.forEachOre((pos, key) -> found.add(pos));
        assertEquals(2, found.size());
        assertTrue(found.contains(Pos.pack(5, 4, 5)));
    }

    @Test
    void sectionSlotsMatchOreArray() {
        SectionSnapshot section = mine().snapshot(0, 0, 0);
        assertEquals(2, section.oreCount());
        for (short slot : section.oreSlots()) {
            assertTrue(section.oreAt(slot) != 0);
            long pos = section.worldPos(slot);
            assertEquals(section.ore(Pos.x(pos) & 15, Pos.y(pos) & 15, Pos.z(pos) & 15), section.oreAt(slot));
        }
        SectionSnapshot mined = section.with(section.oreSlots()[0], Cell.AIR, 0);
        assertEquals(1, mined.oreCount());
        assertEquals(2, section.oreCount(), "original unchanged");
    }
}
