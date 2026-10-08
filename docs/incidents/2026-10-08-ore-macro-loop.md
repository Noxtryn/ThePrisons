# Incident 2026-10-08: Ore Macro "circles instead of going to the next ore"

Source: `PrismLauncher/instances/Cosmic/minecraft/logs/latest.log`, the newest real session (01:50:35 - 01:58:13), plus
`config/theprisons/sessions.json`. 130 `[ore_macro] trace` lines (every 2 s), 5 world re-loads, 6 macro runs.

```
INCIDENT:   the macro walked closed loops for a long time (reported by the product owner)
TIMELINE:   01:52:36 first run (world archive, 94 guards, 139 routes loaded)   01:52:38 first trace at 1373,123,609
            01:52:51 plan "wall ahead" (3 legs, 76 blocks, 65 ores)          01:52:53 plan "way blocked" (1 leg)
            01:53:06-01:53:44 ring #1 (1465,658) -> (1472,654) U-turn -> west to (1429,651) -> north (1438,625) -> south (1436,660) -> north
            01:53:53 world re-load #2 (relocation)  01:54:27 "attacked: running to the guard"  01:54:46 re-load #3
            01:55:06 re-load #4, ring #2 over the same coordinates (x 1425-1477, z 619-662)  01:55:41-42 three plans in a row
            01:56:18 re-load #5  01:56:21-01:57:37 last run: 356 blocks walked, net 13 blocks away from the start
SELECTED TARGET: NOT LOGGED. The classic steering follows the tunnel "axis"; the trace says "ore ahead yes" in all 130 samples.
PATH:       10 "new route" plans, 3 "route walked"; 4 of 6 runs spend the time in "axis" steering with "no route".
ROTATION:   NOT LOGGED (only the steering direction, "classic way N deg"). The direction is consistent across the +-180 boundary
            (175 -> -163 is 22 deg), so there is no sign of a wrap error in what the steering decides.
MOVEMENT:   positions at 2 s: 4-5 blocks/s. Per run: path vs net displacement 293/59, 124/38, 200/19, 356/13; accumulated heading change
            650-1200 deg per run; the ring of ~45 x 40 blocks repeats with a period of ~45 s. Heading flips of ~180 deg at dead ends
            (01:53:08 -> 01:53:10: -123 -> +77) match the session counter dead_ends = 7 / 4.
STUCK EVENTS: none logged. Counters of the three newest runs: unwalled 2/2, no_way 1, plan_blocked 1/2, dead_ends 7/4, plans 6/3.
TARGET SWITCHES: NOT LOGGED.
SERVER CORRECTIONS: not logged. 5 world re-loads in 5 minutes (relocations); each calls onRelocated -> forgetWalked() / steer.reset().
YIELD:      27 + 376 + 515 = 918 ores in 165 s (session counters); the diamond ore satchel grew by ~1,900; Legendary and Godly shards were
            found. The macro was productive while it looped.
ROOT CAUSE: NOT FULLY OBSERVABLE
CONFIDENCE: low for any single cause; fairly sure it was NOT a stuck / repath / target-switch loop (counters) and NOT a yaw-wrap error.
SECONDARY CONTRIBUTING FACTORS (hypotheses, not proven):
  H1  the visit memory (`walked`) is wiped on every world re-load, so the ring is walked again after each re-load;
  H2  dead ends inside a closed tunnel complex force U-turns, the steering then finds the same ring again;
  H3  if the product owner saw the CAMERA spinning on the spot, nothing in a 2 s trace can show it (no yaw was logged).
```

## What was changed (small, isolated)

* **Telemetry** (this is the missing data): the 2-second trace now also carries `stuck a/b recover n walked n` and the control layer's
  summary `view <yaw> -> <requested yaw> (<rotation mode>/<owner>) keys <keys> (<source>/<priority>) spin <deg>/<ticks> reach <blocks> made <n>`.
  `ControlTelemetry` keeps the last 10 s (200 ticks) in memory. Owner changes are logged with `-Dtheprisons.dev=true` or
  `-Dtheprisons.control.log=true` (at most once a second), never per tick.
* **Spin guard** (`SpinGuard`, wired in `ControlService`): 720 deg of yaw in 3 s, at most 2 blocks of movement, no block changing nearby ->
  `SPIN_LOOP_DETECTED`: keys and view stop for 10 ticks, `AutomationModule.onSpinLoop()` drops plan / route / travel (Ore Macro: `onSpinLoop`),
  one fresh plan; a second spin within 90 s stops the macro. Walking a circle and turning while blocks break do not trigger it. It would
  NOT have triggered in this log (the macro moved), which is why the telemetry is the main result.
* **Arbitration** (`IntentPriority`, `InputController.request`): MANUAL > EMERGENCY > UNSTUCK > COMBAT_EVADE > PATHFINDING > TARGET_LOOK > IDLE.
  Only the control layer writes keys and view. Existing code is unchanged in effect: `set(keys)` is a PATHFINDING request (last writer among equals
  wins, as before); `RotationMode` keeps its internal order (aiming at a block still beats the path follower) and only gained `intent()`.
  Decision recorded: the example list puts TARGET_LOOK below PATHFINDING; the macro's existing order is the opposite inside the rotation
  controller, and it was not changed without an in-game test.

## Next time it happens

Send the lines `[ore_macro] trace` around the incident (they now show yaw, requested yaw, who owned view and keys, the pressed keys, stuck counters)
and any `[control] SPIN_LOOP_DETECTED` line. If the loops are H1, `walked` in the trace drops to 0 right after each re-load.
