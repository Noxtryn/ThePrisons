# Test results

## V4 static validation

- Registry seed was regenerated and byte-compared with `docs/textures/V4_ASSET_REGISTRY.json`:
  pass (465 identities).
- Pillow import is available locally (12.3.0).
- Empty V4 build: pass. The output contained only the manifest and pack metadata and marked all
  465 identities `CLASSIC_FALLBACK`.
- `python3 -m unittest tools/textures/v4/test_pipeline.py`: pending after the test file is added.
- `./gradlew test build --no-daemon`: pass (8 tasks, all up-to-date).
- `./scripts/check-release.sh`: pass (`release check OK (1.2.1)`).

Run and record:

```bash
./gradlew test build --no-daemon
./scripts/check-release.sh
```
