# Current state

Baseline before Unified UI V4: `origin/dev` at `afeb370` (Phase 4). Phase 4 keeps Bandit movement on the existing production `CombatBrain` path.

The project is a Java 21 Fabric mod. The normal verification command is
`./gradlew test build --no-daemon`, followed by `./scripts/check-release.sh`.

The pre-existing HUD, market, and Bandit edits are reviewed independently of the V4 pipeline.
`stash@{0}` remains preserved and unapplied: it mixes an obsolete pre-V4 HD asset set with an
incomplete navigation experiment (the planner orchestrator is unchanged), so it must not be
applied wholesale.

`LocalNavigator` is presently the pure simulation/debug Dodge component (`BanditDodgeTestModule`),
not a second live Bandit Macro path. Its collision, corner, jump, crowd, unstable-heading and
camera-follow simulations pass. The production macro continues to use `CombatBrain` and its
existing lane driver, so no parallel movement architecture is introduced.

The old `Classic` / `HD V2` / `HD V4 (validated)` choice is retired. The built-in
`theprisons_items_standard` resource-pack overlay is always present and contains exactly 35 approved
Sci-Fi assets: 7 Shards, 6 Contraband, 6 Revealed Books, 6 Books, 6 Keys and 4 Charge Orbs. It is
inserted after player packs but before the existing ThePrisons Look pack, and its exact approved
models win over Cosmic Textures; every other item continues through the existing source/fallback
logic. Old V2/V4 files are safely retained in `archive/legacy-texturepacks/`.

Unified UI V4 has one shared rarity palette (`ItemRarity`) and one shared market-tooltip adapter
(`ItemMarketTooltip`). Generic in-game tooltips preserve their original Minecraft/Cosmic content;
the adapter appends only cache-backed fair price, confidence, source/window, sample count and age.
The inventory list now starts from Minecraft's tooltip for its actual rendered stack, so its
generated registry icons do not claim unretrieved server lore.

The Sci-Fi Dashboard remains the curated control surface. Every feature tile, plus the HUD-page
"All module & market settings" link, opens the complete existing Click-GUI editor for the same
core instance. This makes QoL market controls and advanced settings reachable in production while
avoiding a second, divergent setting mutation path.

Remote: `origin` is `https://github.com/Noxtryn/ThePrisons.git`; development is on `dev`.
`Release` is not a target for this work.
