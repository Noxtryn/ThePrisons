package io.theprisons.modules.mining.ore;

import io.theprisons.core.nav.Pos;
import io.theprisons.core.nav.Walkability;

import java.util.Arrays;

/**
 * The tunnel around the player as floor and wall, from the shape of the ground only - never from block ids (walls,
 * floor and ceiling are all the same ore / stone).
 *
 * <ol>
 *     <li><b>Floor candidates:</b> columns the player can stand in (a solid block below, 2 blocks of room above), reached
 *     from the player's column over steps of at most 1 up and 3 down. Everything else is wall.</li>
 *     <li><b>Wall or way up?</b> From every candidate the ground is followed outwards in the 4 directions. When it rises
 *     fast over a short width - {@value #FAST_RISE} blocks within {@value #FAST_RUN}, or {@value #WALL_RISE} within
 *     {@value #WALL_RUN} - it is the foot of a wall (a bowl-shaped tunnel side, a stepped cave wall) - unless the ground
 *     then goes on flat for {@value #FLAT_TOP} blocks (a stair or ramp onto a terrace: still a way). The foot of a wall
 *     and everything above it count as wall.</li>
 * </ol>
 *
 * Rebuilt when the player enters another block (radius {@value #RADIUS}, about 2400 columns).
 */
final class TunnelMap {
    static final int RADIUS = 24;
    static final int FAST_RISE = 2;
    static final int FAST_RUN = 2;
    static final int WALL_RISE = 3;
    static final int WALL_RUN = 3;
    static final int FLAT_TOP = 3;
    /** A stair onto a terrace may be this high in all (single steps). */
    static final int MAX_STAIR = 4;
    private static final int SIZE = 2 * RADIUS + 1;
    private static final int NONE = Integer.MIN_VALUE;
    private static final int[][] DIRECTIONS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

    /** Feet height of each floor candidate ({@link #NONE} = wall). */
    private final int[] height = new int[SIZE * SIZE];
    private final boolean[] way = new boolean[SIZE * SIZE];
    private final int[] queue = new int[SIZE * SIZE];
    private int x0;
    private int z0;
    private long key = Long.MIN_VALUE;

    void update(Walkability walk, int px, int feetY, int pz) {
        long k = Pos.pack(px, feetY, pz);
        if (k == key) {
            return;
        }
        key = k;
        x0 = px - RADIUS;
        z0 = pz - RADIUS;
        Arrays.fill(height, NONE);
        int start = (pz - z0) * SIZE + (px - x0);
        int startY = TunnelSteer.standY(walk, px, feetY, pz);
        height[start] = startY == NONE ? feetY : startY;
        int head = 0;
        int tail = 0;
        queue[tail++] = start;
        while (head < tail) {
            int i = queue[head++];
            int cx = i % SIZE;
            int cz = i / SIZE;
            for (int[] d : DIRECTIONS) {
                int nx = cx + d[0];
                int nz = cz + d[1];
                if (nx < 0 || nz < 0 || nx >= SIZE || nz >= SIZE) {
                    continue;
                }
                int n = nz * SIZE + nx;
                if (height[n] != NONE) {
                    continue;
                }
                int y = TunnelSteer.standY(walk, x0 + nx, height[i], z0 + nz);
                if (y != NONE) {
                    height[n] = y;
                    queue[tail++] = n;
                }
            }
        }
        // Wall feet, then everything uphill from them (the slope above the foot is wall too).
        head = 0;
        tail = 0;
        for (int i = 0; i < way.length; i++) {
            way[i] = height[i] != NONE;
            if (way[i] && wallFoot(i % SIZE, i / SIZE)) {
                way[i] = false;
                queue[tail++] = i;
            }
        }
        while (head < tail) {
            int i = queue[head++];
            int cx = i % SIZE;
            int cz = i / SIZE;
            for (int[] d : DIRECTIONS) {
                int nx = cx + d[0];
                int nz = cz + d[1];
                if (nx < 0 || nz < 0 || nx >= SIZE || nz >= SIZE) {
                    continue;
                }
                int n = nz * SIZE + nx;
                if (way[n] && height[n] > height[i]) {
                    way[n] = false;
                    queue[tail++] = n;
                }
            }
        }
        // The player's own column is never wall (it may stand at the foot of one).
        way[start] = true;
    }

    /** The ground rises fast next to this column and does not go on flat at the top: the foot of a wall. */
    private boolean wallFoot(int cx, int cz) {
        int base = height[cz * SIZE + cx];
        for (int[] d : DIRECTIONS) {
            int prev = base;
            int k = 1;
            boolean steep = false;
            for (; k <= WALL_RUN; k++) {
                int h = heightAt(cx + d[0] * k, cz + d[1] * k);
                if (h == NONE || h < prev - 1 || h > prev + 1) {
                    break;
                }
                prev = h;
                int rise = h - base;
                if (rise >= FAST_RISE && k <= FAST_RUN || rise >= WALL_RISE) {
                    steep = true;
                    break;
                }
            }
            if (steep && !flatOnTop(cx, cz, d, k, base)) {
                return true;
            }
        }
        return false;
    }

    /** From step {@code k} on in direction {@code d}: a few more single steps up, then {@value #FLAT_TOP} flat blocks. */
    private boolean flatOnTop(int cx, int cz, int[] d, int k, int base) {
        int prev = heightAt(cx + d[0] * k, cz + d[1] * k);
        int flat = 0;
        for (int j = k + 1; j <= k + MAX_STAIR + FLAT_TOP; j++) {
            int h = heightAt(cx + d[0] * j, cz + d[1] * j);
            if (h == NONE) {
                return false;
            }
            if (h == prev) {
                if (++flat >= FLAT_TOP) {
                    return true;
                }
            } else if (h == prev + 1 && flat == 0 && h - base <= MAX_STAIR) {
                prev = h;
            } else {
                return false;
            }
        }
        return false;
    }

    private int heightAt(int cx, int cz) {
        if (cx < 0 || cz < 0 || cx >= SIZE || cz >= SIZE) {
            return NONE;
        }
        return height[cz * SIZE + cx];
    }

    /** Way (floor of the tunnel); false for walls and outside the map. */
    boolean way(double x, double z) {
        int cx = (int) Math.floor(x) - x0;
        int cz = (int) Math.floor(z) - z0;
        return cx >= 0 && cz >= 0 && cx < SIZE && cz < SIZE && way[cz * SIZE + cx];
    }

    /** Inside the map (outside nothing is known: no wall assumed). */
    boolean known(double x, double z) {
        int cx = (int) Math.floor(x) - x0;
        int cz = (int) Math.floor(z) - z0;
        return cx >= 0 && cz >= 0 && cx < SIZE && cz < SIZE;
    }
}
