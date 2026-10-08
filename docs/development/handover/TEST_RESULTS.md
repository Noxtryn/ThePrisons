# Test results

## V4 static validation

- Registry seed was regenerated and byte-compared with `docs/textures/V4_ASSET_REGISTRY.json`:
  pass (465 identities).
- Pillow import is available locally (12.3.0).
- Empty V4 build: pass. The output contained only the manifest and pack metadata and marked all
  465 identities `CLASSIC_FALLBACK`.
- `python3 -m unittest tools/textures/v4/test_pipeline.py`: pass (4 tests).
- `./gradlew test build --no-daemon`: pass (8 tasks, all up-to-date).
- `./scripts/check-release.sh`: pass (`release check OK (1.2.1)`).

Run and record:

```bash
./gradlew test build --no-daemon
./scripts/check-release.sh
```

## Phase 3 integration

- `./gradlew clean test build --no-daemon`: pass after a source rebuild (only existing deprecation /
  unchecked-operation warnings).
- HUD layout tests cover the three styles, narrow/wide widgets, font widths, long strings, bars and
  Bandit/Ore views.
- AH and `/ee` fixture tests cover fractional history sales, sale-slot confirmation, scam outliers,
  cost calculation and malformed quote rejection.
- Final pre-push verification on `a6188a8`: `./gradlew test build --no-daemon`: pass (8 tasks);
  `./scripts/check-release.sh`: pass (`release check OK (1.2.1)`); V4 pipeline tests: pass (4 tests).

## Phase 4 integration

- `LocalNavigator` focused simulation suite: pass. This covers walls/corners, jump phases,
  blocked paths, heading stability, camera catch-up and multi-bandit spacing; it is not an
  in-game production Macro test.
- Targeted HD pack, dashboard and AH tests: pass. V4 pipeline partial-family fallback is covered
  by a Python unit test; GUI-scale coverage is layout simulation, not an in-game GUI smoke test.
- Verification on `7e65092`: `./gradlew test build --no-daemon` passed (8 tasks) and
  `python3 -m unittest tools/textures/v4/test_pipeline.py` passed (5 tests). Final release guard,
  remote-SHA verification and the same build/test commands still run immediately before push.

## Unified UI V4 foundation

- Targeted `ItemPresentationTest`, `MarketV2Test`, `ItemIdentityTest`, and
  `InventoryListLayoutAndInputTest`: pass. This covers canonical/fallback rarity colours, exact
  catalog-key market lookup, no-data suppression, source/window and age rendering, plus existing
  inventory layout/input behaviour.
- This is source-level and fixture validation. Original Cosmic server lore still requires a live
  server capture because registry-only list icons cannot contain unretrieved server components.
- Dashboard-to-Click-GUI delegation is a source-level integration path: both screens receive the
  same `ThePrisonsCore`; a live client smoke test remains appropriate for pointer flow and scale.
