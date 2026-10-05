package io.theprisons.core.world;

import io.theprisons.core.nav.Pos;
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import org.jspecify.annotations.Nullable;

/**
 * The single index of known target blocks, kept in sync incrementally from section diffs (cost is proportional to
 * the ores in the changed section, not to its 4 096 cells). Holds 4³ spatial buckets for density queries. Client
 * thread only; worker jobs read ores from their {@link WorldSnapshot} instead.
 */
public final class OreIndex {
    public static final int BUCKET_SHIFT = 2;

    private final Long2IntOpenHashMap ores = new Long2IntOpenHashMap();
    private final Long2IntOpenHashMap buckets = new Long2IntOpenHashMap();
    private final Int2IntOpenHashMap perKey = new Int2IntOpenHashMap();
    private long version;

    public int size() {
        return ores.size();
    }

    /** Block key at the position, 0 when it is not a known target. */
    public int key(long pos) {
        return ores.get(pos);
    }

    public int countOf(int blockKey) {
        return perKey.get(blockKey);
    }

    /** Changes whenever an ore appears or disappears. */
    public long version() {
        return version;
    }

    /** Known targets in the (2r+1)³ buckets around a block position (bucket edge = 4 blocks). */
    public int density(int x, int y, int z, int bucketRadius) {
        int bx = x >> BUCKET_SHIFT;
        int by = y >> BUCKET_SHIFT;
        int bz = z >> BUCKET_SHIFT;
        int total = 0;
        for (int dx = -bucketRadius; dx <= bucketRadius; dx++) {
            for (int dy = -bucketRadius; dy <= bucketRadius; dy++) {
                for (int dz = -bucketRadius; dz <= bucketRadius; dz++) {
                    total += buckets.get(Pos.pack(bx + dx, by + dy, bz + dz));
                }
            }
        }
        return total;
    }

    /** Brings the index from {@code before} to {@code after} (either may be null for load / unload). */
    public void apply(@Nullable SectionSnapshot before, @Nullable SectionSnapshot after) {
        if (before != null) {
            for (short slot : before.oreSlots()) {
                int newKey = after == null ? 0 : after.oreAt(slot);
                if (newKey != before.oreAt(slot)) {
                    remove(before.worldPos(slot));
                }
            }
        }
        if (after != null) {
            for (short slot : after.oreSlots()) {
                int key = after.oreAt(slot);
                int oldKey = before == null ? 0 : before.oreAt(slot);
                if (key != oldKey) {
                    put(after.worldPos(slot), key);
                }
            }
        }
    }

    private void put(long pos, int key) {
        int previous = ores.put(pos, key);
        if (previous != 0) {
            perKey.addTo(previous, -1);
        } else {
            buckets.addTo(bucketOf(pos), 1);
        }
        perKey.addTo(key, 1);
        version++;
    }

    private void remove(long pos) {
        int previous = ores.remove(pos);
        if (previous == 0) {
            return;
        }
        perKey.addTo(previous, -1);
        long bucket = bucketOf(pos);
        if (buckets.addTo(bucket, -1) <= 1) {
            buckets.remove(bucket);
        }
        version++;
    }

    public void clear() {
        ores.clear();
        buckets.clear();
        perKey.clear();
        version++;
    }

    private static long bucketOf(long pos) {
        return Pos.pack(Pos.x(pos) >> BUCKET_SHIFT, Pos.y(pos) >> BUCKET_SHIFT, Pos.z(pos) >> BUCKET_SHIFT);
    }
}
