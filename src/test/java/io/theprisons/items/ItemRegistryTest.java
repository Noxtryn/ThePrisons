package io.theprisons.items;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ItemRegistryTest {
    private static final ItemRegistry REGISTRY = ItemRegistry.seeded();

    @Test
    void theSeededRegistryHasUniqueEntriesWithPreparedSearchText() {
        assertTrue(REGISTRY.size() > 100, "size " + REGISTRY.size());
        Set<String> keys = new java.util.HashSet<>();
        for (ItemEntry e : REGISTRY.all()) {
            assertTrue(keys.add(e.key()), "duplicate " + e.key());
            assertFalse(e.searchText().isEmpty());
            assertEquals(e.searchText(), SearchText.normalize(e.searchText()), "already normalised: " + e.key());
        }
    }

    @Test
    void petsAndMasksHaveNoRarityRows() {
        int pets = 0;
        int masks = 0;
        for (ItemEntry e : REGISTRY.all()) {
            if (e.category() == ItemCategory.PETS || e.category() == ItemCategory.MASKS) {
                assertNull(e.tier(), e.displayName());
                assertFalse(e.meta().hasTiers(), e.displayName());
                pets += e.category() == ItemCategory.PETS ? 1 : 0;
                masks += e.category() == ItemCategory.MASKS ? 1 : 0;
            }
        }
        assertTrue(pets >= 5 && masks >= 5, "pets " + pets + " masks " + masks);
    }

    @Test
    void tieredFamiliesKeepTheirKnownTiersOnly() {
        List<ItemEntry> candy = REGISTRY.all().stream().filter(e -> e.family().equals("Rare Candy")).toList();
        assertEquals(1, candy.size());
        assertEquals("Godly", candy.get(0).tier());
        long shards = REGISTRY.all().stream().filter(e -> e.family().equals("Shard")).count();
        assertEquals(7, shards, "Simple .. Godly + Executive");
    }

    @Test
    void learningAnItemAddsItOnceAndIgnoresUpgradedPiecesAndKnownOnes() {
        ItemRegistry r = ItemRegistry.seeded();
        long rev = r.revision();
        assertFalse(r.learn(ItemFacts.ofName("minecraft:prismarine_shard", "Godly Shard"), "minecraft:prismarine_shard"), "known already");
        assertFalse(r.learn(ItemFacts.ofName("minecraft:iron_pickaxe", "Iron Pickaxe 17 III"), "minecraft:iron_pickaxe"), "an upgraded piece is no ware");
        assertEquals(rev, r.revision(), "nothing changed, the revision stays");
        assertTrue(r.learn(ItemFacts.ofName("minecraft:paper", "Totally New Voucher"), "minecraft:paper"));
        assertNotNull(r.get("totally new voucher|"));
        assertTrue(r.revision() > rev);
        assertFalse(r.learn(ItemFacts.ofName("minecraft:paper", "Totally New Voucher"), "minecraft:paper"));
    }

    @Test
    void theRegistryIsBounded() {
        ItemRegistry r = new ItemRegistry();
        for (int i = 0; i < ItemRegistry.MAX_ENTRIES + 50; i++) {
            r.learn(ItemFacts.ofName("minecraft:paper", "Voucher Number " + i + "x"), "minecraft:paper");
        }
        assertTrue(r.size() <= ItemRegistry.MAX_ENTRIES);
    }

    @Test
    void realCatalogItemsCanBeLearned() {
        ItemRegistry r = new ItemRegistry();
        int learned = 0;
        for (ItemFacts f : Samples.catalog().values()) {
            if (r.learn(f, f.vanillaId())) {
                learned++;
            }
        }
        assertTrue(learned >= 25, "learned " + learned);
        assertEquals(r.size(), learned);
    }
}
