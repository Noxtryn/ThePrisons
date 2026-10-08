# Changes by Codex

## V4 pipeline integration

- Added the V4 asset registry and offline import pipeline supplied in the worktree.
- Added handover documentation and repository hygiene rules.
- Removed tracked Python bytecode artifacts after confirming they are generated outputs.

Verification is recorded in `TEST_RESULTS.md`. The commit SHA is available from the Git log;
keep future entries scoped to one reviewed change set.
## Phase 3 (in review)

- Archived the retired redesign experiments under `archive/`; the archive is explicitly excluded from
  compilation and V4 imports.
- Replaced the session HUD draw-path with a prepared, responsive dashboard model and renderer. It has
  Minimal, Standard and Detailed layouts; layout regression tests cover widths, font metrics and both
  Ore and Bandit information.
- Corrected AH history ingestion so only the slot that is a confirmed sale is learned and fractional
  sale quantities produce the correct unit price. Hardened `/ee` analysis against invalid quotes.
- Added a non-invasive Bandit dashboard adapter. The incomplete goal-navigation experiment in
  `stash@{0}` was reviewed but deliberately not applied.

## Phase 4 (in review)

- Re-audited `LocalNavigator` and the untracked portion of `stash@{0}`. The latter remains an
  incomplete, incompatible goal-navigation experiment plus obsolete assets; no movement code was
  copied into the production macro.
- Added an explicit HD V4 validated overlay choice and an empty pack scaffold. It selects exactly
  one overlay (V2 or V4), keeps the always-on look pack last, and falls through to Classic while
  no V4 artwork is present.
- Added V4 partial-family gating, HD selection, GUI-scale layout, and age-shifted repeated-sale
  regression coverage.

## Unified UI V4 foundation

- Added `ItemRarity` as the one presentation palette for every canonical registry tier. Item-list
  borders, rarity labels and the item-look slot frame now resolve their colours through it; unknown
  server text stays neutral rather than being presented as a made-up tier.
- Added the pure `ItemMarketTooltip` bridge. It derives the exact existing catalog key and appends
  a `MARKET DATA` block only when the real market cache has facts for it. The Fabric item-tooltip
  callback leaves all Minecraft/Cosmic lines in place, then appends this block after the existing
  ThePrisons insights.
- The inventory item-list hover now starts with Minecraft's generated item tooltip, then adds
  registry category/tier facts and the same market block. It does not fabricate Cosmic lore for an
  item that the registry has not captured.
- Item-price details now expose source/window, sample count and observation age in addition to
  fair price, range, trend and confidence. They remain a formatting view of `MarketStats`, not a
  second calculation.
- The Dashboard's module tiles and HUD-page link now open the complete Click-GUI editor in every
  profile, not just DEV. This deliberately reuses its established category navigation and all
  setting controls (including market and multi-choice values) rather than creating a partial
  parallel configuration architecture.
- The inventory-list tooltip detects a market block already supplied by Fabric's normal tooltip
  callback before adding its registry fallback, so real market facts cannot be duplicated.
- Dashboard feature tiles now preselect their exact module in the complete editor, so opening the
  Auction or Energy overlay tile lands directly on its real controls instead of a remembered,
  unrelated module.
