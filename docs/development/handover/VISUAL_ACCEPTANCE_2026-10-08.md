# Visual acceptance preparation — 2026-10-08

## Scope and evidence limits

The automated screenshots below come from Fabric Client GameTest using Minecraft 1.21.11, Java 21 and a local integrated test world. They exercise production screens and client render hooks, but they are not screenshots from the user's Prism instance or from Cosmic Prisons. AH/EE fixtures are seeded test observations, not live server prices. Live `/ah` and `/ee` remain **MANUAL TEST REQUIRED**.

Screenshots are generated under `build/run/clientGameTest/screenshots/`; a later GameTest invocation clears that folder. The reviewed evidence from this run is copied to `/tmp/theprisons-visual-acceptance-2026-10-08/` (Config images in its `config/` subdirectory).

Commands completed successfully: `./gradlew clean test build --no-daemon`, `./gradlew runClientGameTest -Pshowcase -PshowcaseSeconds=1 --no-daemon`, and `./gradlew runClientGameTest -Pmarket --no-daemon`. The client tests used the Gradle dev launcher (Fabric Loader 0.17.3), Java 21 and Minecraft 1.21.11. The user's Prism instance uses Fabric Loader 0.19.5; the separate manual instance check remains required.

## Config GUI

`ShowcaseClientGameTest` opens the production `ClickGuiScreen` and captures every `Tab` at 1920×1080 and 1280×720, GUI scales 1, 2 and 3. Additional shots cover module settings, dropdown, search and scroll. Tabs are selected via test reflection to capture the actual renderer; this verifies rendering, not mouse-navigation clicks.

Initial visual review found that the desktop-scale layout is readable, but 1280×720 at GUI Scale 3 leaves too little logical space: the category chips extend beyond the content boundary and setting labels wrap into extremely narrow columns. This remains an identified visual issue and must be checked manually at the user's preferred resolution. The list-pane scroll hit area was corrected to match the rendered geometry in `ClickGuiScreen.mouseScrolled`.

Representative screenshots:

- `config_scale1_overview.png`, `config_scale2_overview.png`, `config_scale3_overview.png`
- `config_720p_scale2_overview.png`, `config_720p_scale3_overview.png`
- `config_module_settings.png`, `config_dropdown.png`, `config_search_market.png`, `config_scroll_overview.png`

All 48 generated Config images (eight tabs × six resolution/scale combinations) and the opening command-center image are retained under `/tmp/theprisons-visual-acceptance-2026-10-08/config/`.

## Inventory Item List and market screens

The previous market client test mistakenly exercised the older `MarketSearch` helper for the inventory list; those screenshots were discarded as invalid evidence. `MarketClientGameTest` now uses `InventoryItemList`'s production click, typing, scrolling and render paths. The corrected test passed. It rendered 211 item cards in “show all”; `shard` narrowed the list to 8, `mask` to 9; card hover selected an item and displayed its details. The seeded prices did not match the hovered item identities, so the UI correctly displayed “No market data yet.”

AH and EE screenshots render using seeded local observations, including a Charge Orb AH tooltip with observed low/comparison and EE unit rate, and an EE offers view with liquidity and quantity estimates. These are synthetic fixtures, not live-server observations.

AH/EE screenshots from the same test use seeded local observations. They validate the production overlays and displayed item/price fields in a synthetic world only. They do not establish the accuracy or completeness of server-provided data.

## Texture/JAR verification

The successful clean build produced `build/libs/ThePrisons-Nebula-v1.2.1-mc1.21.11.jar` (6,649,539 bytes), SHA-256 `697e9aaa5f69fd73d1ddea26f2ec056fc48a9f30d6d3daea01fb42268f72b48a`. Direct JAR listing found exactly 35 PNGs in `resourcepacks/theprisons_items_standard/assets/theprisons/textures/item/prisons/` and the relevant shard/contraband/book/revealed-book/key/charge-orb models in `assets/theprisons/models/item/prisons/`. Runtime resource reload listed `theprisons_items_standard` and `theprisons_look`. The old HD custom assets remain in `archive/legacy-texturepacks/`; Classic fallback assets were not removed. The archive/pipeline mapping tests are part of the passing unit suite.

The Prism instance path identified for the user's `Cosmic` instance is `/home/freelocs/.local/share/PrismLauncher/instances/Cosmic/minecraft/mods/`. The current QA build was installed there after confirming that only one ThePrisons JAR was present. Installed and build artifact hashes match exactly (`697e9aaa5f69fd73d1ddea26f2ec056fc48a9f30d6d3daea01fb42268f72b48a`).

## Manual Cosmic Prisons acceptance

1. In Prism Launcher, launch the `Cosmic` instance with Minecraft 1.21.11, Fabric Loader 0.19.5 and Java 21. Confirm the loaded mod list has one `ThePrisons` 1.2.1 entry.
2. Join Cosmic Prisons and open an inventory. Search an item family (for example `shard`), then double-click the list search bar to show all; hover a card and inspect its icon, tier border, original tooltip and market source/age. Scroll and select a category/tier chip.
3. Open `/ah`; hover a known listing and verify original item lore remains, the compared price is marked as observed/testable, and confidence/data age are present only when actual observations exist.
4. Open `/ee`; compare at least two actual offers and verify the cheapest/typical per-1k prices, visible liquidity and quantity estimates against the menu. Do not treat empty cache data as a quote.
5. Open the Config GUI (default keybind shown in Controls), test all categories, search, slider/dropdown, and scroll at GUI Scale 1/2/3. At 1280×720 Scale 3, specifically check whether tabs or descriptions clip; this is already a known issue in the automated capture.
6. Capture Prism screenshots for the Inventory List, `/ah`, `/ee`, one extended tooltip, and each tested GUI scale, then record the server, time, resolution and scale alongside them.

Do not mark Cosmic-server behavior as verified until the above manual run is completed.
