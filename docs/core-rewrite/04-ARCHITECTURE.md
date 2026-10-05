# 04 · Architecture of the new core (developer guide)

## Package map

```
io.theprisons
├── core/                         ← shared services, no feature logic
│   ├── ThePrisonsCore            owns every service, the ONLY class registering Fabric callbacks
│   ├── Phases                    tick phase priorities (see below)
│   ├── event/                    EventBus, CoreEvents (TickStart/End, WorldChanged, Chunk*, BlockChanged,
│   │                             PlayerBrokeBlock, ChatReceived, WorldRender)
│   ├── tick/                     TickScheduler (periodic tasks, phase spreading)
│   ├── concurrent/               Worker (one background thread, latest-wins jobs, client-thread callbacks)
│   ├── profiling/                Profiler (avg / worst case per section)
│   ├── module/                   Module, AutomationModule, ModuleManager, ModuleHost, Category
│   ├── setting/                  Setting + Settings (bool, int, double, enum, multi, keybind, text, colour, action)
│   ├── config/                   ConfigStore (modules.json, overrides only, debounced async atomic writes)
│   ├── world/                    WorldCache (scanner), SectionStore, SectionSnapshot, WorldSnapshot, OreIndex,
│   │                             BlockClassifier, TargetRegistry, BlockKeys, LiveWorldView
│   ├── nav/                      Pos, Cell, VoxelView, Walkability, PathSearch (A*, flood), PathValidator,
│   │                             ReachSpots, SightLine, MiningSafety
│   ├── movement/                 Navigator, PathFollower, SteeringLogic, JumpPhysics
│   ├── control/                  ControlService (lease), InputController, RotationController, HumanRotation,
│   │                             RotationMode, RotationMath, BlockBreaker
│   ├── safety/                   SafetyMonitor
│   ├── analytics/                StatsService, RateMeter
│   ├── hud/                      HudService, HudLine
│   ├── render/                   Overlay
│   └── command/                  CommandService (/prisons tree)
├── modules/                      ← features, each one Module
│   ├── ModuleRegistry            creates modules with exactly the services they need
│   ├── mining/ore/               OreMacroModule, OrePlanner, ReachSelector, OreMemory, ZoneMemory, OreCatalog
│   ├── general/                  SafetyModule, PerformanceModule, ClickGuiModule
│   └── legacy/                   LegacyModule adapters for the v1 features
├── gui/click/ClickGuiScreen      module GUI
└── (v1 packages: feature, state, bandit, cache, config, ui, update, gui/ThePrisonsHudLayoutScreen …)
```

Minecraft-free (unit-tested without a game): `event`, `tick`, `concurrent`, `profiling`, `module`, `setting`,
`config`, `nav`, `world/SectionStore|SectionSnapshot|WorldSnapshot|OreIndex`, `movement/SteeringLogic|JumpPhysics`,
`control/HumanRotation|RotationMath`, `analytics`, `modules/mining/ore/OrePlanner|ReachSelector|OreMemory|ZoneMemory`.

## One tick

```
START_CLIENT_TICK   Worker.drain()  → callbacks of finished jobs (plans, paths) run here, on the client thread
                    TickStart event
  … vanilla input handling, world / entity tick (the player moves with the keys pressed last tick) …
END_CLIENT_TICK     WorldChanged (if the world instance changed), then TickEnd with these phases:
   1000 WORLD         WorldCache: dirty sections first, then the scan plan (≤ 6 sections, ≤ 1 ms)
    950 KEYBINDS      module keybinds (only when no screen is open)
    900 SAFETY        SafetyMonitor for the lease holder (may stop it before it acts)
    800 SCHEDULER     periodic tasks
      0 DECIDE        modules: choose targets, request view / keys, advance state machines
  -1000 CONTROL       ControlService: set the winning view request as target of HumanRotation (drawn every frame from the Mouse mixin) and the keys
  -1100 ACT           modules: act on the view that was just applied (break the block in the crosshair)
  -1900 legacy        v1 tick handlers (profiled)
  -2000 HOUSEKEEPING  HudService refresh (every 5 ticks), ConfigStore debounce
```

## Threading rules

1. **Client thread:** everything that touches Minecraft objects, all module logic, the `SectionStore` and the
   `OreIndex`.
2. **Worker thread:** only pure functions of immutable inputs (`WorldSnapshot`, copied sets / maps). Results come
   back through `Worker.drain()` on the client thread. A job never touches a module field.
