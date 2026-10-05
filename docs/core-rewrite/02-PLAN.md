# 02 · Target architecture and implementation plan

## 1. Goals

1. One **core** that owns every shared service. Modules declare what they need and never rebuild a service.
2. **Nothing blocking on the client or render thread.** Searches and planning run on one shared worker over
   immutable world snapshots. File IO runs on the IO executor.
3. **Bounded, visible cost.** Every periodic job has a fixed interval or budget and is measured by the profiler.
4. **Clean lifecycle.** Enabling a module registers its listeners, tasks and jobs; disabling removes all of them
   and releases every input it held.
5. The **Ore Macro** is the first module built on the core. Legacy HUD / QoL features keep working through thin
   adapters until they are migrated one by one.

## 2. Layers

```
┌──────────────────────────────────────────────────────────────────────────────────────┐
│ gui/click        ClickGuiScreen (categories, search, module list, settings panel)    │
├──────────────────────────────────────────────────────────────────────────────────────┤
│ modules/         mining/ore (OreMacroModule …) · general (Safety, Performance, Goto) │
│                  legacy/* adapters (HUD, QoL, General) bound to the v1 config        │
├──────────────────────────────────────────────────────────────────────────────────────┤
│ core/ services   world (WorldCache, OreIndex, TargetRegistry) · nav (A*, flood,      │
│                  NavigationService, PathFollower) · control (InputController,        │
│                  RotationController, BlockBreaker, ControlLease) · safety · analytics│
│                  · hud · render (overlays) · command                                  │
├──────────────────────────────────────────────────────────────────────────────────────┤
│ core/ kernel     EventBus · TickScheduler · Worker (+ main-thread queue) · Profiler  │
│                  · ModuleManager · Settings · ConfigStore                            │
├──────────────────────────────────────────────────────────────────────────────────────┤
│ Fabric bridge    ThePrisonsCore.install(): the ONLY place that registers Fabric      │
│                  events, mixin hooks forward into the EventBus                       │
└──────────────────────────────────────────────────────────────────────────────────────┘
```

Rule: arrows only point down. The kernel has no Minecraft dependency and is unit tested in isolation. Pure
algorithms (navigation, planning, steering, rotation easing) are Minecraft-free as well. Only thin adapters
touch `MinecraftClient`.

## 3. Kernel contracts

| Service | Contract |
|---|---|
| `EventBus` | Typed, synchronous, client thread. `subscribe(type, owner, priority, handler)`; `unsubscribeAll(owner)`. Listener arrays are copy-on-write, so posting allocates nothing. A throwing listener is isolated and reported to the owner's error handler (the module manager disables that module). |
| `TickScheduler` | `every(owner, name, intervalTicks, task)`. Tasks with the same interval are spread across phase offsets so they never run in the same tick. Each task is profiled. |
| `Worker` | One daemon thread. `submit(owner, key, job, onResult)`: latest submission per key wins, the previous one is cancelled cooperatively. Results are handed to the client thread through a queue drained at tick start, and are dropped when the job was cancelled or the owner was disabled. |
| `Profiler` | Named sections with EMA / max over 5 s / calls. `/prisons perf` and the Performance module show them. |
| `ModuleManager` | Registry + lifecycle. `enable` / `disable` / `toggle`, error isolation, keybind polling, status for the GUI. |
| `Setting<T>` | Typed settings (bool, int, double, enum, choice, multi-choice, keybind, text, colour, action) with ranges, groups, visibility conditions and change listeners. Storage is either owned (persisted by the core) or **bound** to an external getter / setter (legacy config). |
| `ConfigStore` | `config/theprisons/modules.json`. Persists **only values that differ from defaults**. Unknown or broken entries fall back to defaults. A corrupt file is backed up, never crashes. Saves are debounced and serialised on the client thread (µs), then written atomically on the IO executor. |

## 4. Services

### 4.1 World cache (`core/world`)
- **Demand-driven.** Modules acquire a `WorldInterest(radius, vertical)`. With no interest, nothing is scanned.
- Loaded chunk sections inside the union of interests are copied into immutable `SectionSnapshot`s (16-bit
  collision cells + ore keys + an ore slot list). Budget: max. 6 sections **and** max. 1.0 ms per tick,
  closest first.
