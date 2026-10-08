# Next session continuation — 18:34

## Exact checkpoint

- Repository: `/home/freelocs/IdeaProjects/ThePrisons`
- Branch: `dev`
- Last confirmed pushed commit before this handover bundle: `fbb1457feb47f839d09b43295258ece57b7a3e18` (`test(ui): capture visual acceptance at client scales`). Remote `origin/dev` was queried directly and returned the same SHA.
- This handover file, updated `CURRENT_STATE.md`, updated `CLAUDE_NEXT_TASKS.md`, and the 79 screenshot PNGs are new local changes to commit/push after verifying their staged paths. Do not reset or discard them.
- `stash@{0}` must remain exactly as found: `WIP on dev: fa30f51 feat(items): the item list lives in the player inventory ...`; it was not applied or modified. `Release` was not touched.

## Start here (P0)

Open `src/main/java/io/theprisons/gui/click/ClickGuiScreen.java`. The actual screen has fixed `SIDEBAR_W`, `CONTROL_W`, a horizontal category-chip row, and a three-column panel arrangement. At 1280×720 with GUI Scale 3 the category chips exceed the content width and setting labels are compressed/wrapped into unusable narrow columns. Reproduce using:

`docs/development/handover/visual-acceptance-2026-10-08/config/0045_config_720p_scale3_overview.png`

The test harness is `src/gametest/java/io/theprisons/gametest/ShowcaseClientGameTest.java`; its `configVisualQa` captures all eight tabs at 1920×1080 and 1280×720, scales 1/2/3, then module settings, dropdown, search and scroll. Current shots are renderer snapshots; tabs/search/dropdown are partly set programmatically. Add/perform real mouse/keyboard interaction verification for the repaired screen. Preserve the scroller correction in `ClickGuiScreen.mouseScrolled`.

## Following P0

P1 is an actual launch of the already-installed JAR from Prism instance `Cosmic`:

- JAR: `ThePrisons-Nebula-v1.2.1-mc1.21.11.jar`
- Mods directory: `/home/freelocs/.local/share/PrismLauncher/instances/Cosmic/minecraft/mods/`
- Last verified SHA-256: `697e9aaa5f69fd73d1ddea26f2ec056fc48a9f30d6d3daea01fb42268f72b48a`
- Launcher profile seen previously: Minecraft 1.21.11, Fabric Loader 0.19.5, Java 21.

Join Cosmic Prisons; capture the inventory Item List and original item tooltip, then `/ah` and `/ee` with real offers. Check standard textures in player inventory. Treat all live price/lore behavior as **MANUAL TEST REQUIRED** until this occurs. Local Gradle GameTests used Fabric Loader 0.17.3 and synthetic AH/EE fixtures and are not substitutes.

Then continue in this order: P2 improve the real `InventoryItemList`/mixin tooltip and rarity path; P3 validate `MarketScreen`/`MarketOverlay` values and formulas; P4 unify the productive UI and check performance; P5 only then process future, delivered texture batches. No Bandit Macro work until UI acceptance.

## Last automated evidence

Last completed commands before this handover-only update:

- `./gradlew clean test build --no-daemon` — success.
- `./gradlew runClientGameTest -Pshowcase -PshowcaseSeconds=1 --no-daemon` — success; actual `ClickGuiScreen` render snapshots.
- `./gradlew runClientGameTest -Pmarket --no-daemon` — success; production Item List input/render hooks and AH/EE screens, with synthetic data.

The 79 durable screenshots are stored at `docs/development/handover/visual-acceptance-2026-10-08/` (about 11 MB). The prior `build/` and `/tmp` copies may be disposable; use the repository copy. Do not claim original Cosmic tooltip fidelity or live market behavior from these screenshots.
