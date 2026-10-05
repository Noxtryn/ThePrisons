package com.freelocs.theprisons.core.world;

import com.freelocs.theprisons.core.nav.Cell;
import com.freelocs.theprisons.core.nav.Pos;
import com.freelocs.theprisons.core.nav.VoxelView;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import org.jspecify.annotations.Nullable;

import java.util.Collection;

/**
 * A frozen view of the {@link WorldArchive} for a worker job (sections are immutable, the map is a copy). Solid and
 * ore blocks are full cubes, danger blocks are full danger cubes, everything else is air; sections never scanned are
 * {@link Cell#UNKNOWN}. Thread-confined because of the one-entry section cache - use one per job.
 */
public final class ArchiveView implements VoxelView {
    static final int DANGER_CELL = Cell.encode(0, 16, Cell.COLLISION | Cell.OUTLINE | Cell.FULL | Cell.DANGER);

    private final Long2ObjectOpenHashMap<ArchiveSection> sections;
    /** Block key (see {@link BlockKeys}) per ore palette index. */
    private final int[] oreKeys;
    private long cachedKey = Long.MIN_VALUE;
    private @Nullable ArchiveSection cached;

    public ArchiveView(Long2ObjectOpenHashMap<ArchiveSection> sections, int[] oreKeys) {
        this.sections = sections;
        this.oreKeys = oreKeys;
    }

    public int sectionCount() {
        return sections.size();
    }

    public Collection<ArchiveSection> sections() {
        return sections.values();
    }

    private @Nullable ArchiveSection section(int x, int y, int z) {
        long key = Pos.pack(x >> 4, y >> 4, z >> 4);
        if (key != cachedKey) {
            cached = sections.get(key);
            cachedKey = key;
        }
        return cached;
    }

    /** Raw archive code, or -1 when the block was never scanned. */
    public int code(int x, int y, int z) {
        ArchiveSection section = section(x, y, z);
        return section == null ? -1 : section.code(x & 15, y & 15, z & 15);
    }

    public static int cellOf(int code) {
        return switch (code) {
            case -1 -> Cell.UNKNOWN;
            case ArchiveSection.AIR -> Cell.AIR;
            case ArchiveSection.DANGER -> DANGER_CELL;
            default -> Cell.SOLID;
        };
    }

    public int oreKeyOf(int code) {
        int index = code - ArchiveSection.ORE_BASE;
        return index >= 0 && index < oreKeys.length ? oreKeys[index] : 0;
    }

    @Override
    public int cell(int x, int y, int z) {
        return cellOf(code(x, y, z));
    }

    @Override
    public int ore(int x, int y, int z) {
        return oreKeyOf(code(x, y, z));
    }

    @Override
    public boolean mineFloor(int x, int y, int z) {
        int code = code(x, y, z);
        return code == ArchiveSection.SOLID || code >= ArchiveSection.ORE_BASE && code != ArchiveSection.OTHER;
    }
}
