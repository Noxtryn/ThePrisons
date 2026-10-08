# Bandit movement engine (developer test: `bandit_dodge_test`)

Only MOVES: no spear, no aim, no attack. Active with `-Dtheprisons.dev=true`, command `/prisons banditdodgetest`. The logic is pure
(`modules/qol/bandit/dodge/`), the module only senses, calls the planner, submits a `MovementIntent` and draws debug.

## Layers

| Layer | Class | Job |
|---|---|---|
| Perception | `DodgeInputs`, `DodgeBandit` | player, view yaw, body width, every bandit with velocity, ground |
| Executed movement | `DodgeDrive`, `ExecutionModel`, `ExecutedPath` | the keys follow the VIEW (8 directions, sprint with W), the view catches up with a dead zone: the route the keys really walk |
| Body terrain | `BodyClearance` | centre + both body edges swept along the route: corners, gaps, steps (all edges must climb), drops; room beside the body |
| Threat field | `ThreatField` | every bandit predicted along the route: closest approach, summed danger (crowd), gap |
| Scoring | `CandidateScorer` | one explainable score (`Terms`) |
| Momentum | `MotionMemory` | heading hold, evade enter/exit, left-right flutter commit, failed headings, stuck (last resort) |
| Aim window | `AimWindow` | N safe ticks; any breach of the minimum distance closes it at once |
| Orchestration | `BanditDodgePlanner` | 16 headings + current, choice with hysteresis |

## Rules
* Safety is judged on the EXECUTED route, never on the ideal ray. The desired heading is only a preference (small penalty per degree of final error).
* The body is 2 x half width (from the player), not a point.
* Wall pressure: a route that ends in a wall / drop / hazard within `wallComfort` (7 blocks) is penalised quadratically; under 4 it is unsafe, under 3 blocked.
* A jump needs a real step that every body edge climbs and 2 blocks of floor after it; a step into a wall is a wall.
* Minimum distance (default 8, macro setting, not a Cosmic fact) applies to EVERY bandit.
* Spear area VALID/INVALID only from a verified `spearUsable` zone attribute; UNKNOWN today.

## Live diagnostics
Log (changes only): `[BanditDodge]` decision line, `COLLISION_PLAN` (desired / executed / error / centre / left / right / stop / wall pressure),
`SCORE` (every term of the chosen route + camera model error: how far the real yaw was from `DodgeDrive.nextYaw`'s prediction).
Overlay: white = desired, blue = whole executed route, cyan / yellow / magenta = centre / left / right body probe.
