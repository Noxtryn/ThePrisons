# 06 · Ore Macro

Module **Mining › Ore Macro**, key **K**. For Cosmic-style ore caves: floor, walls and ceiling are full of ore, a
mined ore **turns into stone and respawns** after a delay (server plugin *CosmicMining*, below). The macro walks
**straight through the middle of the cave's ways** and mines the floor ores it passes. Walls and ceiling are never
mined. (The earlier "find ores in stone walls" macro was removed.)

## Usage

- Open the GUI (Right Shift / `I` / `/prisons`) → Mining › Ore Macro, pick the ore, press **K**.
- The only visible setting is **Ores**: packages *Coal, Iron, Copper, Lapis, Redstone (default), Gold, Diamond,
  Emerald*. A package includes the ore, the deepslate ore and the ore block.
- Everything else is a tuned standard configuration (hidden): reach 4.4, lane band 5, drop ≤ 3, auto angle, sprint.
- HUD widget **Macro** (movable / scalable in the HUD layout): BPS (60 s window and average), uptime, blocks
  (incl. the pickaxe's side blocks), view angle (and ground: flat / up / down / settle), state.

## What the macro does (specification)

| # | Rule | Implementation |
|---|---|---|
| 1 | Works for every ore, one mine at a time | two packages per ore: *Redstone* = redstone ore only, *Deepslate Redstone* = deepslate redstone ore + redstone block (same for coal, iron, copper, lapis, gold, diamond, emerald); all on by default. A package whose mining level is too high (Mining Fatigue) is left out for the run |
| 0 | Keeps running when tabbed out | while a macro runs, the pause menu vanilla opens when the window loses focus is skipped (mixin on `MinecraftClient.openGameMenu`), so alt-tab does not pause it; Escape in the focused window still opens the menu (and pauses the macro). |
| 1a | Block scanner | scans 32 blocks around the player (±16 high); scanned blocks are kept up to 64 blocks away (scan radius + 32 eviction margin). No lines are drawn in the world while the macro runs (hidden setting `show_route`, default off). |
| 1b | Stay in the mine | the scanner indexes every ore; an exposed ore of a package that is not selected marks the border of the mine: a way ends 2 blocks before it, so the macro turns back |
| 1c | Keep away from wardens | wardens = guard NPCs (Citizens, 1000 HP, "Warden" in the name / name tag; hitting one makes you a villain). No way leads within 15 blocks of one (same level ± 8); already closer, the macro only moves away. Travel paths avoid the zones too |
| 1d | Hand-placed borders | `/theprisons set border` marks the block you look at (up to 64 blocks): no walking and no mining within 5 blocks of it (±8 in height); already inside, only ways leading out. Saved per server + dimension in `config/theprisons/borders/`, always highlighted (red block outline, a 5-block ring on the floor and at head height). Also `remove border` (nearest to the looking point), `clear borders`, `borders` (list). (`BorderMarks`, `BorderZones`) |
| 2 | Only floor ores; walls and ceiling never | a target is a *surface ore* (open above) not on a wall; wall = ground rising ≥ 3 within 3 blocks |
| 3 | Decide block by block, always in the middle of the tunnel | `TunnelSteer`, every tick: 24 directions walked in thought up to **32 blocks** (free way, ore in the pickaxe's cut - ore 16 blocks away counts half -, rises, overlap with the last **1024** walked blocks); best = min(free, 8)/8 × (ore + 0.05/empty block) × turn factor (45° 0.8, 90° 0.5, back 0.2) × 0.8ⁿ per step up × (1 − 0.8·overlap) × area value; the held direction is kept unless another is 1.3× better; then it steers to the middle between the walls (measured 2 blocks ahead, smoothed) |
| 4 | Up only onto a real floor | a step (or a stair of up to 4 single steps) is only walked when ≥ 3 flat blocks follow ahead at the top; a stair into a wall, a ledge, or ground that only goes on back over the way (an overhang / the ceiling) is not a way |
| 5 | No overlapping routes | directions over the blocks just walked are worth 80 % less; turning back ≈ 0.1 of going on |
| 6 | Never stop | no ore within sight in any direction for 2 s → walk to the richest reachable spot (`LanePlanner.travel` + `LaneDriver`), then steer again; stuck 1.5 s → that direction is avoided for 3 s |
| 7 | The view never looks around, fluid at any frame rate | yaw = walking direction, pitch from the stairs ahead, up and down alike (straight on 46°; tread 1 block → 82°, 2 → 74°, 3 → 68°; changed 0.8 blocks before each step; last step up before ≥4 flat blocks → briefly 82°, then 46°); critically damped springs integrated every rendered frame (`FollowMotion`; yaw settles in ~0.4 s, pitch in ~0.5 s) |
| 8 | Always mine | whatever floor ore passes under the crosshair; the pickaxe's side blocks are counted |
| 9 | Remember the mine | `RegionMemory`: blocks per second per 8³ area (every 10 s) and dead ends, saved per server / world in `config/theprisons/regions/`, 6 h half-life |

**Chat reactions** (server/system messages only, never player chat):

| Message | Reaction |
|---|---|
| `[!] Your pickaxe energy is full! Please /extract or level it up to continue mining.` | stop, open the inventory, left-click the first `minecraft:sponge` (main inventory from the top left, then the hotbar), left-click it onto the first pickaxe of the hotbar from the left (wooden … netherite), put the sponge back into its slot, close the inventory, go on. If the server swapped the items instead, the pickaxe is put back first. No sponge / no pickaxe → the macro stops with a message |
| `[!] Your Ore Satchel is now full!` | send `/sellall` right away while the macro keeps walking and mining (at most once per second) |
| `[!] Your inventory is full!` | send `/sellall` right away while the macro keeps walking and mining (at most once per second) |
| (no message) pet in the hotbar off cooldown, name contains "anti xp tax pet" (setting *Pet name contains*) | select it, right click once, back to the item held before |
| (no message) ability item in the hotbar off cooldown, name contains "fireball" (setting *Ability items contain*, comma separated) | stop, select it, hold right click for 2.2 s without mining, back to the item held before, walk on |

Only the ore packages are a user setting; everything else is a hidden standard value. HUD widget **Macro**: BPS,
uptime, blocks, angle + ground state, known areas, state.

## Server plugin (CosmicMining 0.1.2, checked 2026-09-29)

- Bukkit / Spigot 1.12.2 plugin. No network access except a MySQL connection to `localhost` (user `root`); SQL is
  built by string concatenation.
- Left click / break on an ore or ore block (coal … emerald, ore and block) with permission `cosmicmining.minearea`:
  the block becomes `STONE` immediately and a delayed task restores the ore.
- Level gates: coal 0, iron 10, lapis 30, redstone 50, gold 70, diamond 90, emerald 100. Too low → Mining Fatigue 255
  and the break is cancelled.
## Tests

- `TunnelSteerTest`: middle of the tunnel, branch with ore at a junction, not back over its own way, no stair into a
  wall but a stair onto a floor, no swinging between two equal branches.
- `LanePlannerTest` (travel, surface / wall / climb rules, region memory), `LaneDriverTest` (travel driving),
  `TerrainPitchTest`, `RotationTest`.
- `RedstoneCaveClientGameTest` (real client, terraced cave split into tunnels, server stand-in of the plugin incl. the
  3-wide pickaxe; then the same cave rebuilt as a lapis mine). Last run: redstone 585 ores in 120 s (**4.9 BPS**),
  lapis 276 ores in 60 s (**4.6 BPS**), 0 wall / ceiling hits, 0 stuck, 0 backwards, no walk to a far spot needed,
  standing 3.3 %, macro 0.14 ms per tick on average (max 1.1 ms).

## Routes (record & replay)

| What | How |
|---|---|
| Record | Options › Controls › ThePrisons: bind **Start route recording** and **Stop route recording** (unbound by default). Start, then walk and mine the route by hand. The action bar shows the waypoints and the ore so far; the waypoints are drawn in cyan. |
| Waypoints | Only corner points (`RouteTracker`): a new one where the view turned > 20° since the last waypoint, the player is 2 blocks left/right of the line walked so far, went 2 blocks up/down, or after 24 blocks. Nothing before the player moved 1 block away (turning on the spot does not count). The end point is added on stop. |
| Save | The stop key opens a naming window (Enter / Save = save, Discard / Esc = throw away). The route is saved under the ore mined most while recording ("Other" if none), per server + dimension in `config/theprisons/routes/`. Fewer than 2 waypoints → discarded. |
| Replay | Ore Macro: 1. **Route** (dropdown, grouped by the ore it was recorded with - only for sorting), 2. **Ores** (what is mined). Start with K. **Joining the route** (start, teleport): standing on the route already (within 5 blocks of the line between two waypoints) it walks on to the next waypoint - no step back; otherwise the pathfinder walks to the **nearest waypoint** (from any point; mining on the way with the normal floor view; dropping down any height is fine - no fall damage, route paths may drop up to 32 blocks and a lower waypoint counts only its plain height difference, a higher one three times) and the route begins there. **After the last waypoint** straight on to waypoint 1 like any other part of the route (the route is a loop: … → last → 1 → 2 → …): mining on the move, no pause, same rules. **Between two waypoints** the normal tunnel steering (`TunnelSteer`: straight on, mining on the move, in the middle, smooth curves, never into walls) with the route as a guide: **never a step back** (directions more than 90° off the route line are not taken, even when all ore is behind), directions along the line preferred (half weight at 40° off), and it keeps its **lane**: the route line itself is the lane whenever the player is within 1.5 blocks of it (all ores still there → it walks the lines between the waypoints straight); **Lane change** (checked live every tick, also while still moving over): the strip the pickaxe mines (the block + 2 left + 2 right = 5 wide) is scanned **10 blocks ahead** for every lane inside the 5-block corridor; a floor block that is not a selected ore counts as stone/deepslate. Each lane is rated in ores per second: its ores ÷ the way there (straight over to it while walking 10 blocks on). As soon as a lane beats the current one by at least 2 ores and 10 %, it turns **straight over to it at once** (no waiting for the strip to run empty, no settle distance) - only to a lane whose whole 5-wide strip is floor, so at the edge there are still 2 blocks of floor to the wall. No tunnel centring on routes (the lane positions the macro). **Stairs** (e.g. spiral stairs): on a route every 1-block step is simply walked up with a jump - the tunnel-mode rules "climb only onto ≥3 flat blocks", "≥3 up within 3 blocks = wall" and the climb penalty do not apply there; real walls (2+ blocks) still end a way. Moving aside (max. 5 blocks) is a freedom when there is ore, never a duty; without ore it just walks on to the next waypoint and mines again as soon as ore is ahead. A waypoint counts as reached within 1.5 blocks or when passed beside the line. **Emergency**: only when the steering finds no way on (or is stuck twice), the pathfinder walks to the waypoint, then the steering again. **Failsafe (5 s)**: a waypoint that cannot be found (no path) or reached (stuck, no way on) and got no closer by 1.5 blocks within 5 seconds is skipped (warning); a start waypoint that is not reachable → the next one is tried; none reachable → the macro stops. Borders and wardens are respected, chores still run. HUD line "Route: name · waypoint i/n". |
| Waypoint names | Waypoints are named by their number in the route (1, 2, 3, …), drawn above each waypoint (recording, replay, editor). Adding or deleting one renumbers the ones after it. |
| Waypoint editor | GUI › Mining › **Waypoint Editor**: pick a saved route, "Start editing" (closes the GUI). The route is shown with its numbered waypoints; the waypoint you look at turns white. **Left click** on a block adds a waypoint where you would stand there; its number is where it makes the smallest detour between two neighbours, or before 1 / after the last when it lies beyond the start / end in the route's direction (`WaypointMath`). **Right click** on a waypoint deletes it (at least 2 stay). **Enter** saves, **Backspace** discards. Clicks do not break or use blocks while editing. |
| Commands | `/theprisons routes` (list), `/theprisons route delete <name>`. |

## Item sorter

Settings group **Item sorter**: `Private Vault - Shards` (default 7) and `Private Vault - Other` (default 8); an empty field switches the sorter off. Checked once a second (not during a break): when **13 of the 36 inventory slots (35 %) hold prismarine shard stacks, or 18 of 36 (50 %) hold items that are no blocks** (every block - ores, deepslate ores, ore blocks - does not count; the kept items below do not count either) the macro stops walking and runs, 250 ms between the steps:
`/sethome tmp` → `/spawn` → wait for exactly "Teleporting you to spawn in 1 seconds… (DO NOT MOVE)" (not 11, 5, …) (max 60 s, else stop + alert) → 1.5 s → `/pv <shards>` → shift-click every `minecraft:prismarine_shard` in → Esc → `/pv <other>` → shift-click everything else in **except** satchels (name contains "Satchel"), pickaxes (`ItemTags.PICKAXES`), `minecraft:sponge` (absorber), `minecraft:light_blue_dye`, `minecraft:player_head` (pets) and the auto-use ability items → Esc → `/home tmp` → wait for exactly "Teleporting you to … home in 1 seconds… (DO NOT MOVE)" → 1.5 s → `/delhome tmp` → 50 ms → macro goes on (re-joins the route from where it is).
**Stops the whole macro with an alert** (anvil + pling sound, red chat line, notification): the vault's last slot is taken, a shift-click moved nothing for 0.5 s (vault full), the vault did not open within 5 s, or no "… home in 1 seconds" message within 60 s after `/home tmp`. While the sorter runs, a world change counts as the macro's own teleport (`AutomationModule.travelling()`), not as a safety stop. Logic: `ItemSorter` (+ `ItemSorterTest`).

**No mining while warping:** during every chore (item sorter trip, energy, pet, ability) `act()` never mines. A teleport the macro did not do itself (spawn, warp, mine reset) → 3 s complete standstill, then: no ore of any mine within ~24 blocks → the macro stops with an alert; ore around (mine reset) → it goes on.

## Attacked (group "Defence", setting "On attack: run to a guard", default on)

The server's damage packet (`ThePrisonsLivingEntityMixin` → `CoreEvents.PlayerHurt`) names the attacker:

| Attacker | Reaction |
|---|---|
| mob / NPC | "Hit the mob once first" (default on): stand, look at it (springs ω 25), one hit with the item in hand once it is under the crosshair and the attack is ≥90 % charged; skipped when out of reach, dead or not hit within 1 s. Never a player entity (not even player-looking NPCs) or a guard. Then: stop mining, sprint (pathfinder, BREAK phase with `fleeing`) to the nearest guard seen this run (name or name tag contains "warden", "guard" or "enforcer", ≤150 weighted blocks), stand in the 1.5–3 block ring until 5 s without a hit, then go on (route: nearest waypoint). The break schedule is kept. |
| real player (player entity in the tab list) | stop + alert |
| a guard itself | stop + alert |
| no guard known / no way there / still hit after 60 s at the guard | stop + alert |
| no attacker (fall, fire, lava) | ignored |

## Setup, language, menu

- **Required settings** (`Setting.required(check, problem)`, shown only while visible): ores ≥ 1, both vault numbers (different) while the item sorter is on, pet name while "Use pet" is on, ability names while "Use item abilities" is on. `SetupGate` (in `AutomationModule.canEnable`) refuses the start until the welcome setup was finished once and nothing is missing, and writes what is missing **only into the player's own chat** (`ModChat`, never sent to the server): mod name light blue bold, heading pink bold, text white, each line with a **[Set now]** link → `/prisons open <module> <setting>` (client command, runs locally) opens the menu scrolled to that setting, which flashes pink.
- **Welcome setup** (`gui/welcome/WelcomeScreen`, `SetupFlow`): opens by itself on the first join until finished; again via `/prisons setup` or the menu's "Setup wizard" button. 8 steps: language → how it works (+ start key) → ores → route → item sorter → pet/abilities → breaks/sprint → checklist. The controls are the real settings; "Next" is blocked while a field on the page is red.
- **Language** EN/DE (`core/i18n/I18n`, texts in `assets/theprisons/i18n/de.json`, English text = key): button at the top of the menu and the welcome screen, `/prisons lang [en|de]`, setting "Language" in Click GUI - switches live.
- **Menu**: black translucent, pink bold headings, numbered sections (step by step), missing settings red with a "Before you start" box, modules with missing settings show "Setup needed: n".

## Update 2026-09-30: dense ore, small pieces, drops, learned routes

- **Dense ore first:** every ore on a way counts `1 + neighbours / 4` (other floor ores within 2 blocks, ±1 high). Free mode checks every 10 ticks for a way ≤90° aside that is ≥2x better with ≥6 weighted ore and turns the axis there.
- **Small pieces beside the way:** a lane counts as soon as its own middle is floor for 3 blocks (also right at the wall); a single ore up to ~4 blocks aside beats a mined-out lane (`LANE_GAIN` 0.9).
- **No height limit / drops:** steps up and down are all walked; ledges up to 32 blocks deep are dropped down (no fall damage) - only when the way down there has ≥4 weighted ore, else the way ends at the ledge.
- **Learned routes** (setting "Learn routes", group Route, default on): with no route picked, the walk is recorded (corner waypoints, `RouteTracker`) and saved as "Learned <ore>" with ores/min (`per_minute` in the routes file). After 10 min / 150 waypoints it is saved and followed from then on; stopping after ≥2 min also saves it. Next start with a learned route of a picked ore within 48 blocks (±16 high) → that route is walked (best ores/min). A later run replaces it only with more ores/min.

## Update 2026-09-30: floor rule, flat ways, 8m energy

- **Floor:** only stone, deepslate and ores carry a foot; every other block counts as void (never stepped on). The
  world cache marks stone / deepslate with `SectionSnapshot.FLOOR`; `Walkability.mineFloorOnly(x, z)` applies it (the
  column the player stands in is exempt). Used by the steering, lane planner / travel and the route approach; the
  walk to a guard (breaks, fleeing) is not restricted.
- **Flat first:** every step up or down within 8 blocks keeps 0.6 of a way's value (`TunnelSteer.FLAT_FACTOR`, all
  modes, also lane strips) - with a block on the left and a flat way on the right it walks the flat way; a step is
  still taken for clearly more ore. Obstacles end a way as before.
- **On ore:** an ore right on the path counts 1.5x (`ON_PATH`); denser patches win sooner (`CLUSTER_SWITCH` 1.6,
  checked every 5 ticks).
- **Pitch down:** 62° (`TerrainPitch.DOWN`, `DEEP`). *(Superseded 2026-10-01: tread-based angles, see row 7.)*
- **Item sorter:** also starts at ≥8m Cosmic Energy in the inventory (items named "30,000,000 Cosmic Energy", amount
  × stack size; `ItemSorter.energy`).

## Update 2026-09-30 (afternoon): back to the v1.0 jar pathfinder + tunnel view + guard walls

- Pathfinder = the state of `ThePrisons-v.1.0.jar` (before "floor rule / flat first / ON_PATH / cluster 1.6"):
  no `mineFloorOnly`, no `FLAT_FACTOR`, `CLUSTER_SWITCH` 2 every 10 ticks.
- Tunnel view (`TunnelSteer.chooseLane` / `betterLane` / `tunnelLanes`): lanes = the tunnel's middle and the two
  wall lanes (3 wide mined way touching the wall). The lane is kept while ore lies within `LANE_NEAR` 3 blocks in the
  3 wide way; only then a lane with ≥ `LANE_MIN_GAIN` 3 more ores (per 10 blocks, 3 wide) is taken; once picked it is
  walked to without re-deciding; on the new lane it stays until another is clearly better. On a line (axis / route)
  ray directions no longer score ore - the lane alone goes for the ore.
- Another tunnel (`richerAside`): only ≥ 45° aside and ≥ 16 blocks long, and only when no lane of this tunnel is worth it.
  Start: axis = view direction if ≥ 8 blocks of way ahead.
- Height: a stair ending at a wall within 3 blocks (`climbsIntoWall`) counts ×0.1; a stair with the tunnel going on
  is a normal straight way. Lanes with a block to jump on (up and down within 2) or a wall stair are avoided.
- Guard walls (`GuardWalls`, owned by `BorderMarks`): guards (warden/guard/enforcer) within 1024 blocks are remembered
  and saved (`config/theprisons/guardwalls/`). From each guard the tunnel is traced in 4 directions (middle of the
  tunnel, bends, steps) for 128 blocks; a way with no other guard on it, a plain tunnel (≤ 15 % ore floor) and 128
  blocks long (or ≥ 48 and then unloaded / another cave) gets a wall at the guard (at the tunnel mouth if the guard
  stands in the open): every air block of the cross-section, wall to wall, floor to ceiling, 1 thick. Walls are
  `BorderZones.walls` (rays, lane-free paths, travel / break / route paths, mining) and drawn light blue at 25 %.
  Commands: `/theprisons guardwalls`, `/theprisons guardwalls clear`.

## Update 2026-09-30 (evening): automatic border, edges

- **Guard walls = automatic border** (`GuardWalls.walls` / `branches`): from each guard row the tunnel is walked in
  thought (Dijkstra over standable blocks, step up 1 / down 3, diagonals √2) up to 128 blocks of WALKING distance.
  At 16 blocks the fronts split into ways. A way gets a wall only when a tunnel really goes on there (≥16 blocks) and
  no other guard stands on it within 128 walked blocks (not air line). A guard alone (no other guard reached) only
  gets walls on ways behind its back (opposite of yaw). Ways with unloaded blocks: no wall yet (not drawn, not
  solid), retried every 5 s. Wall: 2 blocks behind the guards, square to the way, extended to 3 blocks deep in the
  rock on both sides (max 48). Search capped at 200k blocks (huge open cave: undecided). Hand borders still work.
- **Edges and corners** (`TunnelSteer.cast` → `Ray.graze`, `edgeFactor`): on the floor, a way where the body's side
  (0.45 off the middle) goes over a higher block while the feet stay down grazes its edge/corner. Stepping fully up
  onto it within 1 block is fine. A graze within 6 blocks scales the way's score down to 0.2 (closer = less), so the
  steering goes around edges and corners or straight up onto the block. Test `grazesTheEdgeOfAHigherBlock...`.

## Update 2026-10-01 (evening): free tunnel mode = way + lane

Rebuilt from all the user's pathfinding prompts ("nicht hin und her", "Ores vor ihm → dahin", "erst nach >5 Blöcken
ohne Ore / 5 Blöcken Stone Path wechseln", "Tunnelblick: links / Mitte / rechts", "nicht für 2 Blöcke mehr").
`TunnelSteer.decideFree` (route mode unchanged) holds state instead of re-picking the best ray every tick:

| Rule | Code |
|---|---|
| Ore within 5 blocks on the lane → walk on, nothing else is looked at | `ORE_AHEAD` |
| Lane empty for 3 blocks → other lane of the same way only with ore within 5 and ≥3 more ores (10 ahead); no re-deciding while moving over | `LANE_EMPTY`, `LANE_MORE_ORE`, `LANE_ARRIVED` |
| No ore within 5 and no better lane → look for another way every 3 blocks; held way kept while worth finishing (stone ≤ 1.8× ore), else a worth-finishing / 1.3× better one ≤ 90° | `REVIEW_BLOCKS`, `Pick.REVIEW` |
| Lane blocked (pillar, bump) → nearest lane of the same way that goes on ≥ 8 and is reachable | `detourLane` |
| Way ends → best way ≤ 90° of the way AND ≤ 100° of the real walking direction (last 3 blocks); back / stubs only at a dead end | `Pick.BLOCKED`, `travelDir` |
| New way → lane nearest the tunnel middle unless one has ≥3 more ores; ore right ahead → only lanes just as good | `freshLane` |
| Steering = pure pursuit onto the lane line 3 blocks ahead (≤ 45°) | `pursue` |
| Long ways first (length counts up to 16) | `WAY_LENGTH` |

OreMacroModule: "5 blocks on stone → travel" only while there is no ore within 5 ahead (travel could lead back); the
guard XP tax counts as gone only after 2 sidebar reads in a row (a flicker at 16:44:30 had marked a block in the tunnel
as zone edge → ways ended there → turning back).

Tests (`TunnelSteerTest`): walking simulations - wide tunnel with scattered ore (104 blocks / 500 ticks, 0 back, 0
reversals), pillar, L bend, dead end, equal ways, lane change only for ≥3 more ores.

## Update 2026-10-01: world memory and planned routes

Setting **"Plan routes (world memory)"** (Movement, default on). Scan → plan → walk, all the time.

- **World memory** (`core/world/WorldArchive`, `ArchiveSection`, `ArchiveView`, `ArchiveCodec`): while the macro runs,
  every block of every loaded chunk up to 512 blocks to each side (±64 high) is read once per session, nearest
  first, 1 ms per tick. Each block becomes air / solid / danger / which ore. Server block updates and chunk (re)loads
  make their sections be read again, so mined ores (stone now) and respawned ores stay current. The data is saved as
  gzip region files (32x32 chunks) in `config/theprisons/world/<server>_<dimension>/`, every 60 s and on stop, and is
  loaded around the player at the next start. Regions further than 768 blocks are dropped from memory once saved.
  The client only gets chunks within the server's view distance: what lies further out comes from earlier runs.
- **Guards** seen are saved to `config/theprisons/guards/<server>_<dimension>.json` and known at the next start
  (`GuardArea.remember`); a remembered guard that is not there when the player comes within 48 blocks is forgotten.
- **Planner** (`OrePlanner`, worker thread, on the world memory): flood over the walkable floor (512 blocks, 250k
  nodes; outside the guarded area and borders forbidden; wall blocks within 2 blocks cost extra so ways run in the
  middle of the tunnel). Floor cells 8x8x8: floor ores vs stone; cells with ≥75 % stone are never a goal. The best
  cells become candidate ways, rated by the ores in the 5 wide mining strip per block walked, × "straight on first"
  (1 / (1 + (turn/90°)^4)), × the learned value of the area (`RegionMemory`). Up to 3 legs from rich spot to rich
  spot; a leg with nothing new to mine ends the route. The rest of the current route is rated alike and stays unless
  the new route is 1.25x better.
- **Walking it**: the waypoints (corners of the way) are the guide of the normal tunnel steering
  (`TunnelSteer.guide`), so it walks and mines exactly as on a recorded route (lanes left / right by ore per second).
- **Plans anew**: route walked; a waypoint ahead no longer within a guard's radius; ≥75 % of the exposed blocks
  within 5 blocks are stone / deepslate (then not within 60° of the current direction; again only 8 blocks further
  on); the steering finds no way on; every 5 s (kept unless clearly better). No route found → the old tunnel mode
  (travel to the richest spot) as before.
- HUD: "Plan" (legs, blocks, ores, waypoint) and "World" (sections, chunks still to read, stone share around).
- Tests: `OrePlannerTest`, `ArchiveCodecTest`, `GuardAreaTest.guardsFromAnEarlierRun...`. Not tested in game.

## Update 2026-10-02: straight on before the planner

In game the planner re-planned every 0.2 s with routes of 4-11 blocks (its goal was the nearest ore, the route was
"walked" after a few steps, the next one turned somewhere else) - the player zig-zagged instead of walking straight.

- **Straight on first**: no route is planned while the steering walks freely and has ore ahead. The free tunnel mode
  (way + lane, middle of the tunnel, left / right lane when the middle has no ore) drives.
- **Plans only on a reason**: no ore ahead (the triggers that used to start the short travel: 1 s no ore, mined-out
  share, 5 blocks on stone - the latter two in another direction), ≥75 % stone around (only while no ore within 5
  blocks ahead), a guard gives no safety, the steering finds no way on. The periodic "every 5 s" re-plan is gone.
- **No short routes**: the first goal is at least `OrePlanner.MIN_GOAL` (10) blocks of way away.
- **Route walked** → free steering on, straight from there (no immediate new route).
- Planner found nothing / world not read yet → the old travel to the richest spot as before.
- Test `OrePlannerTest.oreRightAheadIsNoShortRoute`. Not tested in game.

## Update 2026-10-02 (later): middle of the tunnel, always in the guard tax zone

- **Middle, all the time** (`TunnelSteer.recentre`): once every block the tunnel's middle 3 blocks ahead is measured
  on the lane; when it lies ≥0.75 blocks beside the lane the lane moves there (lane = the middle one and the middle
  has about as much ore, or a lane beside it ran out of ore and the middle has ore ahead). Wide rooms (no wall
  within 6 blocks) unchanged. Before, the lane was only centred when a new way was picked, so a tunnel shifting
  sideways left the player at the wall. Test `staysInTheMiddleWhenTheTunnelShiftsSideways` (fails without it).
- **Planned routes walk like the tunnel mode** (`TunnelSteer.direct`): the leg's direction (player → next waypoint)
  is the free mode's way - middle lane, lanes beside it only for ore, re-centring. Before, plans used the recorded-
  route guide mode (per-tick ray choice, lanes by ore per second, no centring). Not possible right now (wall) → the
  way held stays and the direction is tried again every block. Recorded routes still use `guide`.
  Tests `walksAPlannedDirectionInTheMiddleOfTheTunnel`, `withoutAPlanTheOreEastIsWalkedOn`.
- **Guard tax zone learned and saved** (`GuardArea`): every feet block with tax on = inside, tax gone = outside;
  saved per world in `config/theprisons/guardzone/<server>_<dimension>.bin` (gzip, 2 long arrays) every 2 min and on
  stop, loaded at start. Each guard's radius is learned from the tax (farthest taxed distance, capped by the nearest
  untaxed one; saved in the guards file as `{x, y, z, in, out}`). Scoreboard mode: steering stays within the learned
  radii or on known taxed ground (±2 blocks) when guards are known; planned / travel paths only lead over ground
  known to be inside - never into the unknown.
