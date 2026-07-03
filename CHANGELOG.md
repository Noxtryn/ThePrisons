# ThePrisons v1.0

ThePrisons is now a full Cosmic Prisons client utility mod with expanded HUD control, improved tracking reliability, bandit-rush flow improvements, and integrated resource pack support.

This release focuses on making day-to-day gameplay clearer and more customizable: cleaner widgets, stronger alerts, better XP/Energy session behavior, smarter cooldown logic, and a bundled PvP pack that installs and enables automatically.

<details>
<summary><b>✨ What Changed</b></summary>

- Upgraded release to **v1.0** with aligned mod metadata and jar naming
- Reworked session stat parsing so XP/Energy gains are tracked more reliably in real gameplay contexts
- Added API-based XP delta fallback to stabilize hourly/session tracking when chat lines are inconsistent
- Improved bandit-rush filtering to prefer rushes matching the last killed ore bandit tier
- Added low-health and low-hunger announcements with cooldown handling
- Fixed false "Ready" states after inventory reordering and reduced join-time announcement noise
- Removed outdated item overlay-number feature and retired Event HUD output
</details>

<details>
<summary><b>🖥️ HUD & UI</b></summary>

- Split main HUD into independently movable widgets (Pet/Trinket, Session XP, Energy)
- Added per-widget mouse-wheel scaling in layout mode
- Merged Pet + Trinket into one combined widget for cleaner layout management
- Made alert popups significantly larger and more prominent with modernized styling
- Added armor destroy-rate percentage display directly in the armor HUD
- Session XP/Energy widget display is now disabled by default for cleaner first launch
</details>

<details>
<summary><b>🏴 Bandits & Pathing</b></summary>

- Pathfinding is gated to valid Bandit Rush context in the same area to reduce noise
- Improved path visuals with thinner, ground-following, smoother and more detailed traces
- Rush alerts/titles are restricted to relevant rush context instead of all detections
- Removed legacy bandit highlight/hitbox rendering behavior in favor of cleaner visuals
</details>

<details>
<summary><b>🎨 Resource Pack Integration</b></summary>

- Bundled the PvP Cosmic texture pack into the mod resources
- Automatic install to the game `resourcepacks` folder on client start
- Automatic default enablement via `options.txt` normalization (`file/...` format)
- Added validation/repair so the pack stays recognized as a valid resource pack (`pack.mcmeta` safety)
- Cleaned up older ThePrisons pack variants so only the intended pack remains active
</details>

<details>
<summary><b>🔗 Links</b></summary>

- [Source Code](https://github.com/olb-freelocs/ThePrisons)
- [Modrinth](https://modrinth.com/mod/theprisons)
</details>