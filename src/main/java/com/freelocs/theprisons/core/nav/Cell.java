package com.freelocs.theprisons.core.nav;

/**
 * Compact 16 bit description of one block, extracted from its real BlockState on the client thread.
 *
 * <p>The collision interval describes the block's collision shape inside the player's footprint when the
 * player stands centred on that column ({@code x,z ∈ [0.2, 0.8]}, the 0.6 wide player box). It is stored in
 * 1/16 block units relative to the block's own y and may exceed 16 (fences and walls are 1.5 blocks tall).
 *
 * <pre>
 * bits 0-4   collision min y (1/16)
 * bits 5-9   collision max y (1/16, up to 31)
 * bit  10    has collision inside the player column
 * bit  11    outline shape is not empty (blocks the crosshair raycast)
 * bit  12    full cube
 * bit  13    dangerous to stand in / on (lava, fire, magma, cactus, ...)
 * bit  14    contains fluid
 * bit  15    breakable (not air, hardness >= 0)
 * </pre>
 *
 * {@link #UNKNOWN} (-1) marks blocks the client has no data for; it is never treated as air.
 */
public final class Cell {
    public static final int UNKNOWN = -1;
    public static final int AIR = 0;

    public static final int COLLISION = 1 << 10;
    public static final int OUTLINE = 1 << 11;
    public static final int FULL = 1 << 12;
    public static final int DANGER = 1 << 13;
    public static final int LIQUID = 1 << 14;
    public static final int BREAKABLE = 1 << 15;

    /** A plain full solid block (stone, ore, ...). */
    public static final int SOLID = encode(0, 16, COLLISION | OUTLINE | FULL | BREAKABLE);

    private Cell() {
    }

    public static int encode(int minY16, int maxY16, int flags) {
        int min = Math.max(0, Math.min(31, minY16));
        int max = Math.max(0, Math.min(31, maxY16));
        return (min | max << 5 | flags) & 0xFFFF;
    }

    public static boolean known(int cell) {
        return cell != UNKNOWN;
    }

    public static boolean hasCollision(int cell) {
        return cell != UNKNOWN && (cell & COLLISION) != 0;
    }

    public static int minY16(int cell) {
        return cell & 31;
    }

    public static int maxY16(int cell) {
        return cell >> 5 & 31;
    }


    /** True when the cell stops the crosshair raycast: a non-empty outline shape, or unknown data (never treated as air). */
    public static boolean blocksSight(int cell) {
        return cell == UNKNOWN || (cell & OUTLINE) != 0;
    }

    public static boolean isFull(int cell) {
        return cell != UNKNOWN && (cell & FULL) != 0;
    }

    public static boolean isDanger(int cell) {
        return cell != UNKNOWN && (cell & DANGER) != 0;
    }

    public static boolean isLiquid(int cell) {
        return cell != UNKNOWN && (cell & LIQUID) != 0;
    }


    /** Convenience for tests and synthetic data: a collision box from {@code minY} to {@code maxY} (1/16 units). */
    public static int partial(int minY16, int maxY16) {
        return encode(minY16, maxY16, COLLISION | OUTLINE | BREAKABLE);
    }
}