- **Guard recognition**: NPCs in player shape with a version 2 UUID (Citizens etc.) are no longer taken for real
  players. No guard recognised → every 30 s a log line "[ore_macro] no guard recognised; living things within 48
  blocks" (type, name, max HP, UUID version, tab list) to see what the server's guards are.
- The sidebar tax is read every tick (was every 5); "gone" counts after 4 reads in a row (0.2 s, was 2 reads = 0.5 s), so the edge is noticed within about a block.
- Walked out (tax on → gone): the edge is marked 3 blocks to each side, square to the walking direction (`GuardArea.edge`; a 12+ block circle bends < 0.5 blocks over that), so the next try does not walk out beside it.
- HUD "Guard": XP tax on/off, known inside blocks, edge blocks, guards.

## Update 2026-10-02 (afternoon): guard circles of 15 blocks, never stop outside

User rule: every guard (100 HP) guards 15 blocks around it; the circles overlap to one area; the macro stays in it,
without going back and forth; outside it does not stop but gets back in as fast as it can.

- `GuardArea`: guards known → the guards decide (union of 15 block circles, 3D feet to feet; setting "guard_radius"
  default 15, was 12). The sidebar tax only stands in while no guard is known (tax mode, `scoreboard()`). The radius
  learning from the tax (earlier today) is removed - the radius is the rule. Saved guards file back to `{x, y, z}`.
