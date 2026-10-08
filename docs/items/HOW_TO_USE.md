# Overlays: how to open them and how the numbers are calculated

All of them are always on and passive: they only read what the server shows you, they never click, buy or send anything.

## Item list (inventory)
Open your inventory (E) and **type** (no key, no command). The search bar sits above the hotbar. Double-click it to list everything. The panel beside the inventory
has categories and (only where a family has them) tier chips; hover an item for its details, click to pin them.

## Auction overlay (`/ah`)
Open `/ah`. A great listing gets a subtle green frame, a good one green/cyan, an overpriced one red; a fair price or an unknown item gets none (the texture stays
readable). A short percentage (-18 %, +26 %) sits in the corner of framed slots; everything else is in the hover (MARKET block: Listed, Fair, Difference,
Unit, Quick Sell, 24h, 7d, Trend, Samples, Basis, Confidence, Last Seen - only the fields that are known).

It learns from what you look at, and only from what the menus really show: a listing line has the seller and the time it expires, a history line has seller,
buyer, price and "sold 2m ago". Those identify a listing, so opening a page again does not count it twice, and two identical listings that expire at different
times are two.

* unit price = total price / amount; sales and asking prices stay separate
* **which samples count** (the first rule that has at least 3): sales of the last 24 h, else sales of the last 7 days, else listings seen in the last 12 h, else
  older data (low confidence at best). A market that moved two weeks ago does not decide today's price.
* **fair price** = the median of those samples, each weighted by its age (half-life: half the window) - never the average
* **outliers**: from 5 samples on, 3.5 median-absolute-deviations away (at least 5 % of the price); from 3 samples on, more than 5x above or below. A typo (1) or
  a scam (100000) next to a market of ~100 moves nothing. The listing being judged is never part of its own baseline.
* **quick sell** = the price a quarter of the samples were at or below (from 5 samples); low / high = cheapest / dearest sample that counts
* **trend** = fair price of the last 24 h over the 7-day level (rising / falling beyond 5 %); only with Medium confidence or better
* rating: at least -25 % under the fair price is **great** - only with Medium confidence and 5 samples; -10 % good; +15 % overpriced. 80 % or more under is
  still highlighted but marked "check the item"
* **confidence** (0-100): samples 30 + recency 25 + spread 20 + basis (sales 15 / listings 6) + agreement of 24 h and 7 days 10. High needs 75 points, 8 samples
  and a newest sample under 6 h old (listings alone: 12 samples); under 2 samples none, under 3 at most Low, under 5 at most Medium, old data at most Low

The item list shows the same fair price and confidence on its cards (the variant with the most evidence speaks for an entry; variants are never mixed).

## Energy market overlay (`/ee`)
Open `/ee`. The panel beside the menu:

* **RATES**: CHEAPEST and TYPICAL, money per 1k energy. Typical = the median of the valid listings of a real size (under 1 energy is ignored).
* **MARKET**: the typical rate against the 7-day average (only after you opened "Price Analytics" once this session) and the "price rises in" clock.
* **BUY COST**: what 10K ... 100M energy cost, buying the cheapest listings of this page first (sum of amount x rate / 1000); sizes the page cannot cover are not shown.
* **YOU**: balance, what it buys (walked through the cheapest listings), the Cosmic Energy you hold and what it would cost at the cheapest rate (an estimate, not a
  sell price).
* Listings far above the market (more than 3x the median, or far beyond the spread with six or more listings: a scam or a typo) are framed red, ignored in
  every number and counted in the note. A listing far BELOW the others is a real offer and stays the cheapest.

## Energy overlay (pickaxe, satchel, Cosmic Energy item)
Hover a pickaxe, a satchel or a Cosmic Energy item in any inventory (also in an extractor menu): the panel at the bottom right appears while you look at it.

* stored / capacity / percent: the lore section "Cosmic Energy ... (now / capacity)" or the `amount` of a Cosmic Energy item
* rate = (energy now - first reading in the window) / elapsed time, per minute - only after 20 s of readings; ETA = (capacity - now) / rate, only when
  the rate is positive and the capacity known; stale after 30 s without a reading
