# Known issues and constraints

- The V4 builder requires Python 3 and Pillow. It performs no network access and no image scaling.
- An entire item family is emitted only when every registered identity in that family is approved;
  otherwise Minecraft resolves the Classic texture.
- The current pipeline enforces one canonical visual ID for `random_*` variants. Do not invent
  per-tier random artwork or remap pets/masks by filename.
- HUD, market, and Bandit edits were present before this integration and remain uncommitted;
  inspect their diffs independently before committing them.
- Do not apply the existing stash blindly.
