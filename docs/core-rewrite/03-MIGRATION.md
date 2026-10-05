# 03 · Archive and migration record

## Where the old code is

`archive/pre-core-rewrite/` holds the complete pre-rewrite state. Nothing was deleted:

| Path | Content |
|---|---|
| `src/main/java/.../core/**` | the old macro core (MacroManager, Failsafes, Pathfinder, WorldModel, rotation, movement, tracers) |
| `src/main/java/.../mining/**` | the old OreMacro with cluster detector, scorer, planner, heatmap learning, run recorder |
| `src/main/java/.../gui/ThePrisonsConfigScreen.java` | the reworked (uncommitted) config screen |
| `src/main/java/.../gui/ThePrisonsConfigScreen.v1.0.java.txt` | the committed v1.0 config screen |
| `src/main/java/.../mixin/ThePrisonsMinecraftClientMixin.java` | old block-breaking mixin |
| `src/test/java/**` | the 12 old test classes, the baseline benchmark and the cave fixture |
| `docs/XRAY_PATHFINDING.md` | the old design notes |
| `uncommitted-changes-to-tracked-files.patch` | the uncommitted diff of the tracked v1 files as found on 2026-09-29 |

The archive is **not compiled into the mod**. The pure navigation / planning part of it is compiled by the separate
Gradle source set `legacyBenchmark` only to reproduce the "before" numbers: `./gradlew legacyBenchmark`.

## Per-class decisions

| Old | New | Decision |
|---|---|---|
| `core/pathfinding/Pos`, `Cell`, `MoveType`, `NavigationPath`, `PathValidator` | `core/nav/*` | **Migrated** unchanged (package only). |
| `Walkability` | `core/nav/Walkability` | **Migrated + extended:** node penalties (anti-stuck), jumps switchable, edge settle. |
| `PathPlanner` | `core/nav/PathSearch` | **Migrated + extended:** flood cost limit, `forEachReached`. |
| `MiningSpots` | `core/nav/ReachSpots`, `SightLine` | **Rewritten:** exact sight line only (no dig-through); face must be open; never the floor around the spot. |
| — | `core/nav/MiningSafety` | **New:** a floor block is only broken if the hole can be left and the spot stays connected. |
| `VoxelStore`, `SectionSnapshot` | `core/world/SectionStore`, `SectionSnapshot`, `WorldSnapshot`, `OreIndex` | **Rewritten:** client-thread store + immutable per-job snapshots (no concurrent map, no boxing); ore slot lists; incremental 4³ density buckets. |
| `WorldModel`, `BlockGeometry`, `TargetBlocks`, `LiveWorldView` | `core/world/WorldCache`, `BlockClassifier`, `TargetRegistry`, `LiveWorldView` | **Rewritten:** interest-based scanning, ms budget, event-driven invalidation (block-update mixin), eviction; per-owner targets instead of a static global. |
| `NavWorker` ×2 | `core/concurrent/Worker` | **Replaced** by one shared worker with per-owner/key cancellation and client-thread callbacks. |
| `Pathfinder` | `core/movement/Navigator` | **Rewritten:** worker-only searches (the blocking client-thread fallback is gone), node penalties, per-owner instances. |
| `MovementController` | `core/movement/PathFollower` | **Migrated + extended:** staged recovery (back off → side-step), stuck nodes reported for penalties. |
| `SteeringLogic`, `JumpPhysics` | `core/movement/*` | **Migrated** unchanged. |
| `RotationController`, `RotationArbiter` | `core/control/RotationController`, `RotationMode` | **Rewritten:** requests survive until the next apply (act-after-rotate). |
| `HumanRotation` | `core/control/SmoothRotation` | **Replaced:** deterministic speed + acceleration limited easing. Randomised Bézier curves, overshoot and sensitivity-grid snapping were evasion features and are not ported. |
| `MacroManager`, `Macro`, `GotoMacro` | `core/module/*`, `core/ThePrisonsCore` (Goto removed on request) | **Replaced** by the generic module system (every feature is a module; macros are `AutomationModule`s with a control lease). |
| `Failsafes` | `core/safety/SafetyMonitor` + `modules/general/SafetyModule` | **Rewritten:** safety stops only (world change, teleport, damage, low health, inventory, run limit, manual input, death). Staff-check detection (bedrock box, chat mention, knockback probe, forced-rotation check) and fake "reaction delays" are not ported. |
| `PathRenderer` | `core/render/Overlay` + module listener | **Replaced:** modules draw from tick-time data. |
| `mining/OreMacro` | `modules/mining/ore/OreMacroModule` | **Rewritten** on the core. |
| `OreClusterDetector`, `OreCluster`, `OreRegistry`, `ClusterScorer`, `RegionAssessor`, `MiningPlanner` | `OrePlanner`, `ZoneMemory`, `OreMemory` | **Replaced:** zone / region scoring on the worker, local nearest-first phase, one-way-drop awareness, back-off memory. |
| `MiningTargeting`, `ToolSelector` | `ReachSelector`, `core/control/BlockBreaker` | **Rewritten:** deterministic aim point (face centre), raycast verification, shared breaker. |
| `MiningStats`, `RunRecorder` | `core/analytics/StatsService`, `RateMeter` | **Replaced** by the shared analytics service (sessions persisted to `config/theprisons/sessions.json`). |
| `OreHeatmap`, `LearningStore` | — | **Archived, not ported** (not required for the first milestone; can be re-added as a `StatsService` / `WorldCache` consumer). |
| X-ray dig-through mode | — | **Archived, not ported** (not requested; digs to buried ores). |
| `ThePrisonsConfigScreen` | `gui/click/ClickGuiScreen` | **Replaced** by the module GUI. |
| `ThePrisonsConfig.MiningConfig` | Ore Macro / Safety module settings | **Removed** from the v1 config; the settings now live in `config/theprisons/modules.json`. Old values are not migrated (different model, only defaults existed). |

## Legacy (v1) features, still active

The v1 HUD / QoL features were **not migrated** yet (as requested: core + Ore Macro first). They keep their code and
run through the core:

- their ticks and chat lines come from the core `EventBus` (one Fabric registration instead of several) and every
  handler is measured by the profiler (`legacy:tracker`, `legacy:features`, `legacy:chat`, `legacy:hud-render` …);
- they appear in the new GUI as `LegacyModule` adapters (HUD, QoL, General). Their settings are *bound* to the v1
  config fields and keep being stored in `config/theprisons.json`;
- three small performance fixes were applied to them because they caused measurable stalls:
  - `ThePrisonsCache` / `ThePrisonsTracker`: the cooldown cache is serialised on the client thread and written on
    the IO executor; refreshing `lastSeenAtMs` no longer forces a write every 5 s (P1);
  - `ThePrisonsConfigManager.saveAsync()` for GUI clicks and HUD drags (P11);
  - `ThePrisonsFeatureManager`: command patterns are compiled once instead of per command per chat line (P12);
- `/prisons` is now owned by the core (`/prisons` opens the module GUI; the old root registration was removed), the
  config key `I` and Mod Menu open the module GUI, the "Mining" HUD widget shows the core's module rows ("Modules").

### Migrating a legacy feature later

1. Create a `Module` in `modules/<category>/` with its settings (owned, not bound).
2. Move the logic from the static manager into `onEnable()` listeners (`on(CoreEvents.TickEnd.class, …)`,
   `on(CoreEvents.ChatReceived.class, …)`), heavy work into `submit(...)`.
3. Read the v1 values once on first start (or leave the defaults), then delete the `LegacyModules` entry and the
   bound fields.
