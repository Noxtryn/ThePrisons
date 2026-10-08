package io.theprisons.testing;

import io.theprisons.modules.qol.bandit.combat.Terrain;

/**
 * A flat test arena from characters, one per block, x to the right, z downwards, origin at the top-left corner.
 * {@code .} floor, {@code ^} floor one block higher (a step that needs a jump), {@code #} wall, {@code ' '} a hole (a drop), {@code ~} hazard (lava), {@code ?} unknown, anything outside is unknown.
 */
public final class GridTerrain implements Terrain {
    private final String[] rows;

    public GridTerrain(String... rows) {
        this.rows = rows;
    }

    /** An open square arena of {@code size} x {@code size} floor blocks. */
    public static GridTerrain open(int size) {
        String[] r = new String[size];
        String row = ".".repeat(size);
        java.util.Arrays.fill(r, row);
        return new GridTerrain(r);
    }

    public char at(int x, int z) {
        if (z < 0 || z >= rows.length || x < 0 || x >= rows[z].length()) {
            return '?';
        }
        return rows[z].charAt(x);
    }

    /** A copy with one block changed. */
    public GridTerrain with(int x, int z, char c) {
        String[] copy = rows.clone();
        StringBuilder sb = new StringBuilder(copy[z]);
        sb.setCharAt(x, c);
        copy[z] = sb.toString();
        return new GridTerrain(copy);
    }

    public int width() {
        return rows.length == 0 ? 0 : rows[0].length();
    }

    public int height() {
        return rows.length;
    }

    @Override
    public Ray cast(double x, double y, double z, double dirX, double dirZ, double maxDist) {
        double len = Math.hypot(dirX, dirZ);
        if (len < 1e-9 || maxDist <= 0.0D) {
            return new Ray(0.0D, Stop.CLEAR, 0.0D);
        }
        double ux = dirX / len;
        double uz = dirZ / len;
        double walked = 0.0D;
        double step = 0.1D; // fine enough that "free" is the real distance to the edge
        double jumpAt = -1.0D;
        int level = at((int) Math.floor(x), (int) Math.floor(z)) == '^' ? 1 : 0;
        int startLevel = level;
        while (walked < maxDist - 1e-9) {
            double next = Math.min(maxDist, walked + step);
            char c = at((int) Math.floor(x + ux * next), (int) Math.floor(z + uz * next));
            Stop stop = switch (c) {
                case '#' -> Stop.WALL;
                case ' ' -> Stop.DROP;
                case '~' -> Stop.HAZARD;
                case '?' -> Stop.UNKNOWN;
                default -> null;
            };
            if (stop != null) {
                return new Ray(walked, stop, level - startLevel, jumpAt);
            }
            if (c == '^' && level == 0) {
                level = 1;
                if (jumpAt < 0.0D) {
                    jumpAt = walked;
                }
            } else if (c == '.' && level == 1) {
                level = 0;
            }
            walked = next;
        }
        return new Ray(maxDist, Stop.CLEAR, level - startLevel, jumpAt);
    }

    /** True when the straight line between two points crosses no wall (a line of sight on this arena). */
    public boolean sees(double x1, double z1, double x2, double z2) {
        double dx = x2 - x1;
        double dz = z2 - z1;
        double d = Math.hypot(dx, dz);
        for (double t = 0.0D; t < d; t += 0.5D) {
            char c = at((int) Math.floor(x1 + dx / d * t), (int) Math.floor(z1 + dz / d * t));
            if (c == '#') {
                return false;
            }
        }
        return true;
    }
}
