package io.theprisons.core.world;

import io.theprisons.core.nav.Cell;
import io.theprisons.core.nav.Pos;
import io.theprisons.core.nav.VoxelView;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectIterator;
import org.jspecify.annotations.Nullable;

import java.util.function.Predicate;

/**
 * The client-thread store of extracted sections plus the {@link OreIndex}. Minecraft-free, so the whole data path
 * (extraction result → diff → index → snapshot) is unit-testable. Worker threads never touch this object; they get
 * a {@link WorldSnapshot}.
 */
public final class SectionStore implements VoxelView {
    private final Long2ObjectOpenHashMap<SectionSnapshot> sections = new Long2ObjectOpenHashMap<>();
    private final OreIndex ores = new OreIndex();
    private long version;
    private long cachedKey = Long.MIN_VALUE;
    private @Nullable SectionSnapshot cached;

    public OreIndex ores() {
        return ores;
    }

    /** Changes with every stored modification. */
    public long version() {
        return version;
    }

    public int size() {
        return sections.size();
    }

    public @Nullable SectionSnapshot get(long sectionKey) {
        return sections.get(sectionKey);
    }

    /** @return true when the content changed */
    public boolean put(SectionSnapshot snapshot) {
        SectionSnapshot previous = sections.put(snapshot.key(), snapshot);
        invalidateCache();
        ores.apply(previous, snapshot);
        boolean changed = previous == null || !java.util.Arrays.equals(previous.cells(), snapshot.cells())
                || !java.util.Arrays.equals(previous.ores(), snapshot.ores());
        if (changed) {
            version++;
        }
        return changed;
    }

    public void remove(long sectionKey) {
        SectionSnapshot previous = sections.remove(sectionKey);
        if (previous != null) {
            invalidateCache();
            ores.apply(previous, null);
            version++;
        }
    }

    /** Removes every stored section matching the predicate (eviction by distance, unloaded chunks). */
    public int removeIf(Predicate<SectionSnapshot> predicate) {
        int removed = 0;
        for (ObjectIterator<Long2ObjectMap.Entry<SectionSnapshot>> it = sections.long2ObjectEntrySet().fastIterator(); it.hasNext(); ) {
            SectionSnapshot section = it.next().getValue();
            if (predicate.test(section)) {
                it.remove();
                ores.apply(section, null);
                removed++;
            }
        }
        if (removed > 0) {
            invalidateCache();
            version++;
        }
        return removed;
    }

    /**
     * Applies a single block change immediately (our own block break), copy-on-write.
     *
     * @return false when the section is not stored
     */
    public boolean patch(long pos, int cell, int stored) {
        int x = Pos.x(pos);
        int y = Pos.y(pos);
        int z = Pos.z(pos);
        SectionSnapshot section = sections.get(Pos.sectionOf(x, y, z));
        if (section == null) {
            return false;
        }
        int index = SectionSnapshot.index(x & 15, y & 15, z & 15);
        if (section.cellAt(index) == cell && section.storedAt(index) == stored) {
            return true;
        }
        put(section.with(index, cell, stored));
        return true;
    }

    public void clear() {
        sections.clear();
        ores.clear();
        invalidateCache();
        version++;
    }

    /**
     * Immutable view of all sections whose column lies within {@code radius} blocks (Chebyshev) of the centre and
     * whose height overlaps {@code [minY, maxY]}.
     */
    public WorldSnapshot snapshot(int centerX, int centerZ, int radius, int minY, int maxY) {
        int minCx = (centerX - radius) >> 4;
        int maxCx = (centerX + radius) >> 4;
        int minCz = (centerZ - radius) >> 4;
        int maxCz = (centerZ + radius) >> 4;
        int minSy = minY >> 4;
        int maxSy = maxY >> 4;
        Long2ObjectOpenHashMap<SectionSnapshot> copy = new Long2ObjectOpenHashMap<>();
        for (SectionSnapshot section : sections.values()) {
            if (section.sx() >= minCx && section.sx() <= maxCx && section.sz() >= minCz && section.sz() <= maxCz
                    && section.sy() >= minSy && section.sy() <= maxSy) {
                copy.put(section.key(), section);
            }
        }
        return new WorldSnapshot(copy, version);
    }

    public Iterable<SectionSnapshot> sections() {
        return sections.values();
    }

    private void invalidateCache() {
        cachedKey = Long.MIN_VALUE;
        cached = null;
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
}
