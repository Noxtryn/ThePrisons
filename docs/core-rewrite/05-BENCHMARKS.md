# 05 · Benchmarks: before and after

All numbers were measured on the same machine (JDK 21), median of 7 runs after 3 warm-ups, on the **same terrain**:
the ore-cave fixture (`CaveFixture` / archived `LegacyCaveFixture`, seed 42: 112 × 48 × 112, worm-carved chambers
and tunnels, 2 948 redstone ores in veins). Reproduce:

```bash
./gradlew legacyBenchmark                                   # before  → build/benchmarks/legacy-baseline.txt
./gradlew test --tests '*CoreBenchmarkTest'                 # after   → build/benchmarks/core-after.txt
./gradlew test --tests '*OreMacroSimulationTest'            # decisions → build/benchmarks/ore-simulation.txt
./gradlew test --tests '*OrePlannerTuning' -Dtuning=true    # parameter sweep (several minutes)
```

## 1. Offline micro benchmarks

| Job | Thread | Before | After | Change |
|---|---|---|---|---|
| A* to the farthest reachable cell (path cost 277) | worker | 11.73 ms | 4.44 ms | **2.6× faster** (primitive snapshot map + section cache instead of a boxed `ConcurrentHashMap`) |
| Dijkstra flood, radius 48 | worker | 1.79 ms | 0.92 ms | 1.9× faster |
| Re-plan, **client-thread** part | client | 1.30 ms + 0.89 ms scoring = **2.19 ms** | **0.005 ms** (snapshot of section references) | the whole analysis moved to the worker |
| Re-plan, worker part | worker | 23.85 ms | 4.17 ms (local phase) / 3.59 ms (zone phase) | 5.7× faster (two-phase: coarse buckets, exact spots only where needed) |
| Section ingest (store + ore index) | client | 0.007 ms | 0.003 ms | ore slot lists instead of 4 096-cell diffs |
| In-reach decision | client | 0.013 ms (ore lookups only) | 0.43 ms (full ranking: sight lines, safety, connectivity) | not comparable; runs only when a target is needed, not every tick |

## 2. Per-tick / per-frame costs removed (by construction)

| Issue (see `01-ANALYSIS.md`) | Before | After |
|---|---|---|
| P1 cooldown cache | synchronous JSON write on the client thread every 5 s while a pet / trinket is carried | written only on real changes, serialised in µs, written on the IO executor |
| P3 live fallback search | up to 8 000-node A* **on the client thread** | none; searches exist only on the worker |
| P4 HUD | module rows rebuilt every frame (registry lookups, formatting) | rows collected every 5 ticks, the renderer draws a prepared list |
| P5 threads | 2 planning threads | 1 shared worker |
| P6 ore data | 3–4 copies of the ore index, O(all ores) copies per re-plan | one incremental index + immutable snapshots |
| P10 events | CHAT ×2, block break ×2, `/prisons` ×3 | one registration each, dispatched by the bus |
| P11 config saves | synchronous write per GUI click / HUD drag | debounced, off-thread |
| P12 chat parsing | ~15 `Pattern.compile` per chat line | compiled once |

In game, `/prisons perf` (or the Performance Monitor module) shows avg / worst case per section, including every
legacy handler (`legacy:tracker`, `legacy:features`, `legacy:chat`, `legacy:hud-render`), every module and every
worker job. The in-game test (§4) logs these numbers.

## 3. Decision quality (simulation)

`OreMacroSimulationTest` runs the complete Ore Macro decision loop (planner → walk → mine everything the selector
offers, world updated after every block → re-plan) and a greedy "nearest visible ore" baseline on the same cave.
Travel time from path cost at 4.3 blocks/s, turning time from the angle at 20°/tick.

| seed 42 | planner (defaults) | greedy nearest |
|---|---|---|
| ores mined until nothing reachable is left | **1 440** | 1 304 |
| time for the first 1 304 ores | **438 s** | 502 s |
| stops / ores per stop | 257 / 5.6 | 568 / 2.3 |
| long reversals (> 150° turn, > 8 blocks) | 9 | 6 |
| stops that yielded nothing | 0 | 3 |
| exposed, reachable ores left at the end | 0 | 1 |
| planning time per decision (worker) | 3.5 ms avg | 12.8 ms avg |

Parameter sweep over three caves (seeds 42, 7, 1234), time until 300 / 600 ores:

| variant | 300 ores | 600 ores | ores mined (avg) | walked / ore | worst run |
|---|---|---|---|---|---|
| greedy nearest | 85–99 s | — | 1 126 | ~1.0 | — |
| zones only (first version) | 126 s | 240 s | 1 506 | 6.1 | 4 577 s |
| **local 10 + zones (default)** | **76 s** | **158 s** | **1 684** | 2.5 | 1 774 s |
| local 16 + zones | 76–79 s | 157–169 s | 1 408–1 757 | 1.9–2.1 | 1 601 s |

How the design got there (each step was a measured failure, see `06-ORE-MACRO.md`): pure zone scoring walked twice
as far per ore as greedy; without the two-way rule the macro dropped into lower parts early and lost a third of
the cave; without the floor / connectivity rules it dug itself into pits; without exhaustion memory it explored
dead ends endlessly (worst case 15 600 s).

## 4. In-game integration test

`./gradlew runClientGameTest` starts a real client and integrated server, builds an ore cave, runs the Ore Macro and
checks the result, the profiler, the GUI and the safety stop. See the result section below (filled from the latest
local run).
