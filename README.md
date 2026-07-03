# ThePrisons

Client-side Fabric mod for Minecraft `1.21.11`.

## Links

- [Modrinth](https://modrinth.com/mod/theprisons)
- [Source](https://github.com/olb-freelocs/ThePrisons)

## Features

- Persistent pet cooldown cache across relogs and restarts
- Trinkets and command cooldown display in the same HUD
- Satchel fill HUD with combined same-type satchels and threshold alerts
- Session stats HUD for XP, Cosmic Energy, and per-hour rates
- EasyView inventory overlays for common CosmicPrisons items
- Clue scroll step overlays and item insight tooltip lines
- Armor durability HUD with critical alerts
- Parsed event markers for meteors, meteorite showers, ore merchants, and bandit rushes
- Message mention/private-message notifications with optional sound
- Pickaxe/satchel drop protection and peaceful-mining hit protection
- Modrinth update checks that can be disabled in General
- Mod Menu integration, module GUI (Right Shift / `I` / `/prisons`) and HUD layout editor
- Ore Macro on a shared macro core (pathfinding, world cache, safety stops, profiler) — see `docs/core-rewrite/`

## Build

```bash
./gradlew build                 # mod jar + unit tests
./gradlew test                  # unit, simulation and benchmark tests
./gradlew runClientGameTest     # in-game integration test (opens a Minecraft window)
./gradlew legacyBenchmark       # "before" benchmark of the archived pre-rewrite code
```

Architecture, benchmarks and the Ore Macro design: [`docs/core-rewrite/`](docs/core-rewrite/).
