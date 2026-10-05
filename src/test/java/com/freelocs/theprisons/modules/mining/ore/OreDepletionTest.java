package com.freelocs.theprisons.modules.mining.ore;

import com.freelocs.theprisons.core.world.BlockKeys;
import com.freelocs.theprisons.testing.TestMine;
import org.junit.jupiter.api.Test;

import java.util.function.IntPredicate;

import static org.junit.jupiter.api.Assertions.assertEquals;

class OreDepletionTest {
    private static final IntPredicate REDSTONE = key -> key == BlockKeys.key(TestMine.REDSTONE_ORE);

    @Test
    void countsOresThatTurnedIntoStoneAsMined() {
        TestMine mine = new TestMine(-20, -3, -20, 20, 6, 20).carve(-18, 1, -18, 18, 3, 18);
        for (int x = -9; x <= 10; x++) {
            for (int z = 0; z <= 4; z++) {
                mine.ore(x, 0, z, TestMine.REDSTONE_ORE);
            }
        }
        OreDepletion depletion = new OreDepletion();
        depletion.update(mine, REDSTONE, 0.5D, 1.0D, 0.5D);
        assertEquals(0.0D, depletion.minedShare(), 1.0E-9);
        // 65 of the 100 ores become stone.
        for (int x = -9; x <= 3; x++) {
            for (int z = 0; z <= 4; z++) {
                mine.fill(x, 0, z, x, 0, z);
            }
        }
        depletion.update(mine, REDSTONE, 0.5D, 1.0D, 0.5D);
        assertEquals(100, depletion.total());
        assertEquals(0.65D, depletion.minedShare(), 1.0E-9);
    }
}