- **Event-driven invalidation** instead of blind rescans. A mixin on `ClientWorld.handleBlockUpdate` marks the
  section dirty, chunk (re)loads mark the chunk dirty, and our own block breaks are patched immediately
  (copy-on-write). A slow safety rescan (every 10 s) of the sections around the player catches anything
  missed. Unloaded chunks and sections outside `radius + 32` are evicted.
- `OreIndex` is the **single** ore index. It is maintained incrementally from section diffs and holds 4³ spatial
  buckets for density queries.
- `WorldSnapshot` is an immutable, consistent view (section references within a radius plus a version) handed
  to worker jobs. Lookups go through a thread-confined last-section cache, with no boxing and no concurrent map.
- `TargetRegistry` is the reference-counted union of the blocks that modules want indexed. It replaces the old
  static global.

### 4.2 Navigation (`core/nav`, `core/movement`)
- `Walkability`, `AStar` (partial paths), `Flood` (Dijkstra), `PathValidator`, `SightLine`, migrated from the old
  core and adapted to `WorldSnapshot`.
- `NavigationService.request(owner, goals, callback)` runs only on the worker. There is **no** client-thread
  fallback search.
- `PathFollower` handles pure-pursuit steering, timed jumps, sprinting, live validation of the next 3 edges,
  and a staged anti-stuck sequence: back off + jump, then strafe (alternating side), then penalise the blocked
  nodes (TTL) and re-plan, and give up after N attempts.

**Algorithm choice.** An ore cave is an irregular 3D voxel space: chambers of different heights, 1–2 wide
tunnels, steps, ledges and drops. The mine is also re-shaped constantly by our own mining and by resets. So:

| Option | Verdict |
|---|---|
| **A\* on the implicit voxel walk graph** (8 directions, step / jump / drop edges, real collision shapes) | ✔ Exact for this terrain, needs no pre-processing (the terrain changes), and the octile/height heuristic is admissible. With a consistent snapshot, a 200-block path takes ~5 ms. |
| **Bounded Dijkstra flood** from the player | ✔ One run gives the true travel cost to *every* reachable node. Zone / ore scoring needs costs to hundreds of targets, which would otherwise take hundreds of A\* runs. |
| Navmesh / waypoint graph | ✘ Must be rebuilt whenever blocks change (every mined ore); caves are too irregular for cheap convex decomposition. |
| Jump Point Search | ✘ Assumes uniform-cost grids; jump / drop / tight-spot costs and vertical moves break its pruning rules. |
| D\* Lite / LPA\* | ✘ Pays off when many edge costs change along a long path. Our changes mostly open space near the player, and re-planning from scratch on a snapshot costs a few ms. The extra complexity is not justified. |
| HPA\* | ✘ The search radius is ≤ 64 blocks; a flood over that area costs ~3–4 ms, so no hierarchy is needed. |

Path smoothness comes from executing the grid path with pure pursuit (look-ahead along the polyline). This
avoids a separate string-pulling pass that would have to re-validate collisions.

### 4.3 Control (`core/control`)
- `ControlLease`: at most one module drives the player. It is acquired on enable and released on disable, so
  keys are always released.
- `InputController`: the only writer of movement keys. Held keys are re-applied each tick and paused while a
  screen is open.
- `RotationController`: per-tick priority requests (mining > jump > recovery > obstacle > turn > navigation).
  `SmoothRotation` is deterministic easing with a speed limit and an acceleration limit, so there is no
  overshoot, no randomisation and no snapping.
- `BlockBreaker`: tool selection, crosshair ray check, `updateBlockBreakingProgress`, and break confirmation /
  timeout. Shared by all future mining modules (Meteor Mining).

### 4.4 Safety (`core/safety`)
Runs only while a lease is held. Rules: world change / teleport, damage, low health, full inventory, runtime
limit, manual override (the player presses movement keys), and repeated exceptions. A violation disables the
leasing module with a reason (notification, chat line, optional sound). The settings live in the
`General › Safety` module.

### 4.5 Analytics (`core/analytics`)
`StatsService`: namespaced counters, sliding 60 s rate meters (per-hour rates), time-in-state accounting and
session summaries (last 20 kept, persisted asynchronously). It replaces `MiningStats`, `RunRecorder` and the
ad-hoc counters.

### 4.6 HUD, overlays, commands
- `HudService` collects module HUD lines every 5 ticks into an immutable list. The HUD renderer only draws it,
  with no per-frame allocation from modules.
