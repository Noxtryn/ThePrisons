package io.theprisons.modules.qol.bandit;

import io.theprisons.core.nav.Cell;
import io.theprisons.core.nav.VoxelView;
import io.theprisons.modules.qol.bandit.combat.Terrain;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The real {@link WorldTerrain} (the module's adapter over the path finder's walkability rules) on a small synthetic world. */
class WorldTerrainTest {
    /** A flat world: floor blocks up to y = 63 (feet at y = 64), air above; features are put in by hand. */
    private static class World implements VoxelView {
        final Map<Long, Integer> overrides = new HashMap<>();

        private static long key(int x, int y, int z) {
            return ((long) x & 0x3FFFFFFL) << 38 | ((long) z & 0x3FFFFFFL) << 12 | (y & 0xFFFL);
        }

        void set(int x, int y, int z, int cell) {
            overrides.put(key(x, y, z), cell);
        }

        void solidColumn(int x, int z, int fromY, int toY) {
            for (int y = fromY; y <= toY; y++) {
                set(x, y, z, Cell.SOLID);
            }
        }

        void air(int x, int z, int fromY, int toY) {
            for (int y = fromY; y <= toY; y++) {
                set(x, y, z, Cell.AIR);
            }
        }

        @Override
        public int cell(int x, int y, int z) {
            Integer o = overrides.get(key(x, y, z));
            if (o != null) {
                return o;
            }
            return y <= 63 ? Cell.SOLID : Cell.AIR;
        }
    }

    private static Terrain terrain(World w) {
        return new WorldTerrain(w);
    }

    @Test
    void openGroundIsClear() {
        Terrain.Ray ray = terrain(new World()).cast(0.5, 64.0, 0.5, 1, 0, 6.0);
        assertEquals(Terrain.Stop.CLEAR, ray.stop());
        assertEquals(6.0D, ray.free(), 1e-9);
    }

    @Test
    void aWallStopsTheProbeAtItsEdge() {
        World w = new World();
        w.solidColumn(4, 0, 64, 66);
        Terrain.Ray ray = terrain(w).cast(0.5, 64.0, 0.5, 1, 0, 8.0);
        assertEquals(Terrain.Stop.WALL, ray.stop());
        assertTrue(ray.free() >= 3.0D && ray.free() <= 3.6D, "free " + ray.free());
    }

    @Test
    void aStepOfOneBlockCanBeWalkedUpButTwoCannot() {
        World w = new World();
        for (int x = 3; x <= 9; x++) {
            w.set(x, 64, 0, Cell.SOLID); // floor +1 from x = 3 on (feet at 65)
        }
        Terrain.Ray up = terrain(w).cast(0.5, 64.0, 0.5, 1, 0, 6.0);
        assertEquals(Terrain.Stop.CLEAR, up.stop(), "a one block step is a jump: " + up);
        assertEquals(1.0D, up.heightChange(), 1e-9);
        World high = new World();
        high.solidColumn(3, 0, 64, 65); // two blocks high
        high.air(3, 0, 66, 70);
        Terrain.Ray tooHigh = terrain(high).cast(0.5, 64.0, 0.5, 1, 0, 6.0);
        assertTrue(tooHigh.stop() == Terrain.Stop.WALL || tooHigh.stop() == Terrain.Stop.STEP, "stop " + tooHigh.stop());
        assertTrue(tooHigh.free() < 3.0D);
    }

    @Test
    void aDeepHoleIsADrop() {
        World w = new World();
        for (int y = 55; y <= 63; y++) {
            w.set(3, y, 0, Cell.AIR);
            w.set(3, y, 1, Cell.AIR);
            w.set(3, y, -1, Cell.AIR);
        }
        Terrain.Ray ray = terrain(w).cast(0.5, 64.0, 0.5, 1, 0, 6.0);
        assertEquals(Terrain.Stop.DROP, ray.stop(), "stop " + ray);
        assertTrue(ray.free() < 3.0D);
    }

    @Test
    void aSmallDropIsWalkedDown() {
        World w = new World();
        w.set(3, 63, 0, Cell.AIR); // one block lower from x = 3 on
        w.set(4, 63, 0, Cell.AIR);
        w.set(5, 63, 0, Cell.AIR);
        w.set(6, 63, 0, Cell.AIR);
        Terrain.Ray ray = terrain(w).cast(0.5, 64.0, 0.5, 1, 0, 6.0);
        assertEquals(Terrain.Stop.CLEAR, ray.stop());
        assertEquals(-1.0D, ray.heightChange(), 1e-9);
    }

    @Test
    void lavaIsAHazard() {
        World w = new World();
        int lava = Cell.encode(0, 16, Cell.LIQUID | Cell.DANGER);
        for (int z = -1; z <= 1; z++) {
            w.set(3, 63, z, lava);
        }
        Terrain.Ray ray = terrain(w).cast(0.5, 64.0, 0.5, 1, 0, 6.0);
        assertEquals(Terrain.Stop.HAZARD, ray.stop(), "stop " + ray);
    }

    @Test
    void blocksTheClientDoesNotKnowStopTheProbeAsUnknown() {
        World w = new World();
        for (int y = 60; y <= 68; y++) {
            for (int z = -1; z <= 1; z++) {
                w.set(3, y, z, Cell.UNKNOWN);
            }
        }
        Terrain.Ray ray = terrain(w).cast(0.5, 64.0, 0.5, 1, 0, 6.0);
        assertEquals(Terrain.Stop.UNKNOWN, ray.stop(), "never assumed to be air: " + ray);
    }

    @Test
    void aLowCeilingStopsTheProbe() {
        World w = new World();
        w.set(3, 65, 0, Cell.SOLID); // a block over the head height: the space is only one block high
        Terrain.Ray ray = terrain(w).cast(0.5, 64.0, 0.5, 1, 0, 6.0);
        assertTrue(ray.stop() == Terrain.Stop.HEADROOM || ray.stop() == Terrain.Stop.WALL, "stop " + ray);
        assertTrue(ray.free() < 3.5D);
    }

    @Test
    void bandOfDifferentFloorBlocksIsStillWalkable() {
        // The ore macro only walks on stone; bandit land is not stone. The adapter must not care about the floor material.
        World w = new World() {
            @Override
            public boolean mineFloor(int x, int y, int z) {
                return false;
            }
        };
        Terrain.Ray ray = terrain(w).cast(0.5, 64.0, 0.5, 0, 1, 5.0);
        assertEquals(Terrain.Stop.CLEAR, ray.stop());
    }
}
