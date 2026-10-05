package io.theprisons.modules.qol.market;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PriceBookTest {
    @Test
    void moneyAsCosmicWritesIt() {
        assertEquals(20_000_000D, Money.parse("$20,000,000"));
        assertEquals(20_000_000D, Money.parse("Price: 20m"));
        assertEquals(1_500D, Money.parse("1.5k"));
        assertEquals(2_600_000D, Money.parse("Balance $2.60M"));
        assertEquals(-1D, Money.parse("no price"));
        assertEquals("20.0M", Money.compact(20_000_000D));
    }

    @Test
    void lastSeenPriceAndTheEnergyRate() {
        PriceBook book = new PriceBook();
        book.seen("mask_sentinel", "Sentinel Mask", "Cosmetics", 30e6, 1_000L, "ah", true);
        book.seen("mask_sentinel", "Sentinel Mask", "Cosmetics", 25e6, 1_000L, "ah", false);
        assertEquals(25e6, book.get("mask_sentinel").price(), "the lowest of a scan");
        book.seen("mask_sentinel", "Sentinel Mask", "", 28e6, 400_000L, "ah", true);
        assertEquals(28e6, book.get("mask_sentinel").price(), "a new scan replaces the old price");
        assertEquals("Cosmetics", book.get("mask_sentinel").category());
        // The user's example: 2k money per 1k energy -> an item for 20m money costs 10m energy.
        book.energyOffer(2_000D, 1_000D, 0L);
        assertEquals(10e6, book.inEnergy(20e6), 1e-6);
    }

    @Test
    void aRandomRewardIsTheAverageOfItsKind() {
        PriceBook book = new PriceBook();
        book.seen("a", "Sentinel Mask", "Cosmetics", 10e6, 0L, "ah", true);
        book.seen("b", "Pirate Mask", "Cosmetics", 30e6, 0L, "ah", true);
        book.seen("c", "Iron Pickaxe", "Mining", 5e6, 0L, "ah", true);
        assertEquals(20e6, book.average(PriceBook.kindOf("Random Mask")));
        assertEquals(20e6, book.average(PriceBook.kindOf("Mystery Masks")));
        assertEquals(-1D, book.average(PriceBook.kindOf("Random Pet")));
    }
}
