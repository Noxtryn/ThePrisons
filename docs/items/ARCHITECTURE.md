# Item platform: registry, item list, auction overlay, energy overlay

One shared data layer (`io.theprisons.items`), three consumers. Nothing parses lore, builds stacks or sorts in a render frame.

```
ItemStack / menu line --ItemFactsReader--> ItemFacts (immutable) --ItemClassifier--> ItemClass --ItemIdentity--> stable key
                                                                           |
                       ItemRegistry (seeded from ItemDirectory + real items learned once) --revision--> ItemSearchIndex --> ItemListModel --> ItemView --> InventoryItemList (search bar above the hotbar, panel beside the inventory; no screen, no key)
AH menu revision changes --> ListingInput[] --AhAnalyzer--> MarketCache (bounded) --> AhSnapshot (immutable) --> AhOverlayRender (border, badge, hover)
focused item changes --> EnergyReader --> EnergyTracker --> EnergyExtractorState --> EnergyOverlayModel --> EnergyOverlayRender
```

## Identity
`ItemIdentity.catalogKey()` = family + tier/variant (the list entry). `key()` = catalog key + server item id + identity-relevant attributes (tier, level, stage,
type, charge, energy capacity, prestige, enchants). Never in it: uuid, timestamps, owner, serial, amount, droppable/movable flags. Pets and masks never get a tier.

## Caches (key, invalidation, bound)
| Cache | Key | Invalidated by | Bound |
|---|---|---|---|
| ItemRegistry | catalog key | `add`/`learn` (revision) | 4000 |
| ItemSearchIndex | registry revision | revision change | rebuilt, not grown |
| ItemListModel view | (registry revision, filter revision) | query/category/tier/registry change | 1 view |
| ItemStacks | catalog key | never (entries are immutable) | registry size |
| MarketCache histories | item key (LRU) | `observe` | 1500 keys x 64 samples |
| MarketCache stats | (key, history version, minute) | new observation / minute | 6000 |
| EnergyOverlayModel | state revision (+stale flag) | state change | 1 |

## Market
Observations are what the AH showed (listing asks, sales); a listing seen again refreshes its time. Stats: median/min/max with outlier removal; confidence 0-100 from
sample count (40), freshness (40) and consistency (20), explained in `why`. A listing is judged against the OTHER samples of the same item; "great" needs Medium
confidence or better. No external API.

## Energy
Only known forms are read: the lore energy section of a pickaxe/satchel (`PickaxeLore`) and `cosmicprisons:amount` of a Cosmic Energy item. Rate/ETA only from
observed changes over >= 20 s. No extractor menu has been captured yet: the word "extractor" only lets the overlay show; capture one with `/prisons capture`.
