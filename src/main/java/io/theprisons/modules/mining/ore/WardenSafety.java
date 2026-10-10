package io.theprisons.modules.mining.ore;

import io.theprisons.core.nav.Pos;
import it.unimi.dsi.fastutil.longs.AbstractLong2DoubleMap;
import it.unimi.dsi.fastutil.longs.Long2DoubleMap;
import it.unimi.dsi.fastutil.objects.ObjectSet;

/** Hard keep-away rule, shared by steering and pathfinding; independent of guard-tax excursions. */
final class WardenSafety {
    static final double DISTANCE = 15.0D;
    static final double HEIGHT = 64.0D;

    private WardenSafety() {}

    /** Checks the whole segment, including chords whose endpoints are both outside the circle.
     * Starting inside (e.g. after a teleport) permits only movement away from each nearby warden. */
    static boolean blocks(double[][] wardens, double x, double y, double z, double toX, double toZ) {
        double dx = toX - x, dz = toZ - z;
        double lengthSq = dx * dx + dz * dz;
        if (lengthSq == 0.0D) return false;
        for (double[] w : wardens) {
            if (Math.abs(w[1] - y) > HEIGHT) continue;
            double wx = x - w[0], wz = z - w[2];
            double startSq = wx * wx + wz * wz;
            double dot = wx * dx + wz * dz;
            if (startSq < DISTANCE * DISTANCE) {
                if (dot < 0.0D) return true;
            } else {
                double t = Math.clamp(-dot / lengthSq, 0.0D, 1.0D);
                double cx = wx + t * dx, cz = wz + t * dz;
                if (cx * cx + cz * cz < DISTANCE * DISTANCE) return true;
            }
        }
        return false;
    }

    /** Frozen per-job obstacle map. Cell corners, not just centres, stay outside the safety radius. */
    static Long2DoubleMap penalties(Long2DoubleMap base, double[][] wardens, double x, double y, double z) {
        if (wardens.length == 0) return base;
        double[][] snapshot = new double[wardens.length][];
        for (int i = 0; i < snapshot.length; i++) snapshot[i] = wardens[i].clone();
        return new AbstractLong2DoubleMap() {
            @Override public double get(long key) {
                double cost = base.get(key);
                if (cost == Double.POSITIVE_INFINITY) return cost;
                double cx = Pos.x(key) + 0.5D, cz = Pos.z(key) + 0.5D;
                for (double[] w : snapshot) {
                    if (Math.abs(w[1] - Pos.y(key)) > HEIGHT) continue;
                    double radius = Math.min(DISTANCE + Math.sqrt(0.5D), Math.hypot(w[0] - x, w[2] - z));
                    if (Math.hypot(w[0] - cx, w[2] - cz) < radius) return Double.POSITIVE_INFINITY;
                }
                return cost;
            }
            @Override public int size() { return Math.max(1, base.size()); }
            @Override public boolean containsKey(long key) { return get(key) != 0.0D; }
            @Override public ObjectSet<Long2DoubleMap.Entry> long2DoubleEntrySet() {
                throw new UnsupportedOperationException("computed per node");
            }
        };
    }
}
