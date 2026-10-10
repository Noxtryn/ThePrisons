# Warden safety QA — 2026-10-10

Base: `origin/dev` at `5664bfb`. Work branch: `fix/warden-safety-distance`.
Separate checkout: `warden-qa`; no changes to the existing Discord-bot files, Release, or the original stash.

## Cause

Warden detection is working: the named iron golem is found by `OreMacroModule.findWardens/isWarden`.
The safety information was disconnected from movement:

- `ClassicSteer.decide` accepted the warden array but did not use it. Its private `limitRayToWardens` helper had no caller.
- `TunnelSteer.decide` likewise ignored the array, including its free-mode path. Comments explicitly treated the 15-block circle as a non-binding preference.
- `OreMacroModule.guardedCosts` applied ordinary border/guard-tax restrictions but no warden exclusion.
- `PathStraightener.standsOnLevel` checked terrain but ignored infinite node penalties. It could shortcut across an obstacle already avoided by A*.
- Deliberate rest/flee paths targeted a 3-block ring around a warden, conflicting with the requested minimum.

The unchanged baseline reproduced the handover failure: closest reported distance **0.45466682784038626 blocks**.
This is not caused by the Config GUI replacement or HUD recognition.

## Fix

`modules/mining/ore/WardenSafety.java` defines one 15-block horizontal exclusion rule, with the existing 64-block vertical scope.
Both steering engines check the whole candidate segment before scoring ore, in both free and guided modes.
Starting inside after an external relocation permits movement outward, never a shortcut deeper through the circle.

Path jobs receive a frozen warden obstacle map, composed with existing border and guard-tax costs.
Cells near the edge include a corner margin. The path straightener now respects infinite penalties.
Guard-return jobs and rest/flee jobs use the same exclusion map; a warden rest/flee goal is an outer ring instead of a 3-block approach.
Ordinary guard rest/flee targets retain their close ring.

Before `Phases.CONTROL` applies keys, a final movement gate checks the current camera direction plus velocity look-ahead.
This covers centring, gradual camera turns, recovery and previously computed routes. It releases movement keys before the 15-block edge,
leaving the 1.5-block margin to the mandatory 13.5-block test threshold. It does not set player position or velocity.
The first active mining tick scans guards rather than waiting until tick five.

## Fixture correction — no weakened checks

The original generated cave contains only one guard source: the warden itself.
With defaults enabled, guard-return goals must be well inside its 15-block protected area (2-block inset), while warden safety forbids entering its 15-block circle.
There is no legal return goal. The first safety-fixed run correctly held **21.7 blocks** away but failed productivity: **51 ores / 120 s**, below the unchanged 150 threshold.

`RedstoneCaveClientGameTest` now includes four stationary ordinary guards at the cave corners, providing legal guarded mining ground outside the warden circle.
Neither guard safety nor warden safety is disabled. The existing 13.5-block minimum, 150-ore requirement, wall-contact, floor-only and tick-time assertions are retained.
The test measures both the previous block-centre reference and the actual integer `/summon` coordinates, taking the smaller distance.

## Regression coverage

`src/test/java/io/theprisons/modules/mining/ore/WardenSafetyTest.java`:

1. Entry, crossing chords, tangents, outward escape and the vertical-scope boundary.
2. Planner cell corners, preservation of existing obstacles and frozen snapshots.
3. Both steering engines, free and guided modes, with ore-rich paths into the warden circle.
4. Actual A* plus path straightening around the circle, checking all shortcuts as well as path nodes.
5. Guard excursions/corridors cannot override exclusion, and escaping one warden cannot approach another.

## Validation

- `gradlew.bat test build checkPausedCosmicItems --no-daemon`: **748 tests, 0 failures/errors, 1 skipped**; the separate paused suite has **8 passing tests**.
- Shipped JAR inspected: no paused Cosmic item runtime classes, model/item assets or resource packs.
- The first corrected-fixture client attempt was interrupted at about 65 test seconds. Although Gradle reported success, Fabric logged `runOnClient called when no client is running`; that attempt is **not accepted as a passing test**.
- A second corrected-fixture attempt stopped during world loading with the same client-shutdown exception; it is also incomplete.
- Further macro client runs were stopped at the product owner's request. Client QA remains incomplete; the passing build and unit regressions do not establish a complete client pass.

No live Cosmic-server validation is claimed. External teleports/knockback can place the player inside a circle; the fix permits escape and prevents automatic movement deeper in.
No Config GUI, HUD, overlay, Discord-bot, release or Cosmic-item feature changes are included.