- No back and forth at the edge: own ways end 1.5 blocks inside (13.5); the way back starts only beyond 15 and ends
  at 13 - between them nothing happens. After the way back the steering starts from the view direction (towards the
  guard, i.e. inwards).
- Outside: never stops. While the way back is still being planned it sprints straight towards the guard (jumping up
  steps); then the planned way, always sprinting. No time limit (the 60 s stop is gone); after 4 failed plans the
  next guard. No guard known at all: it no longer stands and stops after 10 s - it walks and mines on.

## Update 2026-10-02 (evening): guards recognised, gaps of ≤5 crossed, mine ground only

Log of the 15:24 run: no guard recognised - the census showed Cosmic's guards are `minecraft:player` entities named
`guard_452_e3d1c7` with custom name "Guard", client max HP 20, v4 UUID, in the tab list - so they were taken for real
players. Without guards the tax mode sent it "back" to the untaxed edge block next to it, where it circled.

- **Guard recognition**: a player entity with a custom name or a `guard_` name is an NPC, not a real player.
- **Gaps**: two guard circles at most `GuardArea.GAP` (5) blocks apart are joined by a strip (ellipse round both
  guards: distances to both ≤ their distance + 2; ~5-6 blocks half-width in the middle). The macro walks across to the
  next guard; a wider gap is its edge. "Outside" (way back) uses the strip 1.5 wider than its own ways.
