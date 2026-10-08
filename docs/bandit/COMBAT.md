# Bandit Macro: combat controller (Core Sprint 03)

The macro used to be "find a bandit, look at it, stand still, aim, throw". It is now a state machine that perceives, chooses and **keeps** a target,
builds a position, holds a distance, circles the target, avoids danger and breaks off when the fight is not worth it. The spear attack (aim with lead
and drop, throw, wait, recall with F) is unchanged in what it does, but it no longer freezes the player: the macro keeps orbiting while it aims and while
the spear is out; only the throw itself (about 0.8 s) holds still.

```
 Cosmic state store (entities, spear, zone)  ->  CombatInputs  ->  CombatBrain (pure FSM)  ->  Decision
                                                                         |                        |
   live world only for the target + a few candidates                     |                        v
   (line of sight, speed)                                                +--> attack allowed? --> spear attack (Attack enum in the module)
                                                                                                  |
   MovementIntent / RotationIntent  ->  ControlService (one winner per tick)  ->  keys and view
```

Code: `modules/qol/bandit/combat/*` (pure, tested without a game), `BanditMacroModule` (glue and the spear attack), `WorldTerrain` (ground probes).

## States

| State | What it does | Leaves for |
| --- | --- | --- |
| IDLE | nothing: no keys, no view, every temporary state cleared | SCAN on start |
| SCAN | no inputs; waits for a usable bandit (patrol route runs here if one is set) | TARGET_SELECT |
| TARGET_SELECT | one tick: score the bandits, lock one | ORBIT / EVADE / APPROACH |
| APPROACH | walk to a point on the orbit radius, on the side away from other bandits; direct when the line is open, else the path finder | ORBIT, EVADE, REPOSITION, LOST_TARGET, RETREAT |
| ORBIT | move around the target at the orbit radius, facing it | APPROACH, EVADE, REPOSITION, ENGAGE |
| ENGAGE | the throw is being committed: hold still | ORBIT |
| EVADE | short step away from what is too close (best free direction, away from threats) | ORBIT / APPROACH, REPOSITION, RECOVER |
| REPOSITION | a better spot on the orbit radius (reachable in a line, open around, away from others), same target | ORBIT, RECOVER |
| RECOVER | four bounded local tries (away, left, right, jump) | back to the fight, or LOST_TARGET after 3 in 30 s |
| RETREAT | break off, run along the best free direction until calm | COOLDOWN |
| LOST_TARGET | release the lock (blacklist when it was unusable) | COOLDOWN |
| COOLDOWN | pause (0.4 s; 0.8 s after a loop; waits for health after a low-health retreat) | SCAN |
| STOPPED | terminal; the module ends the macro | |

## Target choice (`TargetSelector`)

Score = 100 - 1.2 x |distance - orbit radius|, -25 when closer than the minimum, +20 line of sight / -10 none, +8 when the straight way is open (probed for the
three best only), -6 per other bandit near it (max -24), +10 when it is already hurt. A bandit with a real player within 6 blocks, beyond the target range or on the
blacklist is not a candidate. **Lock:** the chosen target gets +25 (+12 after 6 s) and keeps it until it is gone for 1.5 s, unusable (no path 3x, out of sight 4 s,
approach timeout) or a challenger beats it by 10 after the lock is 1.5 s old. Weights are our design, not game facts.

## Distance control (`DistanceBand`)

TOO_FAR / COMBAT / TOO_CLOSE from the macro's minimum and maximum distance, each limit with a 2 block dead band (a bandit standing on a limit cannot flip the state).
Orbit radius: the setting, else **AUTO = 40 % into the band** (until a verified value exists: `bandits.preferredCombatDistance`, today UNKNOWN). The log says which
source is used. Within the band the radius is corrected (towards / away) when it is off by more than 2.5 blocks.

## Orbit (`OrbitPlanner`)

Direction of travel = the tangent of the circle around the TARGET (never around the player), corrected towards the radius, pushed away from other bandits and
players. The side (left / right of the facing target) is chosen by free space, flips only when the current side is blocked (wall, drop, hazard, body, unknown ground)
and the other is better, at most every 1.2 s (unless fully stuck). Both sides blocked = REPOSITION. The view stays on the target; the keys come from the world
direction and the real view yaw (`Geo.keysFor`, 8 directions), so lag in the view does not bend the path.

## Threat and player model

`ThreatContext`: primary target, other bandits within the threat radius (12 blocks by setting; the verified aggro range when known), nearest distance, count.
A second bandit changes positioning and can cause EVADE (two close) or RETREAT (more than "Max nearby bandits"), it never becomes the target by itself.
Other real players (friends / gang excluded) are never targets: they count as bodies in the way, as a reason to back off (existing `BanditDanger`: caution inside the
safety radius -> RETREAT, critical -> stop after the spear is back; a retreat from a player ends only at 1.2 x the radius) and make bandits next to them bad targets.

