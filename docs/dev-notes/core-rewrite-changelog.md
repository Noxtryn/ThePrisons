# ThePrisons (Unreleased) — Core rewrite

<details>
<summary><b>🗄️ Finished-mod release: dashboard, storage overlay, item look, HUD redesign</b></summary>

- **Shipped feature set** (`modules/FeatureProfile.java`): the features listed there are always on, the rest off; users cannot switch features. The Ore Macro, route tools and the welcome setup exist only in the developer build (`-Dtheprisons.dev=true`, also used by `runClientGameTest`)
- **Dashboard** replaces the module GUI (`/prisons`, keybind, Mod Menu): Overview (active features), Design (theme, card darkness, animations, slim font, live preview), Controls (keybinds), HUD (HUD editor). Animated: drifting stars, shimmering title, flowing accent lines, sliding tab underline, sliding pages, staggered tiles that lift on hover, pulsing status dots, smooth switches and slider
- **HUD editor** in the same look: drag, scroll to scale, right-click to reset, grid / centre snapping with guides, reset all
- **Scoreboard**: replaces the server sidebar with fixed rows (player, economy, world, session); unknown values show "–"
- **Better Tab**: tab list as a card - player columns with heads, rank colours, ping bars, server info without store / website / Discord lines
- **HUD redesign**: pets & trinkets (only real pets / trinkets, real item icons, ACTIVE / READY / countdown from chat and item cooldowns), cooldowns & satchels, session stats (now also Rested XP, charge orb bonus, Anti XP Tax and Lucky pet effects as boosters), armour (durability instead of wear), notifications as sliding cards
- **Storage Overlay**: `/pv` opens all private vaults as cards; open pages are fully usable; inventory and hotbar below; theme colours
- **Comic textures** (Design setting, on by default): every texture the game loads - from vanilla and from every loaded pack - gets the comic / MMORPG look while loading (pixel-art upscale, livelier colours, cel bands, ink outlines, rim light); nothing of Mojang's art is shipped. The mod's own hand-made textures, clouds, colour maps, fonts and maps stay as they are
- **Look pack on top**: the mod's blocks and worn armour live in a built-in pack (`resourcepacks/theprisons_look`) that is always added last, so it wins over loaded texture packs
- **Worn armour in 4x detail** (256x128): layered pauldrons with rivets, winged chest emblem with a faceted gem, abdominal plates, belt and buckle, knee cops, plated boots, T-visor helmet with crest and ear plates; armour icons are cut from the same texture
- **Scoreboard reads the real Cosmic sidebar**: heading / value pairs (Balance, Current Zone, Criminal Record, Cosmic Coins, /top Credits), the day, the planets board; the sidebar's Level / Progress is the held pickaxe's and shows in its own PICKAXE section (only while holding it); the mining level comes from the XP bar (level cap noticed); server font icons are removed
- **Masks**: Cosmic helmets (player heads, lore "Mask (...)") show the mask in 3D; new Clue Master theme; player heads are never learned as plain items
- **Blocks**: the main blocks (stone types, all ores incl. deepslate / nether, mineral blocks, dirt, grass, sand, gravel, oak, stone bricks, netherrack, obsidian, bedrock) as 64x64 comic / MMORPG textures; tileable stones, glossy gems with sparkles, framed mineral blocks with emblems. Generator: `tools/textures/blocks.py`
- **Session stats per activity**: uptime starts with the first block and runs only for the current activity; every ore family is its own activity (all redstone blocks = "Redstone", quartz = "Meteor Mining", bandits = "<Mine> Bandits"); switching continues the activity's saved numbers (kept across restarts in `config/theprisons/activities.json`); the card's title is the activity; pauses after 2 min idle
- **Scoreboard**: rounded card with drop shadow, a colour per section, rows sliding in, values flashing when they change, plus every other server sidebar line under SERVER
- **Satchels**: icon, fill percent, amount and a fill bar (green -> amber -> red, pulsing from the warning level)
- **Worn armour**: leather ... netherite armour on the player in the same MMORPG look (T-visor helmet, emblem chestplate with shoulder pads, belted leggings with knee guards, boots with cuffs); leather stays dyeable. Generator: `tools/textures/armor_skin.py`
- **Item Textures**: 250 own textures - MMORPG gear (swords, pickaxes, spears, armour by material), cartoon economy items for every Cosmic item family, pets drawn after what they do, themed masks with 3D models on the head; rarity frames in the texture colour for every item; works next to Cosmic Textures. Generator: `tools/textures/`
</details>


