# Changelog

All notable changes to ThePrisons are documented here.
The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and the project uses [Semantic Versioning](https://semver.org/).
The long development notes of the core rewrite live in [docs/dev-notes/core-rewrite-changelog.md](docs/dev-notes/core-rewrite-changelog.md).

## [Unreleased]

### Added

- **Website** on GitHub Pages (`site/`): feature overview, gallery of the real pictures in `docs/media`, install guide and the latest release notes
- **Discord embed** now links to the GitHub main page and the website
- **Tunnel Vision: magic carpet** - a second animation (Aladdin style): the rainbow road dissolves into rising particles with a flash cut and the carpet appears with a swirl of sparks; it bobs up and down on its own while its left and right stay fixed to the character. Switches in turns (Auto) or stays on the one you pick
- **Tunnel Vision: targets** - small pink crystals ahead on the road, light-blue asteroids over the carpet; the player shoots a bolt and they burst into a few shards (no fireworks)
- **Tunnel Vision: performance setting** (High / Balanced / Fast): fewer road rows, no glow and fewer particles for more FPS

### Fixed

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

[Unreleased]: https://github.com/olb-freelocs/ThePrisons/compare/v1.0.0...HEAD
[1.0.0]: https://github.com/olb-freelocs/ThePrisons/releases/tag/v1.0.0
