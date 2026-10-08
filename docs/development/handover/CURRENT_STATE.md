# Current state

Baseline: `dev` at `85b3734` before the V4-pipeline integration commit.

The project is a Java 21 Fabric mod. The normal verification command is
`./gradlew test build --no-daemon`, followed by `./scripts/check-release.sh`.

The worktree contained uncommitted HUD, market, and Bandit edits when this handover was
created. They are deliberately not folded into this cleanup/pipeline change without their
own review. One stash exists (`stash@{0}`) and has not been applied.

Remote: `origin` is `https://github.com/Noxtryn/ThePrisons.git`; development is on `dev`.
`Release` is not a target for this work.
