# 01 · Analysis of the pre-rewrite code base

Stack: Minecraft **1.21.11**, Fabric Loader 0.17.3, Fabric API 0.141.1, Yarn mappings, Java 21, Loom 1.15.5.
Client-side only. Snapshot analysed: `v1.0` (committed) plus the uncommitted macro work that was on disk on
2026-09-29 (packages `core/`, `mining/`, the reworked config screen and 12 test classes). All of that is archived
unchanged in `archive/pre-core-rewrite/` (see `03-MIGRATION.md`).

## 1. Inventory

| Area | Classes | Lines | Role |
|---|---|---|---|
| Entry | `ThePrisonsClient` | 257 | Registers every subsystem's Fabric events itself, installs the bundled resource pack |
| Legacy features | `ThePrisonsFeatureManager` | 928 | Static god class: keybinds, commands, chat parsing (cooldowns, XP, energy), satchel scan, vitals warnings, item overlays, tooltips, peaceful-mining hit block |
| | `ThePrisonsTracker` | 207 | Pet / trinket cooldown tracking, writes the cooldown cache |
| | `ThePrisonsBanditManager` | 189 | Armor HUD + warnings (the bandit-rush logic itself is empty) |
| | `ThePrisonsCache`, `ThePrisonsConfig(Manager)` | 323 | JSON cooldown cache, one monolithic config POJO |
| | `ThePrisonsHudRenderer`, `ThePrisonsHudLayoutScreen`, `ThePrisonsColors` | 836 | HUD widgets, notifications, drag-and-drop layout |
| | `ThePrisonsConfigScreen` | 1 034 | Config GUI bound directly to config fields |
| | `ThePrisonsUpdateChecker`, `ThePrisonsTrinketsCompat`, mixins | 330 | Modrinth update check, Trinkets reflection, cooldown accessors |
| Macro core (uncommitted) | `core/macro` `MacroManager`, `Macro`, `Failsafes`, `GotoMacro` | 583 | Static manager, one macro at a time, commands, keybinds, failsafes |
| | `core/world` `WorldModel`, `BlockGeometry`, `TargetBlocks`, `LiveWorldView` | 474 | Section scanner, block → cell encoding, global static target set |
| | `core/pathfinding` (14 classes) | 1 625 | Voxel store, walkability, A* / Dijkstra, validator, mining spots, worker |
| | `core/movement`, `core/rotation`, `core/render` | 1 060 | Pure-pursuit steering, jump physics, Bézier "human" rotation, tracers |
| Ore macro (uncommitted) | `mining/*` (15 classes) | 2 540 | `OreMacro` state machine, vein detector, cluster scorer, region assessor, heatmap learning, run recorder, stats, tool selection |

## 2. Dependency graph (simplified)

```
ThePrisonsClient ──registers──► FeatureManager, Tracker, BanditManager, UpdateChecker, MacroManager, MiningModule
FeatureManager ──► MacroManager.KEY_CATEGORY, ConfigScreen, HudRenderer
HudRenderer ──► FeatureManager, BanditManager, Cache, MacroManager.hudEntries() ──► OreMacro.hudEntries()
MacroManager ──► WorldModel, Pathfinder, RotationController, Failsafes ──► ThePrisonsConfig.MiningConfig   (core → module config!)
Pathfinder ──► ThePrisonsConfig.mining.humanization                                                         (core → module config!)
OreMacro ──► own NavWorker, OreClusterDetector, LearningStore, RunRecorder, MiningStats, TargetBlocks (static global)
ToolSelector ──► MacroManager.failsafes()                                                                   (module → core internals)
```

Layering is inverted in several places: the "core" reads the mining module's config, the module reaches into
core internals, and the HUD pulls data straight out of the running macro on every frame.

## 3. Concrete problems

### 3.1 Performance and threading

| # | Where | Problem | Impact |
|---|---|---|---|
| P1 | `ThePrisonsTracker.syncStacks` → `ThePrisonsCache.save()` | `lastSeenAtMs` is refreshed every 5 s and marks the cache dirty, which triggers a **synchronous JSON write on the client thread** | A disk write every 5 s while any pet / trinket is in the inventory; frame-time spikes on slow disks |
| P2 | `ThePrisonsTracker.tick` | Every tick: all 36 inventory stacks + trinkets → `getName().getString()`, regex normalisation, string concatenation, `HashSet` | Constant per-tick garbage even with nothing to track |
| P3 | `Pathfinder.searchLive` | When the worker result is unusable or the start is not scanned yet, an **A\* with 8 000 nodes runs on the client thread** | Up to several ms of blocking work in a tick; exactly what must never happen |
| P4 | `ThePrisonsHudRenderer.render` → `MacroManager.hudEntries()` → `OreMacro.hudEntries()` | Rebuilt **every frame**: `OreType.parse`, registry lookups, `String.format`, new lists | Per-frame allocations at 144+ fps for values that change a few times per second |
| P5 | `OreMacro` + `Pathfinder` | Two separate `NavWorker` single-thread executors | Two planning threads competing, no shared cancellation |
| P6 | Ore data | The ore index exists 3–4× (`VoxelStore.oreIndex`, `OreClusterDetector.ores`, the `visibleOres` copy per re-plan, `plannedVisible`, the density buckets rebuilt per re-plan) | Redundant memory, O(all ores) copies on the client thread for every re-plan |
| P7 | `VoxelStore` / `Walkability` | `ConcurrentHashMap<Long, SectionSnapshot>`: every block lookup boxes a `Long` and hashes; A* reads ~40 cells per expanded node | Allocation-heavy hot loop on the worker; the map also changes while a job reads it (no consistent world version per job) |
| P8 | `MiningPlanner` | Best standing spot searched for **every** candidate ore (~1 000 flood lookups + sight lines each) even for ores in areas that can never win | Worker re-plan 22.7 ms on the cave fixture, grows linearly with ores |
| P9 | Analytics | `MiningStats`, `RunRecorder`, `OreHeatmap`/`LearningStore`, `FeatureManager.SessionStats` each count on their own | Four ad-hoc stats systems, different time bases, duplicate event listeners |
| P10 | Events | `ClientReceiveMessageEvents.CHAT` registered 2×, `ClientPlayerBlockBreakEvents.AFTER` 2×, `/prisons` root 3× | Every subsystem re-dispatches the same event, no ordering, no error isolation |
| P11 | Config | Every GUI click / HUD drag calls `CONFIG.save()` synchronously | Disk write on the render thread per interaction |
| P12 | `FeatureManager.parseCooldown` | Builds and compiles a regex per known command for every chat line | ~15 `Pattern.compile` per chat message |

