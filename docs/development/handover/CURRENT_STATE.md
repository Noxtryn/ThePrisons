# Current state

Baseline: `origin/dev` at `d203860` (Phase 3). Phase 4 keeps Bandit movement on the existing production `CombatBrain` path.

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

Remote: `origin` is `https://github.com/Noxtryn/ThePrisons.git`; development is on `dev`.
`Release` is not a target for this work.
