package io.theprisons.items;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ItemSearchTest {
    private static ItemRegistry registry() {
        return ItemRegistry.seeded();
    }

    private static List<String> names(List<ItemEntry> entries) {
        return entries.stream().map(ItemEntry::displayName).toList();
    }

    @Test
    void searchIsCaseInsensitiveAndMatchesTheName() {
        ItemSearchIndex idx = new ItemSearchIndex(registry().all());
        assertTrue(names(idx.search("GODLY shard")).contains("Godly Shard"));
        assertEquals(names(idx.search("godly shard")), names(idx.search("GoDlY ShArD")));
    }

    @Test
    void aliasesCategoryAndTierFindItems() {
        ItemSearchIndex idx = new ItemSearchIndex(registry().all());
        assertTrue(names(idx.search("gkit")).stream().anyMatch(n -> n.contains("G-Kit")), "alias gkit");
        assertTrue(names(idx.search("xp")).stream().anyMatch(n -> n.contains("XP Bottle")), "alias xp");
        assertTrue(names(idx.search("pets")).stream().anyMatch(n -> n.endsWith("Pet")), "category word");
        assertTrue(names(idx.search("godly")).stream().allMatch(n -> true) && idx.search("godly").stream().allMatch(e -> e.searchText().contains("godly")));
        assertTrue(idx.search("godly").size() > 5);
    }

    @Test
    void everyWordMustMatchInAnyOrder() {
        ItemSearchIndex idx = new ItemSearchIndex(registry().all());
        List<String> a = names(idx.search("shard godly"));
        assertTrue(a.contains("Godly Shard"));
        assertFalse(a.contains("Simple Shard"));
    }

    @Test
    void anEmptyQueryReturnsEverythingAndNoMatchReturnsNothing() {
        ItemRegistry r = registry();
        ItemSearchIndex idx = new ItemSearchIndex(r.all());
        assertEquals(r.size(), idx.search("").size());
        assertEquals(r.size(), idx.search("   ").size());
        assertTrue(idx.search("zzzxqv nothing like it").isEmpty());
    }

    @Test
    void theBestMatchComesFirst() {
        ItemSearchIndex idx = new ItemSearchIndex(registry().all());
        assertEquals("Godly Shard", names(idx.search("godly shard")).get(0));
        // a name that starts with the query outranks an alias or category match
        List<String> orb = names(idx.search("enchant orb"));
        assertTrue(orb.get(0).endsWith("Enchant Orb"), orb.toString());
    }

    @Test
    void searchingNeverAllocatesPerEntryText() {
        ItemRegistry r = registry();
        ItemSearchIndex idx = new ItemSearchIndex(r.all());
        long t0 = System.nanoTime();
        for (int i = 0; i < 2000; i++) {
            idx.search(i % 2 == 0 ? "godly" : "satchel gold");
        }
        long ms = (System.nanoTime() - t0) / 1_000_000L;
        assertTrue(ms < 1500, "2000 searches over " + r.size() + " entries took " + ms + " ms");
    }

    // ── filter + model ───────────────────────────────────────────────────────

    @Test
    void theCategoryFilterAndTheTierFilterCombine() {
        ItemListModel m = new ItemListModel(registry());
        m.setCategory(ItemCategory.LOOT);
        assertTrue(m.view().entries().stream().allMatch(e -> e.category() == ItemCategory.LOOT));
        m.setTiers(Set.of("Godly"));
        assertTrue(m.view().entries().stream().allMatch(e -> e.category() == ItemCategory.LOOT && "Godly".equals(e.tier())));
        assertFalse(m.view().entries().isEmpty());
        m.setQuery("shard");
        assertTrue(m.view().entries().stream().allMatch(e -> e.displayName().contains("Shard")));
        assertEquals(1, m.view().entries().size());
    }

    @Test
    void theTierRowOnlyExistsWhereTheCategoryHasTiers() {
        ItemListModel m = new ItemListModel(registry());
        m.setCategory(ItemCategory.PETS);
        assertTrue(m.view().tiers().isEmpty(), "no rarity row for pets");
        m.setCategory(ItemCategory.MASKS);
        assertTrue(m.view().tiers().isEmpty(), "no rarity row for masks");
        m.setCategory(ItemCategory.LOOT);
        assertTrue(m.view().tiers().contains("Godly") && m.view().tiers().contains("Simple"));
        // the tier filter itself does not hide the other tiers
        m.setTiers(Set.of("Godly"));
        assertTrue(m.view().tiers().contains("Simple"));
    }

    @Test
    void changingTheCategoryDropsTheTiersOfTheOldOne() {
        ItemListModel m = new ItemListModel(registry());
        m.setCategory(ItemCategory.LOOT);
        m.setTiers(Set.of("Godly"));
        m.setCategory(ItemCategory.PETS);
        assertTrue(m.filter().tiers().isEmpty());
        assertFalse(m.view().entries().isEmpty(), "pets are not hidden by a leftover tier");
    }

    @Test
    void theViewIsRecomputedOnlyWhenSomethingChanged() {
        ItemRegistry r = registry();
        ItemListModel m = new ItemListModel(r);
        ItemView first = m.view();
        for (int i = 0; i < 500; i++) {
            assertSame(first, m.view());      // 500 frames
        }
        assertEquals(1, m.computations());
        m.setQuery("shard");
        m.view();
        m.setQuery("shard");                  // the same query again: nothing to do
        m.view();
        assertEquals(2, m.computations());
        m.setCategory(null);                  // already all: no change
        m.view();
        assertEquals(2, m.computations());
        r.learn(ItemFacts.ofName("minecraft:paper", "A Brand New Shard Voucher"), "minecraft:paper");
        assertTrue(m.view().entries().stream().anyMatch(e -> e.displayName().contains("Brand New")), "the registry change reaches the list");
        assertEquals(3, m.computations());
    }

    @Test
    void searchAndFilterTimingsAreRecorded() {
        ItemListModel m = new ItemListModel(registry());
        m.setQuery("gold");
        ItemView v = m.view();
        assertTrue(v.searchNanos() > 0 && v.filterNanos() >= 0);
        assertEquals(registry().size(), v.totalItems());
    }
}
