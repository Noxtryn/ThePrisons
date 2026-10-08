# Cosmic game model, sensors, state store (Core Sprint 02)

```
Minecraft client
   -> sensors (core/cosmic/sense)        read what the client already has, as plain data (Raw.*)
   -> parsers / classifiers (.../parse)  pure text and rule code, shared by everybody
   -> CosmicGameModel (.../model)        knowledge: rules, facts, registries, every value a GameValue with a confidence
   -> SnapshotBuilder (.../data)         Raw.Frame + memory + model -> immutable CosmicContextSnapshot
   -> CosmicStateStore (.../state)       latest snapshot + bounded history, reset on world / context change
   -> consumers                          HUD (Session HUD now), macros and tools (later)
```

## Audit (what existed before)

| Topic | Where it was read / parsed | Now |
| --- | --- | --- |
| sidebar lines | `ClientReadouts.sidebar` called independently by SessionHud, ScoreboardModule, OreMacro (5 call sites), each sorting and decorating all rows | read once per sample by `FrameSampler`; **SessionHud consumes the store**. Still direct: ScoreboardModule, OreMacro |
| sidebar numbers (tax %, energy, XP, level) | regexes inside `CosmicStats.sidebar`, again in OreMacro's `EnergyTax`/tax reading | `CosmicPatterns` + `SidebarParser`; CosmicStats uses the shared constants |
| pickaxe energy from lore | `CosmicStats.loreEnergy` (used by SessionHud, PlayerViewer, OreMacro) | the rule lives in `PickaxeLore`; `CosmicStats.loreEnergy` delegates (all 3 callers unchanged) |
| zone | inline regex + static field in `ThePrisonsCore` | `ZoneParser` + `CosmicMemory`; `ThePrisonsCore.lastZone()` reads it (same answers, covered by an oracle test) |
| bandit classification | `BanditScan` (spear helper + bandit macro), name checks in BanditMacro, `SessionHud.banditActivity`, `ThePrisonsBanditManager` | rule in `BanditClassifier`; `BanditScan` delegates (98 combinations proven identical). **Still separate:** BanditMacro's own checks, SessionHud.banditActivity |
| spear rule | `SpearHelperModule.isSpear` | `SpearRule`; the module delegates |
| "human is pressing a key" | `SafetyMonitor.physicallyPressed` | `InputSensor`; the monitor delegates |
| entity scans | `world.getEntities()` loops in SpearHelper, BanditMacro (x2), OreMacro, plus `getEntitiesByClass` for projectiles | `EntitySensor` (bounded box, max 96, radius up to 64 on request via `CosmicStateService.interest`). **BanditMacro now reads the store** (entity ids -> live entity only for the target and 8 candidates); SpearHelper and OreMacro still scan themselves |
| meteor event | inline in SessionHud | `EventParser` (not yet used by SessionHud) |
| item classification | `PrisonsItems` (textures) and `ItemIdentity` (market) | sensors call `PrisonsItems.info`; ItemRegistry lists its 32 families |
| legacy | `ThePrisonsFeatureManager` (12 patterns), `ThePrisonsTracker`, market parsers, `Chores` money / shard text | untouched; candidates for the next sprints |

## Values and confidence

`GameValue<T>(value, confidence, source, observedAt, context, note)`, `Confidence` = `VERIFIED_OFFICIAL` (stated by the server, owner-confirmed),
`VERIFIED_LIVE` (read from the live game now), `OBSERVED` (seen / inferred, not guaranteed), `UNKNOWN`. UNKNOWN carries no value, never
becomes 0, and `orElse(x)` makes the fallback the caller's explicit choice. Data files must name a real confidence; a typo is an error.

## Model and registries

`CosmicGameModel`: areas `MINING, PLAYER, PICKAXE, ENERGY, ENCHANTS, BANDITS, WEAPONS, ARMOR, ITEMS, ZONES, EVENTS, SEASON, SOCIAL, ECONOMY`
(keys like `mining.speedRequirement`) and registries `OreRegistry, PickaxeRegistry, EnchantRegistry, BanditRegistry, ZoneRegistry, ItemRegistry`.
All data is JSON in `assets/theprisons/cosmic/` (extend by editing). Everything the repository could not confirm is listed as UNKNOWN on purpose,
that list is the question list for the owner. If the data cannot be read the game continues with an empty model.

## Sampling and cost

One loop (`CosmicStateService`) at TickEnd, priority just after the world cache: a sample every 4 ticks (5/s), entities in a 32-block box (max 96),
a 9x9x9 block cube every 5th sample (about once a second), inventory and container slots only for captures, item stacks re-read only when they changed.
Messages arrive by event. Profiler sections `cosmic:sample` and `cosmic:build` show in `/prisons perf`. Measured: snapshot build with 96 entities
about 0.08 ms (unit test), i.e. well under 1 ms per second of game time.

## Privacy

Sensors read only the normal client state. Player chat is not stored, private messages are dropped, stored text passes `PrivacyFilter`.
Captures are local files, anonymised, never sent anywhere (see `CAPTURE.md`). No telemetry was added.

## Control layer (incident follow-up)

`IntentPriority`, `InputController.request`, `SpinGuard`, `ControlTelemetry`: see `docs/incidents/2026-10-08-ore-macro-loop.md`.
