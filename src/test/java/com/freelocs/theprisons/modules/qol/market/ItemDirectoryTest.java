package com.freelocs.theprisons.modules.qol.market;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ItemDirectoryTest {
    @Test
    void everyVariantIsAnItemOfItsFamilyInRarityOrder() {
        Set<String> keys = new HashSet<>();
        for (ItemDirectory.Spec spec : ItemDirectory.all()) {
            int lastRank = -2;
            for (String name : spec.names()) {
                ItemIdentity.Id id = ItemIdentity.of(name, null);
                assertNotNull(id, name);
                assertTrue(keys.add(id.key()), "duplicate " + name);
                assertEquals(spec.family().toLowerCase(), id.family().toLowerCase(), name);
                assertTrue(id.rank() >= lastRank, "rarities lowest first: " + name);
                lastRank = id.rank();
            }
        }
        assertTrue(keys.size() > 200, "a complete directory: " + keys.size());
    }

    @Test
    void rareCandyIsGodlyOnlyAndShardsHaveEverySix() {
        for (ItemDirectory.Spec spec : ItemDirectory.all()) {
            if (spec.family().equals("Rare Candy")) {
                assertEquals(java.util.List.of("Godly Rare Candy"), spec.names());
            }
            if (spec.family().equals("Shard")) {
                assertEquals("Simple Shard", spec.names().get(0));
                assertEquals("Godly Shard", spec.names().get(5));
            }
        }
    }

    @Test
    void theKindsTheyLandIn() {
        Map<String, Integer> count = new TreeMap<>();
        for (ItemDirectory.Spec spec : ItemDirectory.all()) {
            for (String name : spec.names()) {
                MarketCategory.Sorted s = MarketCategory.of(name, null);
                count.merge(s.group().label + "/" + s.kind(), 1, Integer::sum);
            }
        }
        for (ItemDirectory.Spec spec : ItemDirectory.all()) {
            for (String name : spec.names()) {
                MarketCategory.Sorted s = MarketCategory.of(name, null);
                if (s.kind().equals("Other")) {
                    System.out.println("[uncategorised] " + name);
                }
            }
        }
        System.out.println("[directory] " + count);
        assertTrue(count.getOrDefault("Other/Other", 0) < 12, "few uncategorised: " + count.get("Other/Other"));
    }
}
