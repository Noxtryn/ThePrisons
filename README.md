<p align="center">
  <img src="docs/media/banner.png" alt="ThePrisons — Cosmic Evolution" width="100%">
</p>

<p align="center">
  <strong>THEPRISONS — COSMIC EVOLUTION</strong><br>
  <strong>PUBLIC BETA RELEASE CANDIDATE · v1.3.0-beta.1 · Minecraft 1.21.11 · Fabric · Java 21</strong>
</p>

<p align="center">
  <a href="https://noxtryn.github.io/ThePrisons/">Official website</a> ·
  <a href="https://github.com/Noxtryn/ThePrisons/releases">Download / releases</a> ·
  <a href="CHANGELOG.md">Changelog</a> ·
  <a href="README.de.md">Deutsch</a> ·
  <a href="https://github.com/Noxtryn/ThePrisons/issues/new">Feedback & bug reports</a>
</p>

ThePrisons is a client-side Fabric mod for Cosmic Prisons. Cosmic Evolution brings the existing in-game tools together with 35 approved sci-fi item textures, a redesigned configuration interface, an inventory item browser, market overlays and a live session HUD.

> **Beta notice:** this is a public prerelease. AH/EE behavior and server-specific item data have not been fully validated on Cosmic Prisons. Market values are observations, not trading guarantees. At 1280×720 with GUI Scale 3, parts of the Config GUI can overflow or feel cramped.

## What is in the beta

- **Sci-fi item art:** 35 approved built-in textures for Shards, Contraband, Books, Revealed Books, Keys and Charge Orbs. Items without an approved replacement keep their existing Cosmic Prisons / Minecraft appearance.
- **Config GUI:** the production configuration screen organizes module controls and settings into searchable categories. `/prisons` opens the dashboard; the default configuration key is shown in Minecraft's Controls menu.
- **Inventory Item List:** browse, search and filter the item catalogue from the inventory; item cards use the actual item stacks and show original game tooltips.
- **Auction House analytics:** the overlay reads visible Cosmic market menus and local observations to add comparison context. Its behavior has not been fully checked against a live server.
- **Energy Exchange analytics:** `/ee` offer details include observed per-1k rates and quantity context where the menu provides enough data. Availability and freshness depend on observed menus.
- **Session HUD:** activity, ores, energy/XP rates and related session readouts are drawn from the client's available game data; some Cosmic-specific fields may be unavailable or unparsed.
- **Mining tools:** Ore Macro, waypoints and route recording are configurable tools. Automation can violate server rules; use at your own risk.
- **Bandit tools:** Spear Helper and Bandit Macro are present, but the Bandit Macro is early work-in-progress and mostly untested on the live server. Do not leave it running unattended.
- **Quality of life:** private-vault overlay, player cards, friends/gang colours, notifications, cooldowns and other client-side helpers.

## Screenshots and texture art

Screenshots on the [website](https://noxtryn.github.io/ThePrisons/#screenshots) are real Fabric client captures from automated local test worlds. The AH/EE captures use synthetic fixtures, not live Cosmic Prisons offers. No live-server claim is implied.

The built-in standard texture source contains the approved 35-piece art batch. Website previews are copied from those same canonical PNG files during the GitHub Pages build; they are not separately redrawn assets.

## Install with Prism Launcher

1. Create or select a Prism instance for **Minecraft 1.21.11**.
2. Install **Fabric Loader 0.17.3 or newer** and use **Java 21**.
3. Add Fabric API for Minecraft 1.21.11 to the instance. Mod Menu is optional.
4. Download the current JAR from [GitHub Releases](https://github.com/Noxtryn/ThePrisons/releases), then in Prism open the instance's **Edit → Mods → Add** and select the downloaded `.jar`.
5. Launch the instance. Open `/prisons` in-game for the configuration dashboard. The release page identifies the matching JAR and version.

## Compatibility

| Component | Supported version |
| --- | --- |
| Minecraft | 1.21.11 |
| Fabric Loader | 0.17.3+ |
| Java | 21+ |
| Fabric API | Required, for Minecraft 1.21.11 |
| Mod Menu | Optional |
| Server | Built for Cosmic Prisons; generic client features may work elsewhere |

## Known limitations

- Config GUI content can be cramped or clipped at 1280×720 / GUI Scale 3.
- Cosmic Prisons live `/ah` and `/ee` acceptance, live market observations and server-specific lore have not been fully tested. Synthetic client fixtures only verify rendering and calculations against test data.
- Market history, confidence and freshness depend on observations available to the client; missing data should not be interpreted as a quote.
- Bandit Macro remains experimental; automation and aim assistance may breach server rules or result in sanctions.
- The current repository state is the release candidate. A downloadable beta JAR is available only after the prerelease is published on [GitHub Releases](https://github.com/Noxtryn/ThePrisons/releases).

## Community and project links

- [Official website](https://noxtryn.github.io/ThePrisons/)
- [GitHub repository](https://github.com/Noxtryn/ThePrisons)
- [Releases](https://github.com/Noxtryn/ThePrisons/releases)
- [Report a bug / give feedback](https://github.com/Noxtryn/ThePrisons/issues/new)
- Discord: no public invite is configured on the project website yet; use GitHub Issues until one is published.

ThePrisons is an unofficial fan project and is not affiliated with Mojang, Microsoft or Cosmic Prisons. Use it at your own risk. The source code is All Rights Reserved; see [LICENSE](LICENSE).