## Terrain (`Terrain`, `WorldTerrain`, `EscapePlanner`)

Short ground probes (16 directions ~5x per second, a few more per tick for orbit) with the path finder's own walkability: floor, headroom, step height (jump up to 1.1),
drops over 2 blocks, hazards, unknown blocks (never assumed to be air). Results: free distance and why it stopped (WALL, STEP, HEADROOM, DROP, HAZARD, UNKNOWN). No navmesh;
long ways use the existing path finder (`LaneDriver`).

## Ownership of keys and view

The module never presses keys or sets the view. It submits one `MovementIntent` and one `RotationIntent` per tick; `ControlService` keeps the highest `IntentPriority`
(MANUAL > EMERGENCY > UNSTUCK > COMBAT_EVADE > PATHFINDING > TARGET_LOOK > IDLE): EVADE / RETREAT use COMBAT_EVADE, RECOVER UNSTUCK, travelling PATHFINDING, the target look
and the attack's aim TARGET_LOOK (the aim wins over the following look by its rotation mode). Manual movement keys stop the macro at once (brain + the existing safety rule).

## Spear

`SpearStatus`: KNOWN_READY / KNOWN_COOLDOWN (only when the client can read the item cooldown) / UNKNOWN. A known cooldown blocks the attack (the macro keeps orbiting); UNKNOWN
is handled conservatively: no cooldown is assumed, the macro paces its throws itself (0.5 s after the spear is back, one at a time). **Removed:** the random reaction and
cooldown delays of the old macro (fixed 4 ticks now).

## Loops and limits (`LoopDetector`)

Detected: orbit without progress (4 s, < 25 degrees), 4 side flips in 6 s, two states passing the macro back and forth (5 moves in 8 s, not counting waiting or the
ORBIT / ENGAGE toggle of a throw), 3 target choices in 10 s, 3 path failures in 15 s, turning 540 degrees in 2.5 s without moving (the control layer's `SpinGuard` backs this up
globally). First loop: stop, stabilise 0.8 s, drop target and path (blacklist 20 s), scan and plan once more. A second one within 60 s stops the macro. Bounded elsewhere:
3 recoveries / 30 s, 3 repositions / 20 s, 3 retreats / 90 s, unreachable after 3 path failures.

## Logging (developer mode = `-Dtheprisons.dev=true` or the "Debug mode" setting)

State changes and decisions only: `[BanditCombat] STATE ORBIT -> EVADE reason=TOO_CLOSE | view ... keys ... (owner/priority) spin ...` (the control layer's owners are
appended), `TARGET`, `TARGET_SWITCH`, `ORBIT_FLIP`, `ATTACK -> ...`. Always logged (also without debug): `START`, `STOP`, `LOOP_DETECTED`, `SPIN_GUARD`. The context fields on `STOP`
and `LOOP_DETECTED`: BANDIT_STATE, TARGET, TARGET_DISTANCE, TARGET_SCORE, THREAT_COUNT, DISTANCE_STATE, ORBIT_DIRECTION, RECOVERY_COUNT, RETREAT_REASON, PATH_STATUS. With debug
on, the HUD shows state, distance state, threats, orbit side, owners of keys and view, path status and the reason.

## Capture

`/prisons capture` (capture mode, see CAPTURE.md) adds a `bandit.*` block to the file's `extras`: FSM state and reason, target (id, kind, distance, score), distance state, orbit side
and progress, threats, recoveries, retreat reason, loops, the 16 terrain probes, movement / rotation owner and intent, path status, attack phase, spear status, the live bandits.
Positions and health of the player, equipment, entities, nearby blocks, zone and event are already in the frame. Extras are informational (not replayed).

## Settings

Targeting: target range, minimum / maximum distance, **combat distance (0 = auto)**, **max nearby bandits**, patrol route, walk into range, stickiness. Safety: player detection / radius,
**retreat below health** (default 8 hp, our choice, not a game value; resumes 4 hp higher), timeouts. Combat / Recall: as before. Debug.

## Unknown Cosmic values (kept UNKNOWN, see `assets/theprisons/cosmic/knowledge.json`)

Bandit aggro range, the best fighting distance, bandit respawn, the spear's cooldown, what "elite" looks like, the real health thresholds. Every default above is a macro setting that
a verified value replaces (`CombatConfig.knownAggroRange`, `knownPreferredDistance`).

## What must be tested in the game (nothing here ran in a client)

1. Start next to a bandit field: it approaches, circles at ~17 blocks, throws while moving, recalls, goes on. 2. A wall / pit on one side: the orbit goes the other way. 3. Two bandits close: it keeps
its target, steps back. 4. Press a movement key: the macro stops at once. 5. Low health: retreat, wait, resume. 6. A player walks near: back off, stop on the third time.
7. Watch `[BanditCombat]` lines: no state flutter, no loop lines; take a `/prisons capture` in a bad moment.
