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

Texture selection now has `Classic`, `HD V2`, and `HD V4 (validated)`. The bundled V4 directory
currently contains only pack metadata; selecting it safely renders Classic fallbacks until a
separately reviewed registry build supplies approved textures.

The third texture-pack option is technically compatible with the current product behaviour: it is
mutually exclusive with V2 and cannot alter Classic paths. It is nevertheless a product-selection
question because the earlier V4 handover described it as an external review overlay. No selection,
copy, or texture behaviour was changed during Unified UI V4 work.

Unified UI V4 has one shared rarity palette (`ItemRarity`) and one shared market-tooltip adapter
(`ItemMarketTooltip`). Generic in-game tooltips preserve their original Minecraft/Cosmic content;
the adapter appends only cache-backed fair price, confidence, source/window, sample count and age.
The inventory list now starts from Minecraft's tooltip for its actual rendered stack, so its
generated registry icons do not claim unretrieved server lore.

Remote: `origin` is `https://github.com/Noxtryn/ThePrisons.git`; development is on `dev`.
`Release` is not a target for this work.
