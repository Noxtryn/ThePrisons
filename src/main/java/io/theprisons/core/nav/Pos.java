package io.theprisons.core.nav;

/**
 * Packed block positions using the same bit layout as {@code BlockPos.asLong()}, so packed values can be
 * exchanged with Minecraft code directly. Kept free of Minecraft classes so the navigation core can be unit tested.
 */
public final class Pos {
    private static final int BITS_XZ = 26;
    private static final int BITS_Y = 12;
    private static final long MASK_XZ = (1L << BITS_XZ) - 1L;
    private static final long MASK_Y = (1L << BITS_Y) - 1L;
    private static final int SHIFT_Z = BITS_Y;
    private static final int SHIFT_X = BITS_Y + BITS_XZ;

    private Pos() {
    }

    public static long pack(int x, int y, int z) {
        return ((long) x & MASK_XZ) << SHIFT_X | ((long) y & MASK_Y) | ((long) z & MASK_XZ) << SHIFT_Z;
    }

    public static int x(long packed) {
        return (int) (packed >> SHIFT_X);
    }

    public static int y(long packed) {
        return (int) (packed << (64 - BITS_Y) >> (64 - BITS_Y));
    }

    public static int z(long packed) {
        return (int) (packed << (64 - SHIFT_X) >> (64 - BITS_XZ));
    }

    public static long offset(long packed, int dx, int dy, int dz) {
        return pack(x(packed) + dx, y(packed) + dy, z(packed) + dz);
    }

    /** Key of the 16³ section containing the block. */
    public static long sectionOf(int x, int y, int z) {
        return pack(x >> 4, y >> 4, z >> 4);
    }

    public static long sectionOf(long packed) {
        return sectionOf(x(packed), y(packed), z(packed));
    }


    public static String toString(long packed) {
        return x(packed) + " " + y(packed) + " " + z(packed);
    }
}
