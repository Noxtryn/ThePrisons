package com.freelocs.theprisons.core.nav;

import org.jspecify.annotations.Nullable;

import java.util.function.LongPredicate;

/**
 * Where can the player stand to hit a block? A standing node qualifies when the eye ({@value Walkability#EYE_HEIGHT}
 * above the feet) is within reach of a face turned towards it and the sight line to that face crosses no cell that
 * stops the crosshair. Blocks whose removal would dig an inescapable hole ({@link MiningSafety}) and the floor right
 * around the spot are never planned. This is a planning estimate on snapshot data; the block breaker re-checks with Minecraft's
 * real raycast before breaking.
 */
public final class ReachSpots {
    private static final double FACE_INSET = 0.45D;
    private static final int[][] FACES = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};

    private ReachSpots() {
    }

    /** A standing node from which {@code block} can be hit, with its travel cost and the face centre to aim at. */
    public record Spot(long block, long node, double feet, double pathCost, double aimX, double aimY, double aimZ) {
    }

    /**
     * Cheapest reachable standing node (by flood cost) that sees the block.
     *
     * @param excludedNodes nodes to skip (e.g. spots that failed before)
     */
    public static @Nullable Spot best(VoxelView view, PathSearch.Flood flood, long block, double reach, LongPredicate excludedNodes) {
        return best(view, flood, block, reach, excludedNodes, -90.0F, 90.0F);
    }

    /**
     * @param minPitch / maxPitch view band the aim must lie in (degrees, negative = up); -90 / 90 = any direction
     */
    public static @Nullable Spot best(VoxelView view, PathSearch.Flood flood, long block, double reach, LongPredicate excludedNodes,
                                      float minPitch, float maxPitch) {
        int bx = Pos.x(block);
        int by = Pos.y(block);
        int bz = Pos.z(block);
        if (!MiningSafety.keepsFloor(view, bx, by, bz)) {
            return null;
        }
        int horizontal = (int) Math.ceil(reach) + 1;
        Spot best = null;
        double bestCost = Double.POSITIVE_INFINITY;
        // Feet can be up to reach + eye height below the block and one block above it.
        for (int y = by - horizontal - 1; y <= by + 1; y++) {
            for (int x = bx - horizontal; x <= bx + horizontal; x++) {
                for (int z = bz - horizontal; z <= bz + horizontal; z++) {
                    long node = Pos.pack(x, y, z);
                    double pathCost = flood.cost(node);
                    if (pathCost >= bestCost || excludedNodes.test(node)) {
                        continue;
                    }
                    Spot spot = evaluate(view, block, node, flood.feet(node), pathCost, reach, minPitch, maxPitch);
                    if (spot != null) {
                        best = spot;
                        bestCost = pathCost;
                    }
                }
            }
        }
        return best;
    }

    /** Checks a single standing position; {@code null} when the block cannot be hit from it. */
    public static @Nullable Spot evaluate(VoxelView view, long block, long node, double feet, double pathCost, double reach) {
        return evaluate(view, block, node, feet, pathCost, reach, -90.0F, 90.0F);
    }

    public static @Nullable Spot evaluate(VoxelView view, long block, long node, double feet, double pathCost, double reach,
                                          float minPitch, float maxPitch) {
        if (Pos.y(block) < feet && Math.abs(Pos.x(block) - Pos.x(node)) <= 1 && Math.abs(Pos.z(block) - Pos.z(node)) <= 1) {
            // Floor around the spot: mining it would dig a hole under / next to the player that can trap them.
            // Such blocks are mined from a spot further away.
            return null;
        }
        if (!MiningSafety.keepsFloor(view, Pos.x(block), Pos.y(block), Pos.z(block))) {
            return null;
        }
        double ex = Pos.x(node) + 0.5D;
        double ey = feet + Walkability.EYE_HEIGHT;
        double ez = Pos.z(node) + 0.5D;
        double cx = Pos.x(block) + 0.5D;
        double cy = Pos.y(block) + 0.5D;
        double cz = Pos.z(block) + 0.5D;
        Spot best = null;
        double bestDistance = Double.POSITIVE_INFINITY;
        for (int[] face : FACES) {
            // The neighbour on that side must be open, otherwise the face is covered.
            if (!VoxelView.open(view.cell(Pos.x(block) + face[0], Pos.y(block) + face[1], Pos.z(block) + face[2]))) {
                continue;
            }
            double fx = cx + face[0] * FACE_INSET;
            double fy = cy + face[1] * FACE_INSET;
            double fz = cz + face[2] * FACE_INSET;
            // Only faces turned towards the eye can be hit.
            if ((ex - fx) * face[0] + (ey - fy) * face[1] + (ez - fz) * face[2] <= 0.0D) {
                continue;
            }
            double dx = fx - ex;
            double dy = fy - ey;
            double dz = fz - ez;
            double distance = dx * dx + dy * dy + dz * dz;
            if (distance > reach * reach || distance >= bestDistance) {
                continue;
            }
            if (minPitch > -90.0F || maxPitch < 90.0F) {
                double pitch = -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
                if (pitch < minPitch || pitch > maxPitch) {
                    continue;
                }
            }
            if (SightLine.clear(view, ex, ey, ez, fx, fy, fz, block)) {
                best = new Spot(block, node, feet, pathCost, fx, fy, fz);
                bestDistance = distance;
            }
        }
        return best;
    }
}
