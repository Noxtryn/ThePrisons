# Changelog

All notable changes to ThePrisons are documented here.
The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and the project uses [Semantic Versioning](https://semver.org/).
The long development notes of the core rewrite live in [docs/dev-notes/core-rewrite-changelog.md](docs/dev-notes/core-rewrite-changelog.md).

## [Unreleased]

### Changed

- **Item look paused:** ThePrisons no longer changes how items look. The own item textures, tier frames, slot badges, tier tooltip frames, the comic texture filter, the custom tooltip frame and the 35 sci-fi item textures are not shipped for now; items appear exactly as Cosmic Prisons, Minecraft or your resource packs show them. Item recognition, the Item List, AH/EE overlays and market data are unchanged.

## [1.3.0-beta.1] - 2026-10-08

> **THEPRISONS — COSMIC EVOLUTION · PUBLIC BETA / PRERELEASE**

### Added

- **35 approved sci-fi item textures** are included in the standard ThePrisons texture source; items without an approved replacement retain their existing Cosmic Prisons / Minecraft appearance.
- **Unified UI V4 foundations:** the production Config GUI, inventory Item List, rarity presentation and market-facing surfaces use the new gunmetal visual direction.
- **Market context:** AH/EE screens can present observed offers and price context from the local market services; values are observations, not guaranteed prices.

### Changed

- **Config GUI and session dashboard:** the existing client screens have been updated with clearer navigation, panels and status presentation.
- **Item List and rarity presentation:** item cards, icons and rarity accents are integrated into the existing inventory view; original item identity and lore remain the source of truth.
- **Market analysis:** observed offer prices, comparison context and data confidence are surfaced more clearly where data is available.
- **Public project presentation:** the bilingual README and responsive website are refreshed; client-test screenshots distinguish synthetic market fixtures from live-server verification.

### Fixed

- **Configuration and market input handling:** invalid price values and several UI layout/state edge cases now receive safer handling.
- **Texture fallback:** items without an approved ThePrisons texture continue to use their original resource instead of a blanket replacement.

### Known issues

- At **1280×720 with GUI Scale 3**, some Config GUI navigation/control content can overflow or become cramped. Use a lower GUI scale or larger resolution while this is addressed.
- **Cosmic Prisons live-server validation is incomplete.** `/ah`, `/ee`, server-specific lore and market observations have not all been verified against live server data. Do not use beta market estimates as trading guarantees.
- Some UI evidence is from automated/client-side tests and screenshots; this does not establish correct behavior on the live server at every scale.

## [1.2.1] - 2026-10-07

### Changed

- **Item sorter:** items of one kind now go into the same private vault whatever their level, percent or numeral - "Charge Orb 6" lands next to "Charge Orb 12", "Aegis I" next to "Aegis IV" (rarity words still separate: a Godly book is no Simple book)
- **Wormhole powerups:** Double Tap, Overdrive and BOGO each have a texture per rarity, in the colour of the rarity the enchant has at the wormhole
- **"Random" items** (Random Enchant Book, Random Page, Random Prestige Token, Random Boss Egg, Random Trinket, Random Powerup, Random Satchel, ...) are drawn black: the black version of the item they stand for, with a small "?"

### Fixed

- **Item sorter:** ores with a name or lore (e.g. `diamond_ore`) are no longer put into private vaults

## [1.2.0] - 2026-10-07

> **Work in progress:** the **Bandit Macro** is only about 2 % done. It is in this release so you can see where it is going, not because it is ready. Expect it to do silly things; do not leave it running unattended.

### Added

- **Bandit Macro (work in progress, ~2 %)** - a state-machine macro (key `J`) that hunts bandits with the spear: seek, aim like a hand, charge, throw, recall, cooldown. It brings a danger check (retreats and a failsafe stop when real players come near), a patrol route and a HUD card. The dashboard's Bandits page got three new sub-tabs for it. Most of it has not been tested on the server yet
- **Market search over the whole auction house** - with a search text the own auction house screen reads every server page in the background and shows all matches in one list; hover shows the price, the energy worth and the server page, a click takes you to the item
- **Ore Macro followers** - somebody who keeps following the macro gets a polite message first (`/msg`); if they are still there 30 seconds later the macro goes far away (`/spawn` and `/warp` back). Somebody who killed you is remembered and the macro leaves as soon as they come near
- **Ore Macro command cooldowns** - "This command is currently on cooldown for 22s" after `/spawn` or `/home` is waited out and the command is sent again
- **Ore Macro diamond mine drop** - the way back from spawn into the diamond mine (walk to the point, look down, jump)

### Changed

- **Item sorter:** ores in any form (ores, ingots, raw ores, ore blocks, coal, diamonds, emeralds ...) no longer count as "inventory crowded" - mined diamonds used to send the macro to spawn
- **Ore Macro, guarded zone:** no more walking on to ground the guards' circles do not cover (4 blocks of slack, sideways only; jumping down is fine); with a player near the macro walks 0 unguarded blocks, alone 6
- **Ore Macro, spawn escape:** `/spawn` is sent again only after 24 s (the server counts down 18 s; a new `/spawn` restarted it)
- **Friends:** bandits and guards are never coloured as friends or gang; the glow colour is cached
- **Performance:** HUD cards, the Session HUD, the auction house's step labels and the player glow cost clearly less per frame (profiled in game)
- **Spear Helper** idles for a few seconds after the spear left the hand, which removes lag during bandit events

### Fixed

- **Ore Macro, /spawn countdown:** the macro no longer stops with "no way" while the `/spawn` countdown is still running
- **Bandit aim:** the line of bandits is picked over the full 180 degrees and the spear pierce angle is respected; friends in the line stop a throw

## [1.1.3] - 2026-10-05

### Added

- **Tunnel Vision: action bar** - the mining session's action bar (XP, energy, boosts) is drawn by the mod while the tunnel hides the world, as an element of the HUD editor you can move and scale
- **Trailer** - a short animated introduction of the mod (`docs/media/trailer.mp4`, an illustration drawn by `scripts/make_trailer.py`, not a game recording)

### Changed

- **Tunnel Vision: player size** is 80 % by default (setting `player_size`)

### Fixed

- **Ore Macro, back from spawn:** the small step away from a wall after `/home` no longer sneaks, it just walks

## [1.1.2] - 2026-10-05

### Changed

- **Ore Macro, stricter outside rules:** with any other player within 48 blocks it walks at most 2 unguarded blocks (was 3 with 2+ players); with nobody near for 20 s at most 6 (was 48). The setting `outside_free` became `outside_solo`

## [1.1.1] - 2026-10-05

### Fixed

- **Ore Macro, death recovery:** hits taken while dying or respawning no longer start a run to the guard the moment the mine is reached again after the `/warp`

## [1.1.0] - 2026-10-05

### Added

- **Website** on GitHub Pages (`site/`): feature overview, gallery of the real pictures in `docs/media`, install guide and the latest release notes
- **Discord embed** now links to the GitHub main page and the website
- **Tunnel Vision: magic carpet** - a second animation (Aladdin style): the rainbow road dissolves into rising particles with a flash cut and the carpet appears with a swirl of sparks; it bobs up and down on its own while its left and right stay fixed to the character. Switches in turns (Auto) or stays on the one you pick
- **Tunnel Vision: targets** - small pink crystals ahead on the road, light-blue asteroids over the carpet; the player shoots a bolt and they burst into a few shards (no fireworks)
- **Tunnel Vision: performance setting** (High / Balanced / Fast): fewer road rows, no glow and fewer particles for more FPS

### Fixed

- **Ore Macro, attacked:** the run to the guard now aims at the guard in sight right now, not a remembered spot; standing next to a guard no longer ends in "no way to a guard - macro stopped"
- **Energy/h and XP/h** now show the action bar's own per-minute rate times 60 (e.g. `(386/min)` = 23,160/h), updated with every action bar; the 5-minute average sits below. The earlier numbers were wrong
- The **Session HUD** and the **storage overlay** follow the Design setting "Boxy font": with it off, everything uses the game font

### Changed

- **Jar name** is now `ThePrisons-<Codename>-v<SemVer>-mc<Minecraft>.jar` (e.g. `ThePrisons-Nebula-v1.1.0-mc1.21.11.jar`); the release title reads "ThePrisons v1.1.0 · Nebula · MC 1.21.11". The release line "Nebula" replaces the placeholder codename of v1.0.0
- Tunnel Vision is cheaper per frame: pooled particles without allocations, precomputed road colours, half the road rows by default

## [1.0.0] - 2026-10-05

First public release: a client-side Fabric mod for Minecraft 1.21.11 and the Cosmic Prisons server.

### Added

- **Dashboard** (`/prisons`, keybind, Mod Menu): animated pages for Overview, Mining, Bandits, Tunnel, Design, Controls and HUD. Switches, sliders, choice buttons, colour palettes and text fields for every setting, tooltips, sub-tabs and "reset section"
- **Ore Macro** with its own pathfinder: tunnel centring, planned routes with world memory, route memory, anti-stuck, combat failsafe, guarded-zone logic with guard look-ahead and return, breaks at a warden, human view motion at frame rate
- **Item sorter** for the Ore Macro: trips to spawn and the private vaults (shards, contrabands, energy, money), pet and ability use, death recovery
- **Waypoint editor and route recorder**: record, save, list and delete routes; border marks (`/prisons set border`)
- **Market prices**: reads the auction house, its history, `/ee` and the shops `/gz` and `/pb` in the background, tracks the lowest price of every item and its worth in Cosmic Energy
- **Own auction house and `/ee` screens** with categories, search, kind chips, client-side pages and a Tinker screen
- **Shop overlays** for `/gz` and `/pb`
- **Item list** above the hotbar: search every known item, tiers and rarities, real tooltips with prices
- **Storage overlay**: `/pv` shows all private vaults as cards; open pages stay fully usable
- **Scoreboard** that replaces the server sidebar, **Better Tab** with player cards, ranks and ping bars
- **Session HUD** with Ore Mining and Bandit modes (uptime, ores per second, tax, boosters, level-up ETA) and a live **Energy/h and XP/h** rate with the average underneath
- **HUD widgets**: pets and trinkets, command cooldowns, satchels, armour durability, item insights, notifications as sliding cards; HUD editor with drag, scale and snapping
- **Friends and gang** colouring (`/prisons friend add|remove|list`), **Sneak Trade** (sneak + right-click a player sends `/trade`), **Player Cards**
- **Quality of life**: message notifications, peaceful mining, vitals warnings, ready announcements, cooldown cache, update checker
- **Spear Helper** for Bandits: static shooter crosshair with presets, sight point with lead and drop for enemy players, aim assist on `L` that looks at the best line of bandits, recall timing signal for `F`, throw and return effects
- **Tunnel Vision** (`F5` + `V`): iris transition, your player in 3D on a rainbow road that follows the macro, selectable backdrop, notifications and a stats ticker
- **Design**: themes, card darkness, animations, boxy font and comic textures; item and armour textures, 250+ hand-made icons
- **Feature profile**: the shipped feature set is fixed, users change design, HUD layout and keybinds

### Changed

- Version is now SemVer; the release asset is `theprisons-<version>.jar`
- Market scan pauses while the Ore Macro runs (the server blocks mining while a menu is open); it can be switched on in the settings
- Licence changed to All Rights Reserved

### Fixed

- Auction house opening by itself after a stopped background scan, also while another screen was open
- Market scan backs off for 30 minutes where the market is disabled (Badlands)
- Aim assist did not find bandits: they are players named `bandit_xx_xxxxxx`

[Unreleased]: https://github.com/Noxtryn/ThePrisons/compare/v1.3.0-beta.1...HEAD
[1.3.0-beta.1]: https://github.com/Noxtryn/ThePrisons/releases/tag/v1.3.0-beta.1
[1.2.0]: https://github.com/Noxtryn/ThePrisons/releases/tag/v1.2.0
[1.1.3]: https://github.com/Noxtryn/ThePrisons/releases/tag/v1.1.3
[1.1.2]: https://github.com/Noxtryn/ThePrisons/releases/tag/v1.1.2
[1.1.1]: https://github.com/Noxtryn/ThePrisons/releases/tag/v1.1.1
[1.1.0]: https://github.com/Noxtryn/ThePrisons/releases/tag/v1.1.0
[1.0.0]: https://github.com/Noxtryn/ThePrisons/releases/tag/v1.0.0
