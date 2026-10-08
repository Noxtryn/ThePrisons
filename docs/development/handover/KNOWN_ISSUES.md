# Known issues and constraints

- The V4 builder requires Python 3 and Pillow. It performs no network access and no image scaling.
- An entire item family is emitted only when every registered identity in that family is approved;
  otherwise Minecraft resolves the Classic texture.
- The current pipeline enforces one canonical visual ID for `random_*` variants. Do not invent
  per-tier random artwork or remap pets/masks by filename.
- HUD, market, and Bandit Phase-3 changes are intentionally committed separately from V4 and
  require in-game GUI smoke tests in addition to unit tests.
- Do not apply `stash@{0}` blindly: it includes superseded HD V2 assets and a partial navigation
  API change whose `LocalNavigator` orchestrator was not changed.
