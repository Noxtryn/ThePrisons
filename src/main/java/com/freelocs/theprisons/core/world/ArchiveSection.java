package com.freelocs.theprisons.core.world;

import com.freelocs.theprisons.core.nav.Pos;
import org.jspecify.annotations.Nullable;

/**
 * One 16³ section of the {@link WorldArchive}, reduced to what the planner needs: air, stone / deepslate (solid),
 * another solid block, danger or which ore.
 * Immutable (a change makes a new section), so worker threads may read it.
 *
 * @param codes one code per block ({@link #AIR}, {@link #SOLID}, {@link #DANGER} or {@link #ORE_BASE}+palette index),
 *              {@code null} when the whole section is {@code uniform}
 */
public record ArchiveSection(int sx, int sy, int sz, byte @Nullable [] codes, byte uniform, long scannedAtMs) {
    public static final int VOLUME = 16 * 16 * 16;
    public static final byte AIR = 0;
    public static final byte SOLID = 1;
    /** Lava, fire, liquids: never stood on or walked through. */
    public static final byte DANGER = 2;
    /** Ores: code {@code ORE_BASE + i} is entry {@code i} of the archive's ore palette. */
    public static final int ORE_BASE = 3;
    /** A solid block that is neither stone / deepslate nor an ore: never walked on. */
    public static final int OTHER = 255;
    public static final int MAX_ORES = OTHER - ORE_BASE;

    public static int index(int lx, int ly, int lz) {
        return ly << 8 | lz << 4 | lx;
    }

    public long key() {
        return Pos.pack(sx, sy, sz);
    }

    public int code(int lx, int ly, int lz) {
        return codes == null ? uniform & 0xFF : codes[index(lx, ly, lz)] & 0xFF;
    }


    /** Builds a section, collapsing a section of one code to {@link #uniform}. */
    public static ArchiveSection of(int sx, int sy, int sz, byte[] codes, long scannedAtMs) {
        byte first = codes[0];
        for (int i = 1; i < codes.length; i++) {
            if (codes[i] != first) {
                return new ArchiveSection(sx, sy, sz, codes, (byte) 0, scannedAtMs);
            }
        }
        return new ArchiveSection(sx, sy, sz, null, first, scannedAtMs);
    }

    public boolean sameContent(@Nullable ArchiveSection other) {
        if (other == null) {
            return false;
        }
        if (codes == null || other.codes == null) {
            return codes == null && other.codes == null && uniform == other.uniform;
        }
        return java.util.Arrays.equals(codes, other.codes);
    }
}