- **Tax mode way back**: to the known taxed block deepest in the zone (≥6 from known untaxed blocks) among the nearest
  ones within 32 blocks (`GuardArea.taxedTarget`), not to the last taxed block; at a target that is still untaxed the
  next target, no circling. Untaxed blocks are dropped from the taxed set.
- **Mine ground only** (user rule): only stone, deepslate and ores (any `*_ore` block or a target block) carry the
  player; every other block is taboo. Stored per block as `SectionSnapshot.FLOOR` (0x4000) in the ores array
  (`BlockClassifier.stored`), in the archive as `ArchiveSection.OTHER` (255) for other solids. `Walkability.standHeight`
  checks the carrying block (`VoxelView.mineFloor`); the player's own column is exempt (`Walkability.exempt`, the
  steering) and ways may start on any floor (`startHeight`, `settle`). Archive files written before mark other solids
  as stone until those chunks are scanned again.
- Tests: `WalkabilityTest.onlyStoneDeepslateAndOreCarryThePlayer`, `pathsGoRoundForeignFloor`,
  `TunnelSteerTest.onlyWalksOnStoneDeepslateAndOre`, `crossesAShortGapToTheNextGuardButNotAWideOne`,
  `GuardAreaTest` gap / tax target tests. Not tested in game.

## Update 2026-10-03: tunnel geometry, cave mode, look-ahead (state machine)

