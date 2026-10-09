# Paused: Cosmic item look

Product-owner decision 2026-10-09: the Cosmic item system is paused until explicitly re-approved. Do not develop it further.

This tree holds the item look (`ItemLookModule`), its models/textures, the 35 standard textures, the comic texture filter,
the tooltip look pack and their six mixins. It is **not** part of the JAR: `build` neither compiles nor packages it.

- Keep it compiling against the current code: `./gradlew checkPausedCosmicItems`
- Scope, verification and the exact reactivation steps: [`docs/development/COSMIC_ITEM_PAUSE_PLAN.md`](../../../docs/development/COSMIC_ITEM_PAUSE_PLAN.md)
- Guard in the shipped test suite: `src/test/java/io/theprisons/modules/qol/items/CosmicItemPauseTest.java`
