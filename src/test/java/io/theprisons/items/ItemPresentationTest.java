package io.theprisons.items;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ItemPresentationTest {
    @Test
    void rarityPaletteCanonicalisesKnownCosmicTiersAndKeepsUnknownsNeutral() {
        assertEquals("Godly", ItemRarity.canonical("gOdLy"));
        assertEquals(0xFF8DEB, ItemRarity.rgb("godly"));
        assertEquals("RARITY  GODLY", ItemRarity.badge("Godly"));
        assertEquals(ItemRarity.FALLBACK_RGB, ItemRarity.rgb("invented-tier"));
        assertEquals("", ItemRarity.badge("invented-tier"));
    }

    @Test
    void tooltipUsesTheExactCatalogIdentityAndDoesNotInventMarketData() {
        ItemFacts facts = Samples.facts("Godly Shard", "minecraft:prismarine_shard", "shard", java.util.Map.of());
        AtomicReference<String> requested = new AtomicReference<>();
        List<String> lines = ItemMarketTooltip.lines(facts, key -> {
            requested.set(key);
            return List.of("Fair    $1,250 (High)", "Source  Sales · 24h sales", "Updated 2m ago");
        });
        assertEquals(ItemIdentity.of(facts).catalogKey(), requested.get());
        assertEquals("MARKET DATA", lines.getFirst());
        assertTrue(lines.contains("Updated 2m ago"));
        assertTrue(ItemMarketTooltip.lines(facts, key -> List.of()).isEmpty());
        assertFalse(ItemMarketTooltip.lines("", key -> List.of("not used")).contains("not used"));
    }

    @Test
    void marketBlockHasOneStableHeaderForUiDeduplication() {
        List<String> lines = ItemMarketTooltip.lines("shard|godly", key -> List.of("Fair    $1,250 (High)"));
        assertEquals(1, lines.stream().filter("MARKET DATA"::equals).count());
        assertEquals("MARKET DATA", lines.getFirst());
    }
}
