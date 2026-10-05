package io.theprisons.testing;

import io.theprisons.core.nav.Cell;
import io.theprisons.core.nav.Pos;
import io.theprisons.core.nav.VoxelView;
import io.theprisons.core.world.BlockKeys;
import io.theprisons.core.world.SectionSnapshot;
import io.theprisons.core.world.SectionStore;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;

/**
 * Synthetic mine for unit tests: everything inside the bounds is solid rock unless carved, everything outside is
 * unknown (no data), exactly like an unscanned area of the real cache.
 */
public final class TestMine implements VoxelView {
    public static final String REDSTONE_ORE = "minecraft:redstone_ore";
    public static final String DEEPSLATE_REDSTONE_ORE = "minecraft:deepslate_redstone_ore";
    public static final String DIAMOND_ORE = "minecraft:diamond_ore";

    private final int minX;
    private final int minY;
    private final int minZ;
    private final int maxX;
    private final int maxY;
    private final int maxZ;
    private final Long2IntOpenHashMap cells = new Long2IntOpenHashMap();
    private final Long2IntOpenHashMap ores = new Long2IntOpenHashMap();
    /** Solid blocks that are not stone / deepslate / ore (wood, glass ...): never walked on. */
    private final it.unimi.dsi.fastutil.longs.LongOpenHashSet foreign = new it.unimi.dsi.fastutil.longs.LongOpenHashSet();

    public TestMine(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        this.minX = minX;
        this.minY = minY;
        this.minZ = minZ;
        this.maxX = maxX;
        this.maxY = maxY;
        this.maxZ = maxZ;
        cells.defaultReturnValue(Integer.MIN_VALUE);
    }

    public TestMine set(int x, int y, int z, int cell) {
        cells.put(Pos.pack(x, y, z), cell);
        ores.remove(Pos.pack(x, y, z));
        foreign.remove(Pos.pack(x, y, z));
        return this;
    }

    /** A solid block that is neither stone / deepslate nor an ore (wood, planks, glass ...). */
    public TestMine foreignBlock(int x, int y, int z) {
        set(x, y, z, Cell.SOLID);
        foreign.add(Pos.pack(x, y, z));
        return this;
    }

    @Override
    public boolean mineFloor(int x, int y, int z) {
        return !foreign.contains(Pos.pack(x, y, z));
    }

    /** Air box (inclusive corners). */
    public TestMine carve(int x1, int y1, int z1, int x2, int y2, int z2) {
        for (int x = Math.min(x1, x2); x <= Math.max(x1, x2); x++) {
            for (int y = Math.min(y1, y2); y <= Math.max(y1, y2); y++) {
                for (int z = Math.min(z1, z2); z <= Math.max(z1, z2); z++) {
                    set(x, y, z, Cell.AIR);
                }
            }
        }
        return this;
    }

    /** Solid box (inclusive corners). */
    public TestMine fill(int x1, int y1, int z1, int x2, int y2, int z2) {
        for (int x = Math.min(x1, x2); x <= Math.max(x1, x2); x++) {
            for (int y = Math.min(y1, y2); y <= Math.max(y1, y2); y++) {
                for (int z = Math.min(z1, z2); z <= Math.max(z1, z2); z++) {
                    set(x, y, z, Cell.SOLID);
                }
            }
        }
        return this;
    }

    public TestMine ore(int x, int y, int z, String id) {
        cells.put(Pos.pack(x, y, z), Cell.SOLID);
        ores.put(Pos.pack(x, y, z), BlockKeys.key(id));
        return this;
    }

    /** Mines a block: it becomes air. */
    public TestMine mine(long pos) {
        return set(Pos.x(pos), Pos.y(pos), Pos.z(pos), Cell.AIR);
    }

    public boolean inside(int x, int y, int z) {
        return x >= minX && x <= maxX && y >= minY && y <= maxY && z >= minZ && z <= maxZ;
    }

    public int oreCount() {
        return ores.size();
    }

    public Long2IntOpenHashMap ores() {
        return ores;
    }

    @Override
    public int cell(int x, int y, int z) {
        if (!inside(x, y, z)) {
            return Cell.UNKNOWN;
        }
        int cell = cells.get(Pos.pack(x, y, z));
        return cell == Integer.MIN_VALUE ? Cell.SOLID : cell;
    }

    @Override
    public int ore(int x, int y, int z) {
        return ores.get(Pos.pack(x, y, z));
    }

    /** Converts the mine into section snapshots, as the world cache would. */
    public SectionStore store() {
        return store(0);
    }

    /**
     * @param margin extra known (solid) sections around the mine, like the loaded chunks around a real cave; with 0
     *               the data ends right at the mine's sections (unknown space next to the edge)
     */
    public SectionStore store(int margin) {
        SectionStore store = new SectionStore();
        for (int sx = (minX >> 4) - margin; sx <= (maxX >> 4) + margin; sx++) {
            for (int sy = (minY >> 4) - margin; sy <= (maxY >> 4) + margin; sy++) {
                for (int sz = (minZ >> 4) - margin; sz <= (maxZ >> 4) + margin; sz++) {
                    store.put(snapshot(sx, sy, sz));
                }
            }
        }
        return store;
    }

    public SectionSnapshot snapshot(int sx, int sy, int sz) {
        char[] sectionCells = new char[SectionSnapshot.VOLUME];
        short[] sectionOres = new short[SectionSnapshot.VOLUME];
        for (int i = 0; i < SectionSnapshot.VOLUME; i++) {
            int x = sx << 4 | (i & 15);
            int z = sz << 4 | (i >> 4 & 15);
            int y = sy << 4 | (i >> 8 & 15);
            // Outside the mine box but inside a known section: solid rock.
            sectionCells[i] = (char) (inside(x, y, z) ? cell(x, y, z) : Cell.SOLID);
            // Like the real scanner: every solid block but the foreign ones is mine ground (stone / ore).
            boolean floor = Cell.hasCollision(sectionCells[i]) && !foreign.contains(Pos.pack(x, y, z));
            sectionOres[i] = (short) (ore(x, y, z) | (floor ? SectionSnapshot.FLOOR : 0));
        }
        return SectionSnapshot.of(sx, sy, sz, sectionCells, sectionOres, 1_000L);
    }
}