### 3.2 Architecture

- **No module abstraction.** Features are booleans inside a static manager; there is no lifecycle, so disabling a
  feature only skips work inside a tick handler that still runs.
- **No shared services.** Each subsystem registers its own events, keybinds, commands, stats and file IO.
- **Global mutable state.** `TargetBlocks` is a static set: two modules wanting different blocks would overwrite
  each other.
- **Core depends on a module.** Failsafes and the pathfinder read `MiningConfig`.
- **No error isolation.** An exception inside the macro tick propagates into Fabric's tick loop.
- **GUI coupled to the config POJO.** Adding a setting means editing the config class, the screen and `normalize()`.

### 3.3 Behaviour that is out of scope and was not carried over

The uncommitted macro contained features whose only purpose is to evade server-side detection or staff checks:
randomised "human" Bézier rotations with overshoot, snapping rotations to the mouse-sensitivity grid, randomised
view drift while walking, a fake "human reaction delay" before failsafes, and failsafes that detect staff checks
(bedrock / barrier box, name mentioned in chat, knockback probes, server-forced rotation). The rewrite
requirement is explicitly *not* to target detection evasion, so these are archived and not ported. The new core
keeps the **safety** stops (world change / teleport, damage, low health, full inventory, stuck, runtime limit,
exceptions, manual override). The x-ray digging mode and the persistent ore heatmap are archived as well. They
were not requested for the first milestone and can be re-added later as consumers of the new services.

## 4. What was worth keeping

The pure navigation code was well done and has unit tests: the packed `Pos` layout, the 16-bit `Cell` encoding
of real collision shapes, `Walkability` (player box, step / jump / drop, diagonal corner checks), A* with
partial paths, the Dijkstra flood, `PathValidator`, the Amanatides–Woo sight line, `JumpPhysics` and the
pure-pursuit `SteeringLogic` with its `PlayerSim` tests. These are **migrated** into the new core (reviewed and
adapted, not copied blindly). See `03-MIGRATION.md` for the per-class decisions.

## 5. Research: reference projects

| Project | Source available | Useful ideas | Not applicable |
|---|---|---|---|
| **Taunahi** (Hypixel SkyBlock macro) | No. The public `taunahi-org/taunahi-macro` repo is a 724-line Forge 1.8.9 fragment (ShadyAddons base); the real client is closed source | Feature split into independent macros that share one failsafe layer; one active macro at a time | Its core selling point is anti-staff evasion, which is out of scope here; Forge 1.8.9 APIs |
| **Nebula** client | No (the NebulaCli repo returns 404) | UI concept: category sidebar, module list with toggles, per-module settings panel, search, keybinds per module | Implementation details unknown; UI rebuilt from scratch |
| **BetterCosmic** (Fabric 1.21.11, same stack) | Yes, GPL | Per-area config files that persist **only overridden values** (new defaults apply automatically), corrupt configs are backed up instead of crashing, option model (toggle / slider / dropdown / keybind / colour / text), `ServerContext` gating of Cosmic-only features | Has no automation at all; its single-screen config panel is less modular than requested |
| **Baritone** (reference for pathfinding) | Yes, LGPL | A* over a cost graph of movements, partial paths towards far goals ("segmenting"), path re-validation while walking, cost-based avoidance | Full Baritone is far too large and would conflict with our input handling; we keep a lean A* with the same ideas |
| Previous `ThePrisons` macro work | Yes (this repo) | See §4 | See §3.3 |

## 6. Baseline measurements (before)

Measured with `LegacyBaselineBenchmarkTest` (`./gradlew legacyBenchmark`) on the shared **ore-cave fixture**
(112 × 48 × 112, worm-carved chambers and tunnels with walkable floors, 2 948 ores in 900 veins, seed 42), JDK 21,
median of 7 runs after warm-up:

| Job | Thread | Before |
|---|---|---|
| A* to the farthest reachable cell (path cost 277) | worker | 11.73 ms |
| Dijkstra flood, radius 48 | worker | 1.79 ms |
| Re-plan, client-thread part (visible-ore copy, region, density) | **client** | 1.30 ms |
| Re-plan, worker part (flood + best spot for 914 exposed ores) | worker | 23.85 ms |
| Cluster scoring | **client** | 0.89 ms |
| Section ingest (store diff + cluster detector) | client | 0.007 ms / section |
| In-reach scan (11³ cube, ore lookups only) | client | 0.013 ms |

Not measurable offline, only by inspection: the synchronous cache write every 5 s (P1), per-frame HUD
allocations (P4) and the blocking live A* (P3). The new core has a built-in profiler (`/prisons perf`) so these
paths can now be measured in game. See `05-BENCHMARKS.md` for the before / after comparison.