3. **IO executor** (`Util.getIoWorkerExecutor()`): file writes (module config, v1 config, cooldown cache, session
   history). The JSON is built on the client thread (microseconds), only the write is off-thread.
4. **Render thread** (= client thread in 1.21): HUD / overlays only read data captured at tick time
   (`HudService.blocks()`, a module's volatile render record).

## Writing a module

```java
public final class ExampleModule extends Module {
    private final WorldCache world;                       // services are constructor-injected
    private final Settings.IntSetting radius;

    public ExampleModule(WorldCache world) {
        super("example", "Example", Category.QOL, "Utilities", "What it does.", Settings.KeybindSetting.NONE);
        this.world = world;
        radius = integer("radius", "Radius", 16, 4, 64, 4).suffix(" blocks").group("General");
    }

    @Override
    protected void onEnable() {
        on(CoreEvents.TickEnd.class, Phases.DECIDE, event -> { /* per tick */ });
        every(20, "refresh", () -> { /* once per second */ });
        // submit("key", cancel -> heavyPureWork(snapshot), result -> useOnClientThread(result));
    }

    @Override
    protected void onDisable() {
        // listeners, tasks and pending job callbacks are removed automatically
    }
}
```

Register it in `ModuleRegistry.registerAll`. It then has a GUI entry, persisted settings, a keybind, a
`/prisons toggle example` command, a profiler section (`module:example`) and error isolation.

Macros extend `AutomationModule`: enabling takes the **control lease** (one driver at a time) and a
`WorldCache` interest; disabling (manually, by a failsafe or by a crash) always releases keys and breaking.

## Data flow of the world cache

```
chunk section ──extract (client, budgeted)──► SectionSnapshot (immutable: cells, ore keys, ore slots)
      ▲                                              │
block update mixin / chunk load ─► dirty set         ▼
our own block break ─► patch (copy-on-write)   SectionStore ──diff──► OreIndex (pos → key, 4³ buckets)
                                                     │
                                                     └─snapshot(radius)─► WorldSnapshot ─► worker jobs
```

## Algorithms

- **Pathfinding:** A* on the implicit voxel walk graph (8 directions, step / jump / drop, real collision shapes,
  0.6 × 1.8 player box), partial paths for far goals, node penalties from the anti-stuck logic. Dijkstra flood for
  "cost to everything". Reasoning: `02-PLAN.md` §4.2.
- **Following:** pure pursuit on the path polyline, keys relative to the current yaw (strafing while aiming
  elsewhere), jump timing from the vanilla jump arc, live validation of the next 3 edges every 5 ticks, staged
  recovery (back off, side-step), then re-plan with penalised nodes; gives up after 3 recoveries / 5 re-plans.
- **Rotation:** speed- and acceleration-limited easing towards the target, proportional braking, no overshoot, no
  randomness. The mining request outranks navigation.
- **Ore Macro planning:** see `06-ORE-MACRO.md`.

## Cleanup 2026-10-03

* Dead navigation stack moved to `archive/unused-2026-10-03/` (not compiled): `Navigator`, `PathFollower`,
  `SteeringLogic`, `JumpPhysics`, `PathValidator`, `LiveWorldView`, `ReachSpots`, `SightLine`, `MiningSafety` (+ their
  tests `SteeringLogicTest`, `PlayerSim`). The ore macro walks with `TunnelSteer` / `LaneDriver` / `OrePlanner`.
* Unused methods removed (legacy HUD renderer helpers, `Cell.blocksSight/isBreakable`, `Pos.distanceSq`, three
  `WorldCache` getters, `BlockBreaker.canHit/breakTicks`, `RotationController.setProfile`, ...), dead `TunnelSteer`
  REVIEW branch and `turnTo`, `CoreEvents.RouteCompleted` (no subscriber).
* One route compression: `OrePlanner.waypoints` = `RoutePath.compress`; one per-node cost wrapper (`OrePlanner.NodeCosts`).
* Steering view = live world cache with the saved world archive behind it (`OreMacroModule.steerView`): not yet scanned
  blocks are no longer walls. Diagnostics in the log: `[ore_macro] way … ended after … (reason) … -> new way …` and a
  cache line every 10 s.
* Left on purpose: `utils/` (user files, unused, no package line), `LanePlanner.steep` vs `TunnelMap` (target choice vs
  steering), legacy v1 HUD widgets next to the session HUD.