User: walk the middle of the tunnel, not straight on into walls; scan the walls - does the ground rise fast over a
short width (wall) or go up and then on flat (stair / terrace = way). Plus a spec: middle between D_left and D_right,
slight swerves for ore, guard look-ahead 5 blocks, cave mode with a tunnel score, flat curvy ways, path inertia, the
6-block rule. Checked by replaying `TunnelSteer` on the saved world archive of the user's gold cave (bowl-shaped
tunnels 10-16 wide, big flat halls).

**Geometry (`TunnelMap`, rebuilt per block, radius 24, ~0.2 ms):** floor candidates = columns with a solid block below
and 2 blocks of room above, reached from the player over steps ≤1 up / ≤3 down (block ids are never used). Per
candidate and direction the ground is followed outwards: rise ≥2 within 2 or ≥3 within 3 = foot of a wall - unless it
then goes on flat for 3 blocks (stair ≤4 high onto a terrace = way). Wall feet and everything uphill from them are wall.
**Middle (`TunnelSteer.middle`):** square to the way, from the lane to the first wall on each side (D_left, D_right,
0.25 steps, up to 16); middle = half way; averaged over 3 cross-sections. No wall on a side = cave. Free-mode rays end
at map walls (not in recorded-route mode: a spiral stair route is walked as recorded). `bend` lays the way onto the
middle line, `recentre` keeps the lane on it.

