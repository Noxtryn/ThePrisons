# Bandit Dodge Test (developer only)

Temporary module `bandit_dodge_test` (`-Dtheprisons.dev=true`, command `/prisons banditdodgetest`, or the module list). It only MOVES: no spear, no aim, no attack.
Logic: `modules/qol/bandit/dodge/BanditDodgePlanner` (pure, unit-tested). The module only senses, calls the planner, submits `MovementIntent` and draws debug.

* 16 directions + the current heading, each probed on the ground and sampled every 2 blocks against EVERY bandit (velocity-predicted, max 0.6 s).
* Score: free distance, separation (capped at min + half the warning band), summed crowd danger, worst point, gap width, forward continuity; minus turn size, reversal, dead end, jump cost, recently failed heading.
* Minimum distance (default 8 blocks, a macro setting, not a Cosmic fact): any bandit under it = breach: aim window closes in that tick, EMERGENCY priority.
* Aim window: N stable safe ticks (12). Spear is NOT connected.
* Spear area: VALID/INVALID only from a verified `spearUsable` zone attribute; it is UNKNOWN for every zone today.
* Stop: any manual input, or toggle again.
