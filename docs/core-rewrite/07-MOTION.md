# 07 · View motion (frame-rate, human-like)

## Problem

Until now the view was stepped once per game tick (20 Hz) by a speed/acceleration limited easing
(`SmoothRotation`). Minecraft interpolates the camera between the last and the current tick's yaw, so the picture
was smooth-ish, but:

- the motion was piecewise linear: the speed changed in 50 ms steps, visible as a slight "stutter" at 60+ fps;
- the camera was always one tick (50 ms) behind the logic;
- the easing (exponential approach, 32 °/tick = 640 °/s cap) does not look like a hand on a mouse: sharp start,
  long creeping tail.

## Model

`core/control/HumanRotation` (pure, unit-tested in `RotationTest`):

| Aspect | Model | Source |
|---|---|---|
| Shape | minimum-jerk trajectory, quintic `10τ³ − 15τ⁴ + 6τ⁵` from rest; bell-shaped speed, peak = 1.875·D/T | Flash & Hogan 1985 |
| Duration | Fitts' law `T = a + b·log2(1 + D/W)`, a = 50 ms, b = 85 ms/bit, clamped 60–600 ms, speed cap 900 °/s | Fitts 1954, MacKenzie 1992 |
| Target size W | angular size of the block: `2·atan(0.5 / distance)`; 12° when unknown | |
| Retarget | the quintic is re-solved from the current angle, speed **and** acceleration → no velocity jump | Hoff & Arbib 1993 |
| Moving target | target shift < 3° keeps the running schedule (smooth pursuit); larger = new aimed movement | |
| Priority | per tick the highest `RotationMode` wins (mining > jump > recovery > turn > navigation); navigation turns at 75 %, jumps at 130 % | |

Deterministic: no noise, overshoot or sensitivity-grid snapping is added (see 01-ANALYSIS §3.3).

Examples (default profile): 10° correction ≈ 0.12 s, 45° onto a block 3 m away ≈ 0.20 s, 120° ≈ 0.34 s.

## Where it is applied

```
MinecraftClient.render()
 ├─ tick() × n           END_CLIENT_TICK → modules request a view → RotationController.apply(): new target,
 │                                         view written for "now" (the ACT raycast sees it)
 ├─ Mouse.tick()         vanilla mouse look ── ThePrisonsMouseMixin (TAIL) → RotationController.frame()
 └─ GameRenderer.render  camera uses the angle written this frame
```

Writing works like `Entity.changeLookDirection`: yaw/pitch and `lastYaw`/`lastPitch` move by the same delta, so the
camera shows the current angle without the one-tick interpolation delay. If the view was changed by someone else
(player mouse, server teleport) by more than 0.5°, the motion restarts from the real view.

Cost: one quintic evaluation per frame (~20 flops), no allocation besides a 2-element array.

## Reference projects

| Project | What it does | Taken over |
|---|---|---|
| MightyMinerV2 (`Archiv/`) | rotations sampled per render frame (`RenderWorldLastEvent`) along a cubic Bézier with a random ease function per turn | per-frame sampling; not the random curves / ease choice |
| MightyMinerV2 `PathExecutor` | aims the walking yaw at a path node ≥ 5 blocks ahead | already covered by `SteeringLogic` straight runs |
| OreMiner (`Archiv/`) | server-side vein miner (breaks adjacent ores on the server) | nothing: server mod, not a client macro |
| Taunahi | closed source; the public repo is a 724-line fragment | nothing available |