**State machine (module):**

| State | Entered | Does | Leaves |
|---|---|---|---|
| Tunnel (`STEER`, `!inCave`) | walls on both sides 2 blocks in a row | middle lane; ore lanes ≤ `LANE_ASIDE` 2 beside it | 3 blocks without walls → Cave |
| Cave (`STEER`, `inCave`) | 3 blocks without walls | `OrePlanner.tunnel`: best tunnel within 512 blocks per side, `score = ores / distance × guarded × 1 / (1 + |Δy|)`, walked as a planned route | walls again → Tunnel |
| Switch (planned route) | tunnel plan / ore plan | legs walked by the steering (middle of the tunnel) | route walked / blocked / unguarded |
| Look-ahead turn | point 5 blocks ahead not guarded (circles, or learned untaxed blocks) | stop this tick, drop route, `turnTo(yaw + 180)`, plan with Avoid | at once; next turn ≥3 s later |
| Flight (`GUARD`) | already outside | unchanged: back in fast | guarded again |

**Other rules:** planner ways cost +3 per block up/down (`Walkability.climbCost`) - flat curves beat stairs, stairs
only where no flat way leads; planner ways back (>135° off) count half (path inertia; the free mode only turns back at
a dead end). 6-block rule: `BARREN_BLOCKS` 6; before leaving, `TunnelSteer.lookAside` takes a lane ≤3 left/right with
ore within 5 blocks and resets the count.

