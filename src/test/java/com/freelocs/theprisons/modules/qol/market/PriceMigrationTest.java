package com.freelocs.theprisons.modules.qol.market;

import org.junit.jupiter.api.Test;

import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The real prices.json of 2026-10-05 (before the identity rework) read through the new book. */
class PriceMigrationTest {
    private static PriceBook load() throws URISyntaxException {
        PriceBook book = new PriceBook();
        PriceStore.load(Path.of(PriceMigrationTest.class.getResource("/market/prices-real.json").toURI()), book);
        return book;
    }

    @Test
    void oneEntryPerItemAndNoInventedOnes() throws URISyntaxException {
        PriceBook book = load();
        Set<String> names = new HashSet<>();
        for (PriceBook.Entry e : book.all()) {
            assertTrue(names.add(e.name().toLowerCase()), "duplicate " + e.name());
            assertFalse(e.name().toLowerCase().matches(".*\\brandom\\b.*"), e.name());
            assertFalse(e.name().matches(".*\\s[1-9]\\d*$"), "upgraded instance " + e.name());
        }
        assertTrue(book.all().size() < 351, "merged: " + book.all().size());
        assertEquals(1, book.all().stream().filter(e -> e.name().contains("Rare Candy")).count());
    }

    @Test
    void salesAndListingsOfOneItemShareThePrice() throws URISyntaxException {
        PriceBook book = load();
        long unpriced = book.all().stream().filter(e -> e.price() <= 0.0D && !e.source().equals("shop")).count();
        long total = book.all().stream().filter(e -> !e.source().equals("shop")).count();
        System.out.println("[migration] " + book.all().size() + " items, " + unpriced + " of " + total + " (not shop) without a price");
        assertTrue(unpriced * 3 < total, "most items have a price now: " + unpriced + "/" + total);
        PriceBook.Entry satchel = book.get(PriceBook.key(null, "Gold Satchel"));
        assertTrue(satchel != null && satchel.price() > 0.0D);
    }
}
