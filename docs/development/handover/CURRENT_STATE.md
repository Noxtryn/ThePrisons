# Current state — 2026-10-08

## Repository and deployment

- Branch `dev`; latest verified commit and `origin/dev`: `fbb1457feb47f839d09b43295258ece57b7a3e18`.
- The preceding implementation and visual-QA code are committed and pushed. This handover update and archived screenshot batch are the only new local changes until committed.
- `stash@{0}` is still `WIP on dev: fa30f51 ...`; it has not been applied, altered, or dropped. `Release` is untouched.
- Built and installed artifact: `ThePrisons-Nebula-v1.2.1-mc1.21.11.jar`; build and Prism copy SHA-256 `697e9aaa5f69fd73d1ddea26f2ec056fc48a9f30d6d3daea01fb42268f72b48a`.
- Identified Prism mod directory: `/home/freelocs/.local/share/PrismLauncher/instances/Cosmic/minecraft/mods/`; only one `ThePrisons*.jar` was present.

## Verified and not yet accepted

- `./gradlew clean test build --no-daemon` passed. Both local client GameTest runs passed: Showcase and Market.
- The client test renders production `ClickGuiScreen` at GUI scales 1/2/3 and 1920×1080 plus 1280×720, and produces category/settings/search/dropdown/scroll screenshots. The tabs are selected reflectively, so this verifies render states, not physical navigation/input in Prism.
- Confirmed visual defect: `ClickGuiScreen` at 1280×720, GUI Scale 3 clips the horizontal category tabs and squeezes the setting label/control columns. Fix this first; no broad redesign or new renderer.
- The Inventory Item List test now calls its production entry points. It renders 211 registered items; `shard` and `mask` queries narrow results; icons, category/tier chips and card hover work. The synthetic fixture has no matching market quote for its hovered card, and UI correctly says “No market data yet.” Real Cosmic tooltip/lore remains unverified.
- AH and EE screens render with synthetic seeded observations only. Cosmic `/ah`, `/ee`, server prices, original tooltip lore, and the installed Prism instance have not been manually exercised: **MANUAL TEST REQUIRED**.
- The JAR contains 35 approved PNGs under `theprisons_items_standard`; its relevant models are present, the resource reload lists `theprisons_items_standard` and `theprisons_look`, and prior HD art remains archived in `archive/legacy-texturepacks/`.

## Durable QA evidence

The 79 screenshots are now in `docs/development/handover/visual-acceptance-2026-10-08/` (about 11 MB). The directory includes all generated config variants and representative Item List/AH/EE captures. Their generation and limitations are detailed in [VISUAL_ACCEPTANCE_2026-10-08.md](VISUAL_ACCEPTANCE_2026-10-08.md).

## Immediate continuation

Start with P0: fix and re-render the actual `ClickGuiScreen` at 1280×720 / Scale 3. Then launch the installed JAR in Prism and conduct the real Cosmic-server checks. Follow the ordered list in [CLAUDE_NEXT_TASKS.md](CLAUDE_NEXT_TASKS.md) and checkpoint file [NEXT_SESSION_1834.md](NEXT_SESSION_1834.md). No Bandit Macro work before UI acceptance.
