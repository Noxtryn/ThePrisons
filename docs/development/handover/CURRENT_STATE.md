# Current state

Baseline: `origin/dev` at `0d3cb956` (V4 pipeline). Phase 3 is being integrated as small, separately reviewed commits.

The project is a Java 21 Fabric mod. The normal verification command is
`./gradlew test build --no-daemon`, followed by `./scripts/check-release.sh`.

The pre-existing HUD, market, and Bandit edits are reviewed independently of the V4 pipeline.
`stash@{0}` remains preserved and unapplied: it mixes an obsolete pre-V4 HD asset set with an
incomplete navigation experiment (the planner orchestrator is unchanged), so it must not be
applied wholesale.

Remote: `origin` is `https://github.com/Noxtryn/ThePrisons.git`; development is on `dev`.
`Release` is not a target for this work.