- `OverlayService` draws world overlays (path, targets) from data captured at tick time.
- `CommandService` registers a single `/prisons` (alias `/theprisons`) tree: `gui`, `toggle <module>`, `stop`,
  `perf [reset]`, `stats`, `goto <x y z>`. Modules can add sub-commands.

## 5. Ore Macro (`modules/mining/ore`)

> **Implementation note:** this section is the initial plan. The simulation (`05-BENCHMARKS.md`) led to important
> changes: a nearest-first *local* phase before zone scoring, two-way-first planning with one-way regions valued by
> their downstream ores, floor / connectivity safety rules and bounded exploration. The final algorithm is described in
> `06-ORE-MACRO.md`.

```
            ┌───────────── enable ─────────────┐
            ▼                                  │
        STARTING ──coverage ≥ 90 % or 3 s──► PLANNING ◄─────────── zone depleted / better zone / fail
            ▲                                  │ (worker: ZonePlan)
            │                                  ▼
        (teleport/          ┌──────────── TRAVELING ──arrived──► MINING ──stop empty──► NEXT_STOP
         world change        │   (opportunistic mining in reach)     ▲   (worker: StopPlan, prefetched)
         → stop)             │                                       └──────────────┘
                             └─ no ores: EXPLORING (frontier) ─ nothing reachable: WAITING (re-plan every 3 s)
```

**Two-phase planning on the worker** (fixes P8):
1. *Zone plan* (radius 48, every re-plan and every 10 s in the background):
   - A Dijkstra flood from the player, giving true costs.
   - Coarse reachability: min flood cost per 2³ "eye" bucket, then per exposed ore the cheapest bucket within
     reach (≈ 1 ms for 1 000 ores).
   - Ores are grouped into 8³ zones. Score = `value / (travel_s + n · t_ore + overhead)` × concentration
     factor − penalties (recently depleted, failed ores). This rewards dense zones, amortises travel and
     naturally prefers the closer of two equal zones.
   - **Hysteresis**: keep the current zone unless another one scores ≥ `switchThreshold` (default +30 %)
     better or the current one is empty.
   - Exact mining spots (sight line, standing node) are computed only for the top zones.
2. *Stop plan* (radius 20): the next standing spot inside / near the chosen zone that covers the most ores per
   second, computed from the current stop **while still mining there** (the ores being mined are excluded), so
   the path is ready the moment the stop is empty.

**Target choice in reach (client thread, only when needed):** candidates are exposed target ores in the reach
cube. Cost = rotation angle + distance² − vein-continuity bonus. At most 6 real raycasts per tick. After each
break the next target is chosen **in the same tick**. An ore that lands in the crosshair on the way is taken
too ("free" ore). While travelling, ores in reach within ±100° of the walking direction are mined without
stopping.

**Memory:** `OreMemory` keeps per-ore failures (unreachable, not hittable, timeout) with exponential back-off
TTLs. `ZoneMemory` keeps per-zone visits and yield, which lowers the score of zones just emptied and allows
coming back to zones after a reset (the "way back"). Both are pruned every 5 s.

**Continuous operation:** no artificial delays. A block breaks as soon as the crosshair is on it; transitions
between stops use prefetched plans.

**Stop / restart:** stop on disable, safety violation or a lost world. When no ores are reachable, the macro
explores, then waits and re-plans. A failed navigation is retried with the blocked nodes penalised; after 6
consecutive failures the macro waits instead of looping.

## 6. GUI (`gui/click`)
Nebula-style layout: left sidebar (search box, categories with module counts), middle module list (cards with
toggle, status dot and status text, grouped by sub-category), right settings panel (header with enable toggle
and keybind, grouped settings). Widgets: switch, slider, dropdown, multi-select chips, keybind, text, colour
palette, action button. Footer: save state plus **Save**, **Reload**, **Reset module**. Categories: Mining,
Meteor Mining, Bandit, PvP, Combat, QoL, HUD, General. Empty categories show "migration pending".

## 7. Order of work

1. Analysis (`01-ANALYSIS.md`) ✔ · 2. Baseline benchmarks ✔ · 3. This plan ✔
4. Archive the old code (`archive/pre-core-rewrite`), cut legacy references to it.
5. Kernel + tests → 6. world / nav / control / safety / analytics services + tests
7. Ore Macro + planner / simulation tests → 8. Click GUI + legacy adapters
9. Unit tests, benchmarks, in-game client gametest → 10. Docs (`03`–`06`).
