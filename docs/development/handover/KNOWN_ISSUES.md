# Known issues and constraints

- The supplied ZIP manifest claimed all images were 256×256. Fifteen were 1254×1254 and were
  normalized to 256×256 during the documented import; source and output SHA-256 values are retained.
- `random_*`, pets, masks and every family outside the supplied 35 paths deliberately retain their
  Classic/Cosmic texture. Do not extend the standard allow-list by filename similarity.
- HUD, market, and Bandit Phase-3 changes are intentionally committed separately from V4 and
  require in-game GUI smoke tests in addition to unit tests.
- Do not apply `stash@{0}` blindly: it includes superseded HD V2 assets and a partial navigation
  API change whose `LocalNavigator` orchestrator was not changed.
- Unit tests validate pack composition, hashes, dimensions, alpha, model paths, config cleanup and
  fallback behaviour. An in-game reload/client visual smoke test remains required before release.
