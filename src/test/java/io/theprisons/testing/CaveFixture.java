package io.theprisons.testing;

import io.theprisons.core.nav.Cell;
import io.theprisons.core.nav.Pos;
import io.theprisons.core.nav.Walkability;

import java.util.Random;

/**
 * Shared ore-cave fixture (identical generator to the archived baseline benchmark): a worm-carved ore cave (chambers of varying height connected by
 * tunnels, uneven floors, ore veins in the walls). The same generator is ported for the new core so the before /
 * after benchmarks run on identical terrain.
 */
public final class CaveFixture {
    public static final int SIZE = 112;
    public static final int HEIGHT = 48;

    private CaveFixture() {
    }

    public static TestMine cave(long seed, int veins) {
        TestMine mine = new TestMine(0, 0, 0, SIZE, HEIGHT, SIZE);
        Random random = new Random(seed);
        // Worms: random walks that carve spheres; every worm starts inside an earlier one, so the cave is connected.
        double x = SIZE / 2.0D;
        double y = 14.0D;
        double z = SIZE / 2.0D;
        double[][] starts = new double[24][];
        int startCount = 0;
        for (int worm = 0; worm < 12; worm++) {
            if (worm > 0) {
                double[] from = starts[random.nextInt(startCount)];
                x = from[0];
                y = from[1];
                z = from[2];
            }
            double yaw = random.nextDouble() * Math.PI * 2.0D;
            double pitch = 0.0D;
            for (int step = 0; step < 70; step++) {
                yaw += (random.nextDouble() - 0.5D) * 0.6D;
                pitch = Math.max(-0.35D, Math.min(0.35D, pitch + (random.nextDouble() - 0.5D) * 0.25D));
                x = clamp(x + Math.cos(yaw) * 1.6D, 6, SIZE - 6);
                z = clamp(z + Math.sin(yaw) * 1.6D, 6, SIZE - 6);
                y = clamp(y + Math.sin(pitch) * 1.6D, 5, HEIGHT - 12);
                double radius = 1.6D + (step % 17 == 0 ? 2.5D : 0.0D) + random.nextDouble() * 1.2D;
                // Bigger spheres are lifted so their floor stays level with the tunnel floor: chambers are walkable,
                // not pits (a player can only climb 1.2 blocks).
                sphere(mine, x, y + (radius - 1.6D) * 0.8D, z, radius);
                if (step % 20 == 10 && startCount < starts.length) {
                    starts[startCount++] = new double[]{x, y, z};
                }
            }
            if (startCount == 0) {
                starts[startCount++] = new double[]{x, y, z};
            }
        }
        // Ore veins grow from wall blocks into the rock.
        int placed = 0;
        int guard = 0;
        while (placed < veins && guard++ < veins * 200) {
            int vx = 2 + random.nextInt(SIZE - 4);
            int vy = 2 + random.nextInt(HEIGHT - 4);
            int vz = 2 + random.nextInt(SIZE - 4);
            if (mine.cell(vx, vy, vz) != Cell.SOLID || !touchesAir(mine, vx, vy, vz)) {
                continue;
            }
            int size = 2 + random.nextInt(7);
            for (int i = 0; i < size; i++) {
                if (mine.cell(vx, vy, vz) == Cell.SOLID) {
                    mine.ore(vx, vy, vz, TestMine.REDSTONE_ORE);
                }
                int axis = random.nextInt(3);
                int dir = random.nextBoolean() ? 1 : -1;
                vx += axis == 0 ? dir : 0;
                vy += axis == 1 ? dir : 0;
                vz += axis == 2 ? dir : 0;
            }
            placed++;
        }
        return mine;
    }

    /** First air cell above a solid floor near the cave's first chamber. */
    public static long start(TestMine mine) {
        Walkability walk = new Walkability(mine, 3);
        for (int r = 0; r < 20; r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    for (int y = 4; y < HEIGHT - 2; y++) {
                        if (walk.isStandable(SIZE / 2 + dx, y, SIZE / 2 + dz)) {
                            return Pos.pack(SIZE / 2 + dx, y, SIZE / 2 + dz);
                        }
                    }
                }
            }
        }
        throw new IllegalStateException("no standable start");
    }

    private static void sphere(TestMine mine, double cx, double cy, double cz, double r) {
        for (int x = (int) Math.floor(cx - r); x <= cx + r; x++) {
            for (int y = (int) Math.floor(cy - r * 0.8D); y <= cy + r * 0.8D; y++) {
                for (int z = (int) Math.floor(cz - r); z <= cz + r; z++) {
                    double dx = x + 0.5D - cx;
                    double dy = (y + 0.5D - cy) / 0.8D;
                    double dz = z + 0.5D - cz;
                    if (dx * dx + dy * dy + dz * dz <= r * r && y > 1 && y < HEIGHT - 1) {
                        mine.set(x, y, z, Cell.AIR);
                    }
                }
            }
        }
    }

    private static boolean touchesAir(TestMine mine, int x, int y, int z) {
        return mine.cell(x + 1, y, z) == Cell.AIR || mine.cell(x - 1, y, z) == Cell.AIR || mine.cell(x, y + 1, z) == Cell.AIR
                || mine.cell(x, y - 1, z) == Cell.AIR || mine.cell(x, y, z + 1) == Cell.AIR || mine.cell(x, y, z - 1) == Cell.AIR;
    }

    private static double clamp(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
    }
}