<details>
<summary><b>🧱 New core</b></summary>

- One core owns every shared service; it is the only place registering Fabric callbacks. Everything else uses its event bus
- Module system: every feature is a module with settings, keybind, status, `/prisons toggle <module>`, error isolation (a crashing module is disabled, the game keeps running)
- One shared background worker for all planning / path searches (latest-wins jobs, results on the client thread); no blocking search on the client thread anymore
- Shared world cache: demand-driven, budgeted (≤ 6 sections / 1 ms per tick), updated from server block updates instead of blind rescans, bounded memory, immutable snapshots for the worker
- Shared control lease (one macro drives at a time; keys and breaking are always released), shared block breaker
- View motion like a human hand and drawn every frame: minimum-jerk turns (smooth start and stop), durations by Fitts' law (big turns and small targets take longer), moving targets are followed without restarting the turn; applied where vanilla applies the mouse, so turns are fluid at 60+ fps instead of 20 Hz steps. Setting "Turn speed" replaces rotation speed / acceleration
- Shared analytics (sessions, rates, time per state; `/prisons stats`), built-in profiler (`/prisons perf`, Performance Monitor module)
- Module config in `config/theprisons/modules.json`: only changed values are stored, corrupt files are backed up, saves are debounced and written off-thread
- Details: `docs/core-rewrite/`
</details>


<details>
<summary><b>⛏️ Ore Macro (for Cosmic-style ore caves)</b></summary>

- Works in every mine: two packages per ore ("Redstone" = the ore, "Deepslate Redstone" = deepslate ore + block, same for all ores); an ore above your mining level is left out, the rest goes on
- Stays in its mine: where another ore than the selected one begins, it turns back
- Keeps 15 blocks away from wardens (guard NPCs, 1000 HP) at all times
- Decides block by block where to go, always in the middle of the tunnel, towards the ore, turning as little as possible, never back over its own way
- Mines only floor ores (mined ore turns into stone and respawns); never walls or ceiling; goes up only onto a real floor, never up a stair into a wall
- The view never looks around and moves at the game's frame rate: 50° down on flat ground, 82° where it goes down, 68° where it goes up
- Remembers each mine (yield per area, dead ends) across sessions; walks to the richest spot when nothing is in sight
- Pickaxe energy full → applies the first sponge of the inventory to the first hotbar pickaxe and puts it back; ore satchel full → pauses 1–3 s and sends /sellall
- "Macro" HUD: BPS, uptime, blocks, angle, known areas
</details>

<details>
<summary><b>🌌 New module GUI</b></summary>

- Categories Mining, Meteor Mining, Bandit, PvP, Combat, QoL, HUD, General with sub-categories, search (type anywhere or Ctrl+F)
- Module cards with switch and live status; settings panel with switches, sliders, dropdowns, ore chips, keybinds, text, colours, buttons
- Save / Load / Reset module; Right Shift, `I`, `/prisons` or Mod Menu open it
- The v1 HUD / QoL features appear as modules and keep their settings
</details>

<details>
<summary><b>⚡ Performance fixes in v1 features</b></summary>

- Cooldown cache no longer written to disk every 5 s on the client thread; all config / cache writes happen off-thread
- Chat cooldown parsing no longer compiles regular expressions per message
- The HUD reads module rows prepared every 5 ticks instead of querying modules every frame
</details>

<details>
<summary><b>🗄️ Archived</b></summary>

- The previous macro core, ore macro, config screen and their tests are kept unchanged in `archive/pre-core-rewrite/` (not shipped). Not carried over: randomised "human" rotations, staff-check failsafes, x-ray dig mode, ore heatmap learning
</details>

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