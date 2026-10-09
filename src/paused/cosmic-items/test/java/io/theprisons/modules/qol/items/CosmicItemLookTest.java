package io.theprisons.modules.qol.items;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The paused item look's own checks (moved out of PrisonsItemsTest; the recognition tests stayed there). */
class CosmicItemLookTest {
    @Test
    void plainItemsNeedAClearMajority() {
        java.util.Map<String, Integer> counts = new java.util.HashMap<>();
        assertNull(VanillaBases.decide(counts));
        counts.put("theprisons:prisons/shard/simple", 2);
        assertNull(VanillaBases.decide(counts), "too few sightings");
        counts.put("theprisons:prisons/shard/simple", 9);
        assertEquals("theprisons:prisons/shard/simple", VanillaBases.decide(counts).toString());
        counts.put("theprisons:prisons/misc/money_note", 5);
        assertNull(VanillaBases.decide(counts), "shared base item stays vanilla");
    }

    @Test
    void everyRandomFamilyHasItsBlackTextures() {
        for (String family : PrisonsItems.RANDOM_FAMILIES) {
            java.io.File dir = new java.io.File(ItemTexturePacksTest.PAUSED + "/assets/theprisons/textures/item/prisons/" + family);
            String[] files = dir.list((d, n) -> n.endsWith(".png") && !n.startsWith("random_"));
            assertTrue(files != null && files.length > 0, family);
            for (String f : files) {
                assertTrue(new java.io.File(dir, "random_" + f).isFile(), family + "/" + f);
            }
        }
    }
}
