# Overlays: how to open them and how the numbers are calculated

All of them are always on and passive: they only read what the server shows you, they never click, buy or send anything.

## Item list (inventory)
Open your inventory (E) and **type** (no key, no command). The search bar sits above the hotbar. Double-click it to list everything. The panel beside the inventory
has categories and (only where a family has them) tier chips; hover an item for its details, click to pin them.

## Auction overlay (`/ah`)
Open `/ah`. Cheap listings get a green border, dear ones a red one, a fair price none (the texture stays visible); the hover shows the MARKET block.
It learns from what you look at: the first time you see an item there is nothing to compare with ("Estimated: unknown"). Open pages of the same item, and
`/ah` -> Auction House History (real sales) - sales count more than asking prices.

* unit price = total price / amount
* estimate = median unit price of the OTHER observations of that item (a listing is never compared with itself); with at least 3 sales the sales are the
  basis, otherwise the asking prices; a sample more than 3.5 median-absolute-deviations away is left out
* difference = (listed unit - estimate) / estimate; <= -25 % great (only with Medium or High confidence), <= -10 % good, >= +15 % overpriced, else fair
* confidence score 0-100 = samples (up to 40: 12 samples) + freshness of the newest (up to 40: under 10 min) + consistency (up to 20: spread under 10 %);
  75+ High, 45+ Medium, else Low; under 2 samples none, under 3 at most Low, under 5 at most Medium

## Energy market overlay (`/ee`)
Open `/ee`. The card beside the menu shows:

* **Cheapest / Typical**: the cheapest and the median offer on the page, money per 1k energy (offers over three times the median - e.g. 100,000,000 /1k - are
  marked red, ignored everywhere)
* **N CE**: what 10k ... 100M energy cost, buying the cheapest offers of this page first (cost = sum of amount x rate / 1000); a size the page cannot cover
  is not shown
* **7-day avg**: only after you opened "Price Analytics" once this session; shows how far the cheapest offer is from it
* **You can buy**: your balance walked through the cheapest offers
* **You hold**: the amounts of the Cosmic Energy items in your inventory and what they would cost at the cheapest offer (an estimate for the value, not a sell price)

## Energy overlay (pickaxe, satchel, Cosmic Energy item)
Hover a pickaxe, a satchel or a Cosmic Energy item in any inventory (also in an extractor menu): the panel at the bottom right appears while you look at it.

* stored / capacity / percent: the lore section "Cosmic Energy ... (now / capacity)" or the `amount` of a Cosmic Energy item
* rate = (energy now - first reading in the window) / elapsed time, per minute - only after 20 s of readings; ETA = (capacity - now) / rate, only when
  the rate is positive and the capacity known; stale after 30 s without a reading