Replay (12 starts × 60 s): wall < 2.5 blocks ahead 3-36 → 0-11 ticks per run, mean distance from the map middle
1.2-3.9 → 1.0-1.8 blocks (0.7-1.0 with the middle lane only - the rest is ore lanes), halls left by tunnel plans. Tests:
`TunnelMapTest` (bowl slope = wall, stair onto a terrace = way, rock), `OrePlannerTest.fromACaveTheRichTunnelIsTheGoal`,
`aFlatCurveBeatsAStairUpAndDownAgain`, `GuardAreaTest.lookAheadSeesTheCircleEnd`, the tunnel tests of the first
2026-10-03 change; `movesOverToALaneOnlyForAtLeastThreeMoreOres` now expects a lane ≤2 aside. 202 tests green. Not
tested in game.

## Update 2026-10-03 (night): turning in circles fixed

User: "dreht sich im Kreis". Game log: start at 737 81 238 between 12 guards, the guard look-ahead turned round after
3 s, a new route was planned. A replay of the module loop on the saved cave with those guards showed 4,600-21,800°
of turning per minute:

* The guard look-ahead's 180° turn + replan: the new route's first leg led to the circle edge again → turn again
  (every 3 s; with 15-block circles almost everywhere is within 5 blocks of an edge). **Removed** - the steering ends
  its ways at the guard edge anyway (0 ticks outside in every replay without it).
* Planner vs. `TunnelMap`: a planned leg up a slope the map calls wall → rays ended → turned round every 1.5 blocks
  forever (pocket at 711 229). With a planned route the map walls no longer end rays (the planner checked the way).
* Pocket guard: 3 turns >135° within 3 blocks and 15 s → no way on → the module plans a route elsewhere (also without
  an active plan).

Replay after the fix: ~4,600-5,800°/min (the guard strip between 704 and 738 walked end to end, turning at the edge,
plus ~1°/tick pursuit corrections); the extreme cases are gone. Harness kept in the session scratchpad
(CircleReplayTest).

Middle (same night, user "nicht in der Mitte"): replay in the guard strip 704-738 measured 1.3-1.9 blocks from the
TunnelMap middle. Causes: (1) the middle was not followed while the player was still >0.6 blocks off its lane
("moving") - 6,456 skipped ticks per 8 runs, a stale lane was chased; now on the middle lane `bend` / `recentre` run
also while moving (an ore lane move over is still not re-decided); (2) ore lanes up to 2 blocks aside → `LANE_ASIDE`
1 ("slightly"). Mean distance 1.30 → 1.11 blocks (several runs < 0.9).

