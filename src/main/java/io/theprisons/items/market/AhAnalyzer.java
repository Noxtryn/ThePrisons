package io.theprisons.items.market;

import io.theprisons.items.ItemFacts;
import io.theprisons.items.ItemIdentity;
import io.theprisons.items.ItemConfidence;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Auction page to {@link AhSnapshot}: normalise the listings, put them in the market cache, judge each one against the OTHER observations of the same
 * item, and publish an immutable snapshot. Pure and independent of Minecraft - the client part only hands it the items read from the open screen.
 */
public final class AhAnalyzer {
    private AhAnalyzer() {
    }

    /** One slot of an open auction page. */
    public record ListingInput(int slot, ItemFacts facts, double total, int amount, @org.jspecify.annotations.Nullable String seller, long expiresInMs) {
        public ListingInput(int slot, ItemFacts facts, double total, int amount) {
            this(slot, facts, total, amount, null, 0L);
        }
    }

    /** A completed sale of the history page. {@code agoMs} = how long ago ("Item sold 3m ago"). */
    public record SaleInput(ItemFacts facts, double total, int amount, long agoMs, @org.jspecify.annotations.Nullable String seller,
                            @org.jspecify.annotations.Nullable String buyer) {
        public SaleInput(ItemFacts facts, double total, int amount, long agoMs) {
            this(facts, total, amount, agoMs, null, null);
        }
    }

    /**
     * One slot of the history page as a sale, or null: only a slot that itself is a confirmed sale ("Item sold ... ago" and "Price: $T (Nx)") counts - a sale in
     * another slot proves nothing about this one. The quantity is the one the lore names ("(19,999.796x)"), not the stack count, so the unit price is right
     * for energy and stacks.
     */
    public static @org.jspecify.annotations.Nullable SaleInput saleOf(io.theprisons.modules.qol.market.MarketParser.Item it, ItemFacts facts) {
        List<io.theprisons.modules.qol.market.MarketParser.Sale> confirmed = io.theprisons.modules.qol.market.MarketParser.sales(List.of(it));
        if (confirmed.isEmpty()) {
            return null;
        }
        io.theprisons.modules.qol.market.MarketParser.Sale sale = confirmed.get(0);
        ListingMeta meta = ListingMeta.parse(it.lore());
        return new SaleInput(facts, sale.unitPrice(), 1, sale.agoMs(), meta.seller(), meta.buyer());
    }

    public static AhSnapshot analyze(List<ListingInput> inputs, MarketCache cache, long now, long pageSignature) {
        long t0 = System.nanoTime();
        List<MarketObservation> obs = new ArrayList<>(inputs.size());
        List<ListingInput> kept = new ArrayList<>(inputs.size());
        int fresh = 0;
        for (ListingInput in : inputs) {
            if (in.total() <= 0.0D) {
                continue;
            }
            ItemIdentity id = ItemIdentity.of(in.facts());
            MarketObservation o = MarketObservation.of(id.key(), in.total(), in.amount(), now, MarketObservation.Source.LISTING, in.seller(), null,
                    in.expiresInMs() > 0L ? now + in.expiresInMs() : 0L);
            if (cache.observe(id.catalogKey(), o)) {
                fresh++;
            }
            obs.add(o);
            kept.add(in);
        }
        Map<Integer, SlotView> slots = new HashMap<>();
        for (int i = 0; i < kept.size(); i++) {
            ListingInput in = kept.get(i);
            MarketObservation o = obs.get(i);
            ItemIdentity id = ItemIdentity.of(in.facts());
            MarketStats base = id.confidence() == ItemConfidence.VANILLA ? MarketStats.EMPTY : cache.stats(o.key(), now, o);
            slots.put(in.slot(), SlotView.of(ListingAnalysis.of(in.slot(), o, base)));
        }
        return new AhSnapshot(pageSignature, now, slots, kept.size(), fresh, System.nanoTime() - t0);
    }

    /** The history page: only learns the sales (nothing is rated there). Returns how many were new. */
    public static int learnSales(List<SaleInput> sales, MarketCache cache, long now) {
        int fresh = 0;
        for (SaleInput s : sales) {
            if (s.total() <= 0.0D) {
                continue;
            }
            ItemIdentity id = ItemIdentity.of(s.facts());
            // the page says "sold 3m ago": the sale time is computed once; a refresh of the page maps to the same sale (seller, buyer, price, amount, time within
            // the minute the menu rounds to), two genuinely separate identical sales stay two
            long ts = now - s.agoMs();
            if (cache.observe(id.catalogKey(), MarketObservation.of(id.key(), s.total(), s.amount(), ts, MarketObservation.Source.SALE, s.seller(), s.buyer(), 0L))) {
                fresh++;
            }
        }
        return fresh;
    }
}
