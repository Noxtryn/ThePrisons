package io.theprisons.core.world;

import io.theprisons.core.nav.Cell;
import io.theprisons.core.nav.Pos;
import io.theprisons.core.nav.VoxelView;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import org.jspecify.annotations.Nullable;

import java.util.Collection;

/**
 * A consistent, immutable view of the known world around the player, handed to worker jobs. Built on the client
 * thread by copying the references of the relevant section snapshots (a few hundred pointers); the snapshots
 * themselves are immutable, so the job sees exactly one world version no matter what the scanner does meanwhile.
 *
 * <p>Lookups use a primitive map plus a one-entry section cache (consecutive lookups are nearly always in the same
 * section), so there is no boxing and no concurrent map in the search loops. The cache makes an instance
 * <b>thread-confined</b>: use one snapshot per job, or {@link #copyView()} for another thread.
 */
public final class WorldSnapshot implements VoxelView {
    private final Long2ObjectOpenHashMap<SectionSnapshot> sections;
    private final long version;
    private long cachedKey = Long.MIN_VALUE;
    private @Nullable SectionSnapshot cached;

    public WorldSnapshot(Long2ObjectOpenHashMap<SectionSnapshot> sections, long version) {
        this.sections = sections;
        this.version = version;
    }

    public long version() {
        return version;
    }

    public int sectionCount() {
        return sections.size();
    }

    public Collection<SectionSnapshot> sections() {
        return sections.values();
    }

    /** Same data, independent lookup cache. */
    public WorldSnapshot copyView() {
        return new WorldSnapshot(sections, version);
    }

    private @Nullable SectionSnapshot section(int x, int y, int z) {
        long key = Pos.pack(x >> 4, y >> 4, z >> 4);
        if (key != cachedKey) {
            cached = sections.get(key);
            cachedKey = key;
        }
        return cached;
    }

    @Override
    public int cell(int x, int y, int z) {
        SectionSnapshot section = section(x, y, z);
        return section == null ? Cell.UNKNOWN : section.cell(x & 15, y & 15, z & 15);
    }

    @Override
    public int ore(int x, int y, int z) {
        SectionSnapshot section = section(x, y, z);
        return section == null ? 0 : section.ore(x & 15, y & 15, z & 15);
    }

    @Override
    public boolean mineFloor(int x, int y, int z) {
        SectionSnapshot section = section(x, y, z);
        return section != null && section.mineFloor(x & 15, y & 15, z & 15);
    }

    /** Visits every known target block. */
    public void forEachOre(OreVisitor visitor) {
        for (SectionSnapshot section : sections.values()) {
            for (short slot : section.oreSlots()) {
                visitor.visit(section.worldPos(slot), section.oreAt(slot));
            }
        }
    }

    public interface OreVisitor {
        void visit(long pos, int blockKey);
    }
}
