package com.freelocs.theprisons.core.world;

import com.freelocs.theprisons.core.nav.Cell;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ArchiveCodecTest {
    @Test
    void roundTripRemapsTheOrePalette() throws Exception {
        byte[] codes = new byte[ArchiveSection.VOLUME];
        codes[ArchiveSection.index(1, 2, 3)] = (byte) (ArchiveSection.ORE_BASE + 1);
        codes[ArchiveSection.index(4, 5, 6)] = ArchiveSection.SOLID;
        codes[ArchiveSection.index(7, 8, 9)] = ArchiveSection.DANGER;
        ArchiveSection mixed = ArchiveSection.of(2, 3, -4, codes, 42L);
        ArchiveSection stone = new ArchiveSection(2, 2, -4, null, ArchiveSection.SOLID, 7L);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ArchiveCodec.write(out, List.of(mixed, stone), List.of("minecraft:lapis_ore", "minecraft:redstone_ore"));
        // The reading archive knows redstone first: code ORE_BASE + 0.
        List<ArchiveSection> read = ArchiveCodec.read(new ByteArrayInputStream(out.toByteArray()),
                id -> id.equals("minecraft:redstone_ore") ? ArchiveSection.ORE_BASE : ArchiveSection.ORE_BASE + 1);

        assertEquals(2, read.size());
        ArchiveSection back = read.get(0);
        assertEquals(42L, back.scannedAtMs());
        assertEquals(ArchiveSection.ORE_BASE, back.code(1, 2, 3));
        assertEquals(ArchiveSection.SOLID, back.code(4, 5, 6));
        assertEquals(ArchiveSection.DANGER, back.code(7, 8, 9));
        assertEquals(ArchiveSection.AIR, back.code(0, 0, 0));
        assertNull(read.get(1).codes(), "uniform stays uniform");
        assertEquals(ArchiveSection.SOLID, read.get(1).code(5, 5, 5));
    }

    @Test
    void viewTurnsCodesIntoCells() {
        byte[] codes = new byte[ArchiveSection.VOLUME];
        codes[ArchiveSection.index(0, 0, 0)] = (byte) ArchiveSection.ORE_BASE;
        codes[ArchiveSection.index(1, 0, 0)] = ArchiveSection.SOLID;
        ArchiveSection section = ArchiveSection.of(0, 0, 0, codes, 0L);
        Long2ObjectOpenHashMap<ArchiveSection> map = new Long2ObjectOpenHashMap<>();
        map.put(section.key(), section);
        int key = BlockKeys.key("minecraft:redstone_ore");
        ArchiveView view = new ArchiveView(map, new int[]{key});

        assertEquals(Cell.SOLID, view.cell(0, 0, 0));
        assertEquals(key, view.ore(0, 0, 0));
        assertEquals(Cell.SOLID, view.cell(1, 0, 0));
        assertEquals(0, view.ore(1, 0, 0));
        assertEquals(Cell.AIR, view.cell(2, 0, 0));
        assertEquals(Cell.UNKNOWN, view.cell(16, 0, 0), "never scanned");
    }
}
