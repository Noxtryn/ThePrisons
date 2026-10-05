package io.theprisons.core.nav;

/**
 * Read access to extracted block data. Implementations used off-thread must be backed by immutable data.
 */
public interface VoxelView {
    /** Packed {@link Cell} value or {@link Cell#UNKNOWN}. */
    int cell(int x, int y, int z);

    /** Target block key (see {@code BlockKeys}) or 0 when the block is not a target. */
    default int ore(int x, int y, int z) {
        return 0;
    }

    /**
     * Stone, deepslate or an ore: the only ground the macro walks on (everything else is taboo). Views without that
     * knowledge (tests) say true.
     */
    default boolean mineFloor(int x, int y, int z) {
        return true;
    }

    /**
     * True when a player could see the block: at least one face touches a known cell that is not a full cube (air,
     * slabs, torches ...). Buried ores are never "seen".
     */
    default boolean exposed(int x, int y, int z) {
        return open(cell(x + 1, y, z)) || open(cell(x - 1, y, z)) || open(cell(x, y + 1, z))
                || open(cell(x, y - 1, z)) || open(cell(x, y, z + 1)) || open(cell(x, y, z - 1));
    }

    static boolean open(int cell) {
        return cell != Cell.UNKNOWN && !Cell.isFull(cell);
    }
}
