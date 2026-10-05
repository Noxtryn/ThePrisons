package com.freelocs.theprisons.core.world;

import com.freelocs.theprisons.core.nav.Cell;
import com.freelocs.theprisons.core.nav.Pos;
import org.jspecify.annotations.Nullable;

/**
 * Immutable extracted copy of one 16³ chunk section; safe to share with worker threads.
 *
 * @param cells    packed {@link Cell} values indexed by {@link #index}, or {@code null} for an all-air section
 * @param ores     target block keys indexed by {@link #index}, or {@code null} when the section has no target block
 * @param oreSlots local indices of the target blocks (length = ore count), so ores can be listed without scanning
 *                 all 4 096 cells
 */
public record SectionSnapshot(int sx, int sy, int sz, char @Nullable [] cells, short @Nullable [] ores,
                              short[] oreSlots, long scannedAtMs) {
    public static final int VOLUME = 16 * 16 * 16;
    /**
     * Flag in the {@code ores} array: the block is stone, deepslate or an ore - the only ground the macro walks on.
     * The target key sits in the bits below ({@link #KEY_MASK}).
     */
    public static final int FLOOR = 0x4000;
    public static final int KEY_MASK = 0x3FFF;
    private static final short[] NO_SLOTS = new short[0];

    public static int index(int lx, int ly, int lz) {
        return ly << 8 | lz << 4 | lx;
    }

    public long key() {
        return Pos.pack(sx, sy, sz);
    }

    public int cell(int lx, int ly, int lz) {
        return cells == null ? Cell.AIR : cells[index(lx, ly, lz)];
    }

    public int cellAt(int index) {
        return cells == null ? Cell.AIR : cells[index];
    }

    public int ore(int lx, int ly, int lz) {
        return ores == null ? 0 : ores[index(lx, ly, lz)] & KEY_MASK;
    }

    public int oreAt(int index) {
        return ores == null ? 0 : ores[index] & KEY_MASK;
    }

    /** Stone, deepslate or an ore (see {@link #FLOOR}). */
    public boolean mineFloor(int lx, int ly, int lz) {
        return ores != null && (ores[index(lx, ly, lz)] & FLOOR) != 0;
    }

    /** The stored value: target key plus {@link #FLOOR}. */
    public int storedAt(int index) {
        return ores == null ? 0 : ores[index];
    }

    public int oreCount() {
        return oreSlots.length;
    }

    /** World position of a local index. */
    public long worldPos(int index) {
        return Pos.pack(sx << 4 | (index & 15), sy << 4 | (index >> 8 & 15), sz << 4 | (index >> 4 & 15));
    }

    /** Builds a snapshot from full arrays, collapsing all-air / target-free arrays to {@code null}. */
    public static SectionSnapshot of(int sx, int sy, int sz, char[] cells, short[] ores, long scannedAtMs) {
        boolean anyCell = false;
        for (char c : cells) {
            if (c != Cell.AIR) {
                anyCell = true;
                break;
            }
        }
        int count = 0;
        boolean anyStored = false;
        for (short o : ores) {
            if ((o & KEY_MASK) != 0) {
                count++;
            }
            anyStored |= o != 0;
        }
        short[] slots = count == 0 ? NO_SLOTS : new short[count];
        if (count > 0) {
            int j = 0;
            for (int i = 0; i < VOLUME; i++) {
                if ((ores[i] & KEY_MASK) != 0) {
                    slots[j++] = (short) i;
                }
            }
        }
        return new SectionSnapshot(sx, sy, sz, anyCell ? cells : null, anyStored ? ores : null, slots, scannedAtMs);
    }

    /** Copy with one block changed, {@code ore} = the stored value (copy-on-write; the original stays valid for readers). */
    public SectionSnapshot with(int index, int cell, int ore) {
        char[] nextCells = cells == null ? new char[VOLUME] : cells.clone();
        nextCells[index] = (char) cell;
        short[] nextOres = ores == null ? new short[VOLUME] : ores.clone();
        nextOres[index] = (short) ore;
        return of(sx, sy, sz, nextCells, nextOres, scannedAtMs);
    }
}
