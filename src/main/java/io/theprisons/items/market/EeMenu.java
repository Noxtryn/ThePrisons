package io.theprisons.items.market;

import java.util.List;

/**
 * The energy market menu ("Buy Cosmic Energy") as the server shows it, read once per page change.
 *
 * @param available     the energy on sale in total (the header says "Available: 507.9m"), NaN = not shown
 * @param fromRate      the header's "From: $2,299 /1k" in money per 1k energy, NaN = not shown
 * @param sellers       the header's seller count, -1 = not shown
 * @param priceRisesInMs "Price increases in: 23h 40m" in ms, -1 = not shown
 * @param serverLowest  the "Buy Cheapest" button's "Lowest price", money per 1k, NaN = not shown
 * @param serverAvg10k  the button's "Avg price/10k" as the server prints it (not interpreted), NaN = not shown
 * @param balance       "Your balance", NaN = not shown
 */
public record EeMenu(List<Listing> listings, double available, double fromRate, int sellers, long priceRisesInMs, double serverLowest, double serverAvg10k,
                     double balance) {
    /** One seller's offer: the slot, who, how much energy, the total price and the rate per 1k energy the menu prints. */
    public record Listing(int slot, String seller, double amount, double total, double ratePer1k) {
    }

    public EeMenu {
        listings = List.copyOf(listings);
    }
}
