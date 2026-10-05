package io.theprisons.modules.mining.ore;

import io.theprisons.core.nav.Cell;
import io.theprisons.core.nav.Pos;
import io.theprisons.core.nav.VoxelView;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

import java.util.function.IntPredicate;

/**
 * How much of the ore around the player is mined. Every target ore ever seen is remembered; a remembered position that
 * is now stone / deepslate (known, but no target ore any more) counts as mined. The share is taken over all remembered
 * positions within {@value #RADIUS} blocks (±{@value #HEIGHT} high).
 */
final class OreDepletion {
    static final int RADIUS = 16;
    static final int HEIGHT = 4;
    /** Below this many ores in the radius the share says nothing (a nearly empty area is not "mined out"). */
    static final int MIN_ORES = 20;
    /** Remembered positions are dropped when there are more than this many. */
    private static final int MAX_SEEN = 200_000;

    private final LongOpenHashSet seen = new LongOpenHashSet();
    private int total;
    private int mined;

    /** Remembers the ores around {@code x, y, z} and counts the mined share there. */
    void update(VoxelView view, IntPredicate isTarget, double x, double y, double z) {
        if (seen.size() > MAX_SEEN) {
            seen.clear();
        }
        int px = (int) Math.floor(x);
        int py = (int) Math.floor(y);
        int pz = (int) Math.floor(z);
        int all = 0;
        int gone = 0;
        for (int dx = -RADIUS; dx <= RADIUS; dx++) {
            for (int dz = -RADIUS; dz <= RADIUS; dz++) {
                if (dx * dx + dz * dz > RADIUS * RADIUS) {
                    continue;
                }
                for (int dy = -HEIGHT; dy <= HEIGHT; dy++) {
                    int bx = px + dx;
                    int by = py + dy;
                    int bz = pz + dz;
                    int key = view.ore(bx, by, bz);
                    long pos = Pos.pack(bx, by, bz);
                    if (key != 0 && isTarget.test(key)) {
                        seen.add(pos);
                        all++;
                    } else if (seen.contains(pos) && view.cell(bx, by, bz) != Cell.UNKNOWN) {
                        all++;
                        gone++;
                    }
                }
            }
        }
        total = all;
        mined = gone;
    }

    /** Mined share of the ores in the radius (0 when too few ores are known there). */
    double minedShare() {
        return total < MIN_ORES ? 0.0D : mined / (double) total;
    }

    int total() {
        return total;
    }

    void clear() {
        seen.clear();
        total = 0;
        mined = 0;
    }
}