Direction lock (same night, user: "wechselt alle 2 Sekunden die Route"): the game log showed no planner replans - the
switches came from the free steering. Replay (8 runs x 60 s from 710 81 239): bend jumps >30° 167x, way ended 125x,
REVIEW ("another way scores more") 25x, ore-lane flicker. Changes: REVIEW removed (a way is left only where it ends -
wall / guard edge - or by the module's 6-block rule after the ±3 look aside; planned routes were already locked);
`bend` ≤15° per block and only when the middle bends the same way on two blocks in a row; after a way ended, ways
shorter than OPEN_WAY (8) are a last resort (stubs ended again at once); ore lanes need OPEN_WAY free and ore within
LANE_EMPTY (3) - the same bound that sends them back (with 5 vs 3 a lane flipped every half block); an ore lane is held
LANE_HOLD 3 blocks before "the middle has as much ore" takes it back; on the way to a lane the middle moves it again only
when ≥1 block off. Result: way changes 168 → 84, turning ~4,700 → ~4,000°/min. The guard look-ahead 180° turn stays
removed (it caused the circling).

Night 2026-10-03, from the new diagnostic log lines (cache fully known - "0 of 3267 blocks unknown"; ways ended at the
"guard zone edge", turned round, cave mode planned the same tunnel again):
* Planner vs. steering guard zone: the planner allowed nodes outside the circles that were closer to a guard than the
  player's start; the steering compares with the player's current position → routes round outside were refused half
  way. `GuardArea.penalties` now allows outside nodes only within 3 blocks of the player (the step back in).
* 6-block rule on planned routes too: 6 blocks on stone and no ore 3 blocks aside → the route is dropped and a new one
  planned away from that direction (`plan_barren`).
* After a failed route (blocked / unguarded / only stone) no new tunnel plan for 15 s (`FAILED_PLAN_PAUSE_MS`).
* Stuck recovery: 0.5 s back, the direction blocked; 3 times within 20 s → a planned way elsewhere.
* Session HUD: "Bot State" (MINING / SORTING LOOT / SELLING / ESCAPING / RECOVERING / ...) and inventory fill %.

## Update 2026-10-04: excursions out of the guarded zone

User rules: with another player near (16 blocks per side, "alone" only after 5 s without one) the macro may walk
**2-4 blocks** (setting `outside_near`, default 3) without the guard status, alone **8** (`outside_alone`, default 8,
0-10) - to reach more ways and mine the ore just outside. Then back into the zone, **not the way it came but the way
with the most ore**. Directions are still decided by the ore in an area; routes are at least 32 blocks long; middle
of the way with lanes left / right for ore; never leave a route while ore is ahead (only the guard rule ends it).

- **Free steering** (`OreMacroModule.countOutside`): blocks walked in a row without guard status are counted; while
  fewer than the budget `GuardArea.roam` lets the steering go on up to `outsideReach()` (budget + 2) beyond the zone,
  its rays too. Without excursions (budget 0) the old edge band (13 x 9 blocks marked outside on leaving) is used;
  with excursions only the blocks really walked untaxed are marked.
- **Planner** (`GuardArea.penalties`): unguarded nodes within `outsideReach()` of guarded ground cost +0.5 (the ore
  decides); a way may have at most `maxOutsideRun()` (2 x budget) unguarded blocks in a row and must end guarded
  (`longestOutside`, checked per candidate in `OrePlanner.plan` / `tunnel`).
- **Way back in** (`OreMacroModule.backIn`, `OrePlanner.backIn`): when the budget is used up it stands (at most
  1.5 s) and plans: every guarded floor node 2 blocks inside (`GuardArea.deepNode`) within 64 blocks is an entry,
  rated ore / way x straight on first (a way back the way it came scores ~1/17); the way may run budget + 3 blocks
  outside; nothing found → any guarded node, 2 x budget + 3. From the entry it goes on like any route (legs to
  32+ blocks). Walked via the route corridor (route line ± 5 blocks = the lanes). Nothing found → straight back to the
  zone as before.
- **Safety net**: more than budget + 2 x budget + 5 blocks without guard status (the zone is only a model) → the route
  is dropped, straight back.
- **Guard model** in tax mode: a guard's zone is a 3D sphere (radius less a block), was a cylinder ±8 high.
- All routes: legs are added (up to 6) until a route is at least `OrePlanner.MIN_ROUTE` (32) blocks long.

Simulation (`ExcursionSim`, diagnostic, not kept in the tests): the classic steering + this loop on the saved gold
cave with the 20 saved guards, 72 runs of 2,400 ticks, guard status = 15-block spheres. Budget 0: 55 ores per run
(stuck at the edge), budget 3: ~600, budget 8: ~890 (about 40 % outside the zone).

## Update 2026-10-05: way back into the zone, outside watchdog, death by chat

Game 2026-10-05 00:14-01:06: the macro left the zone at (643, 78, 195), its way "back in" led 15 more blocks out, it
stood under the guard at (639.5, 93, 191.5) - 13 blocks below the taxed floor, behind 4 blocks of ore - re-picking the
same unreachable goal every tick (63 146 guard plans) for 52 minutes, and a player killed it. Learned from the real data
(the module's own saves, copied to `src/test/resources/realcave/`):

| Game fact | Change |
|---|---|
| The tax zone does not reach far below a guard: column (638, 181) taxed at y 93, untaxed at y 80. Sphere 15 cut to -6 / +8 classifies 84 % of the learned untaxed blocks right (plain sphere 77 %). | `GuardArea.GUARD_BELOW` / `GUARD_ABOVE`, `zoneDist`, `inBand` (circles, strips, excursion reach). |
| No model fits better than ~77 % (guards move, the sidebar lags): the learned tax is the truth. | `within()` / `predictInside()`: ground learned untaxed is never inside, whatever the circles say. |
| A mine never changes shape (ore → stone → ore, no air is made): only air ways lead back in. A 24-step way led from the stuck spot onto taxed ground. | `GuardReturn`: one flood from the player, the cheapest **walkable** safe node (`GuardArea.safeNode`, learned taxed first) wins - not the nearest by air. Arrival is checked in 3D. |
| The old back-in rule only limited the longest unguarded stretch. | `OreMacroModule.backInRule`: guarded ground within the rest of the excursion (`backInLead`) - never deeper out first; on the way in `overrun()` drops the route after the rest + 2. |
| Cosmic respawns at once (no death screen): "× Killer … M4cL4ren …", "WHITE SCROLL PROTECTED", "You kept your pickaxe but lost N energy". | `Chores.died` → `startDeath` (respawn, whitescrolls, /warp back); `giveUpBreak` no longer stops after a death. |
| "You have entered combat": no /spawn for 10 s. | `Chores.combat` → `combatUntilMs`. |

`OutsideWatch` (outside the zone): 3 s without getting a block closer → new way (that goal is not taken again), 10 s →
wider search (160 blocks, 200 000 nodes), 20 s - 5 s with a player within 32 blocks - and no progress for 2 s → /spawn
and the /warp back of the death recovery, 45 s → stop with an alert. Log lines: `outside watchdog: …`, `way back in: …`,
`no way back into the guarded zone: …`, `back in the guarded zone after N s outside`, `guard xp tax N% at …` (the
percent rises towards a guard - data for a later model). Counters: `watchdog_*`, `escapes`, `guard_no_way`.

Tests: `GuardReturnReplayTest`, `BackInReplayTest` (the real mine; the zone test fails with the old model, the back-in
test shows the old rule's 15-block lead), `OutsideWatchTest`, `DeathChatTest`. Not tested in game.

## Update 2026-10-05: stricter outside rules (deaths)

Log: the macro was killed twice by the same player after walking 21 and 48 unguarded blocks. Now: any other player within 48 blocks (`CROWD_PLAYERS` 1) = at most 2 unguarded blocks (`outside_near`); nobody near for 20 s = at most 6 (`outside_solo`, range 0-20, replaces `outside_free` 48 so the saved value does not stick).
