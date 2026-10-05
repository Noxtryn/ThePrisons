package com.freelocs.theprisons.core.nav;

/**
 * Voxel traversal (Amanatides &amp; Woo) from an eye position to a point on a target block.
 */
public final class SightLine {
    private SightLine() {
    }

    /**
     * True when the segment reaches the target block without crossing a cell that stops the crosshair (non-empty
     * outline shape, or unknown data). The eye cell itself is ignored.
     */
    public static boolean clear(VoxelView view, double x0, double y0, double z0, double x1, double y1, double z1, long target) {
        int x = (int) Math.floor(x0);
        int y = (int) Math.floor(y0);
        int z = (int) Math.floor(z0);
        int tx = Pos.x(target);
        int ty = Pos.y(target);
        int tz = Pos.z(target);
        double dx = x1 - x0;
        double dy = y1 - y0;
        double dz = z1 - z0;
        int stepX = dx > 0 ? 1 : -1;
        int stepY = dy > 0 ? 1 : -1;
        int stepZ = dz > 0 ? 1 : -1;
        double tDeltaX = dx == 0 ? Double.POSITIVE_INFINITY : Math.abs(1.0D / dx);
        double tDeltaY = dy == 0 ? Double.POSITIVE_INFINITY : Math.abs(1.0D / dy);
        double tDeltaZ = dz == 0 ? Double.POSITIVE_INFINITY : Math.abs(1.0D / dz);
        double tMaxX = dx == 0 ? Double.POSITIVE_INFINITY : ((stepX > 0 ? x + 1 - x0 : x0 - x) * tDeltaX);
        double tMaxY = dy == 0 ? Double.POSITIVE_INFINITY : ((stepY > 0 ? y + 1 - y0 : y0 - y) * tDeltaY);
        double tMaxZ = dz == 0 ? Double.POSITIVE_INFINITY : ((stepZ > 0 ? z + 1 - z0 : z0 - z) * tDeltaZ);

        for (int guard = 0; guard < 64; guard++) {
            if (x == tx && y == ty && z == tz) {
                return true;
            }
            if (guard > 0 && Cell.blocksSight(view.cell(x, y, z))) {
                return false;
            }
            if (tMaxX < tMaxY && tMaxX < tMaxZ) {
                if (tMaxX > 1.0D) {
                    break;
                }
                x += stepX;
                tMaxX += tDeltaX;
            } else if (tMaxY < tMaxZ) {
                if (tMaxY > 1.0D) {
                    break;
                }
                y += stepY;
                tMaxY += tDeltaY;
            } else {
                if (tMaxZ > 1.0D) {
                    break;
                }
                z += stepZ;
                tMaxZ += tDeltaZ;
            }
        }
        // The segment ends inside the target block, so falling out of the loop is a numeric edge case.
        return x == tx && y == ty && z == tz;
    }
}
