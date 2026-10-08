# Claude next tasks — handover at 18:34

Work only on `dev`; preserve `Release` and `stash@{0}`. Continue from the exact tree/commit recorded in `NEXT_SESSION_1834.md`. The current product acceptance is incomplete; do not call the UI finished based on builds or synthetic GameTests.

1. **P0 — Repair Config GUI at 1280×720 / GUI Scale 3.** Edit the real `io.theprisons.gui.click.ClickGuiScreen`, not a parallel screen. Fix horizontal category-tab overflow and overly narrow labels/controls. Re-render after each focused change; test the actual navigation, dropdown, slider, search and scroll, with no clipped controls. Keep the existing `mouseScrolled` geometry correction.
2. **P1 — Prism and Cosmic Prisons live acceptance.** Start the installed `ThePrisons-Nebula-v1.2.1-mc1.21.11.jar` in the `Cosmic` Prism instance (MC 1.21.11, Fabric 0.19.5, Java 21). Capture screenshots from the actual client. On Cosmic Prisons, verify `/ah`, `/ee`, server item icons, original Cosmic lore, tooltips, market observations, and actual prices. If server access is unavailable, document each specific step as `MANUAL TEST REQUIRED`; never substitute synthetic prices.
3. **P2 — Item List, rarity frames and original tooltips.** Work on the productive `InventoryItemList` and its real `ThePrisonsMarketInventoryMixin` render/input path. Preserve original Minecraft/Cosmic tooltip lines and add market data only when cache-backed. Verify card size/spacing, rarity colors/frames, search/filter/sort, long names and large inventories against live item examples.
4. **P3 — AH/EE pricing.** Inspect `MarketScreen`, `MarketOverlay`, `MarketModule`, and the Energy calculations. Use only actual observations. Make price source, age, confidence, comparison set, quantity and formulas understandable; test invalid values and repeated observations.
5. **P4 — UI consistency and performance.** Unify the existing Config GUI, Inventory Item List, AH/EE and active HUD without duplicate renderers/services. Check scaling, tooltip fidelity, frame cost and resource reload. Keep unverified claims clearly labeled.
6. **P5 — Future texture batches.** Import only delivered artwork after exact registry/model matching. Current standard pack has 35 approved PNGs; maintain Classic/Cosmic fallback and never globally override unrelated items.

**Hard stop:** no Bandit Macro work until the UI is visually accepted. Do not apply `stash@{0}` wholesale; it combines obsolete HD artwork with incomplete navigation changes. Do not alter product choices or texturepack selection without approval.

Persistent screenshots: `visual-acceptance-2026-10-08/` (79 PNGs). Known visual issue and exact QA limitations: `VISUAL_ACCEPTANCE_2026-10-08.md`.
