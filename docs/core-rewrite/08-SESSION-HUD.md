# Session HUD ("Nebula" look)

Module `session_hud` (category HUD, on by default; position / scale / slim font in the Click GUI).

| File | Role |
|---|---|
| `modules/hud/CosmicStats` | Pure parsing + rates (unit tested in `CosmicStatsTest`). |
| `modules/hud/NebulaHudRenderer` | Card: dark translucent, soft corners, animated pastel chroma line + title, grey labels / pastel values, hairline separators, mode pill. Font `theprisons:nebula` = Noto Sans Medium (TTF provider, falls back to the default font). |
| `modules/hud/SessionHudModule` | Feeds the stats, keeps the snapshot, draws it (HudRenderCallback). |
| `core/client/ClientReadouts` | Sidebar, boss bar titles (`ThePrisonsBossBarHudAccessor`), item lore. |

Rows and sources:

| Row | Source |
|---|---|
| UpTime | Since joining the server (HH:MM:SS). |
| OP/s (avg) / Ores | Broken ore + every ore ≤3 blocks around it (±1 high) that turns into something else within 10 ticks (Fractured / Shatter / left-right blocks). Last 60 s; avg = session. |
| Energy/h | Held pickaxe's lore energy going up (orb "Absorbed N Cosmic Energy" excluded); fallback action bar "+N Energy". Last 5 min → per hour. |
| XP/h | Action bar "+N XP"; fallback vanilla XP points. Last 5 min → per hour. |
| Current Tax | Sidebar line with "tax" and %; "No tax applied due to Inmate Rations" → 0 % (Rations) for 60 s; "—" unknown. |
| Server Booster | Only while active: sidebar / boss bar "booster" lines with server / global / event, chat activation lines. |
| Booster | Only while active: own boosters (sidebar / boss bar / "activated ... booster for 30m"), "Inmate Rations: 2x ..." (2 min after its last message, time left unknown). "You received ... Booster" = an item, not active. |
| Learned Routes | Recorded routes + the guarded routes in the route memory (see below). |
| Mode pill | `OreMacroModule.pathMode()`: Tunnel / Cave / To tunnel / Planned / Flight / Route / Travel / Break / Wait. |

Cost: pending proc checks per tick (a small map), sidebar / boss bars / lore / XP every 10 ticks, snapshot every 5 ticks;
the frame only draws (~15 text calls). The exact Cosmic wording of action bar gains, booster bars and the tax line was
not in any log: lines with booster / energy / xp / tax that no rule understood are logged once per wording as
`[session_hud] unparsed ...` (setting "Log unknown ... lines") so the rules can be fitted. Not tested in game.

## Update 2026-10-03 (later): text stripper, procs, ETA, durability, route memory

* `core/client/TextStrip.strip`: every formatting code (`§0-9a-fk-orx`, any case, hex `§x§r§r§g§g§b§b`, stray `§`),
  zero-width characters, non-breaking spaces - applied to sidebar, boss bars, lore, chat / action bar and the ore macro's
  chat and item names before any regex.
* Ores: blocks taken along by a proc are counted apart (`addProcOres`); the HUD shows "procs +N%" (proc ores per hit
  ore, measured) and the pickaxe's Fractured / Shatter lore lines. Proc blocks are not marked as air: on Cosmic they
  turn into stone (still floor), and the world cache already updates from the server's block packets.
* Level-Up ETA [HH:MM] = XP left / XP per hour (5 min). XP left: sidebar "XP: a / b" line, else vanilla
  (1 - progress) x next level XP.
* Pickaxe: below 10 % durability (item data; unbreakable = no warning) a blinking red "REPAIR REQUIRED" row on top.
* Route memory (`modules/mining/ore/RouteMemory`, `config/theprisons/routes_memory/<server>_<dimension>.json`): planned
  and tunnel routes walked to their end while inside the guarded area all the way (guards known) are saved with start,
  end, middle line (planner nodes, max 2000) and ores per block; the same route again (start / end within 4 blocks) is
  merged. Before planning, a remembered route starting within 6 blocks that gave ≥0.3 ores per block, rested ≥10 min
  (ore respawn) and still lies inside the guarded area (and not the avoided direction) is taken at once.
  Tests: `RouteMemoryTest`, `TextStripTest`, `CosmicStatsTest` (ETA, procs, warning).

## Update 2026-10-03 (part 2-3 completed)

* Proc forecast: `CosmicStats.procChance` sums the % of Fractured / Shatter lore lines; forecast OP/s = direct hits per
  second (60 s) x (1 + chance x blocks per proc). Blocks per proc is measured (proc ores / hits that took any along;
  2 until the first proc). HUD row "Forecast" only while the lore gives a chance.
* Route memory on the route, not only at its start: `RouteMemory.best` matches the nearest middle-line node within 6
  blocks (3 high) with ≥8 nodes left and takes the rest from there (`Match`).
* Utility weighting: `OrePlanner.preferring` makes nodes on and 1 block beside the middle lines of usable learned
  routes (rich, rested, guarded) 0.3 cheaper in the planner's flood (plan and tunnel search) - ways follow them where
  they lead to the ore anyway (`OrePlannerTest.ofTwoEqualWaysTheLearnedOneIsTaken`).
* The memory is loaded when the world is entered (the session HUD asks `OreMacroModule.syncRouteMemory` every 5 s),
  also while the macro is off; JSON is made on the client thread, written atomically on a worker.

## Update 2026-10-03: route memory I/O and compression

| Class | Role |
|---|---|
| `RoutePath` | Compression: 3D Douglas-Peucker (kept where the line bends >1.5 blocks sideways or >1 block in height) → a straight section is start + end, bends add waypoints; width radius = mean `tunnelWidth` / 2 every 8 nodes (8 in a cave); `expand` back to one node per block. |
| `RouteCodec` | File format v2 (compact JSON): `route_id, mine_zone, kind, efficiency_score (ores per 100 blocks), guarded, width_radius, waypoints, ores, blocks, runs, last_run`. v1 files (every node) are compressed on reading. |
| `RouteIo` | One daemon thread ("theprisons-route-io", min priority): load = read + decode there, result delivered on the client thread (`client::execute`), applied only if still the same world; save = immutable snapshot from the client thread, coalesced per file (only the newest is written), encode + temp file + atomic move on the I/O thread. Single thread = loads and writes never overlap. |
| `RouteMemory` | RAM cache: `ConcurrentHashMap` of immutable routes, changed only on the client thread; AABB per route (waypoints + width + 6, ±4 high) in a 32-block grid index (immutable, republished on change). `best`: grid cell → box check → exact path only then. `preferredColumns` limited to boxes within the planner radius. |

`mine_zone` comes from the server's "(!) Welcome to the X Mine!" / "(!) X Mine" lines (`OreMacroModule.currentMine`).
Tests: `RouteMemoryTest` (compression, merge + file size, v1 conversion, background save/load with coalescing, box filter).
