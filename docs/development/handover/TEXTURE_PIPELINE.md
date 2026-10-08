# Texture V4 import pipeline

Entry point: `python3 tools/textures/v4/pipeline.py`.

The registry is `docs/textures/V4_ASSET_REGISTRY.json`. Each row holds the technical identity,
family, variant, visual asset ID, incoming filename, existing texture target, SHA-256, and status.
The canonical registry is seeded from real `items/prisons` definitions, so it cannot introduce
fictional item keys.

## Import procedure

1. Put supplied PNGs below an untracked `tools/textures/v4/sources/` directory.
2. Fill only the corresponding registry rows. Set `validation_status` to `APPROVED` only after
   confirming the actual item identity and SHA-256.
3. A source may be a 256px PNG or a sprite sheet. Use `sprite.rect: [left, top, right, bottom]`
   for any layout, or `sprite.cell`, optional `margin`, and optional `gap` for a 256px grid.
4. Build to a new path; the builder uses exclusive file creation and will not overwrite a reviewed
   pack:

```bash
python3 tools/textures/v4/pipeline.py build \
  docs/textures/V4_ASSET_REGISTRY.json tools/textures/v4/sources \
  tools/textures/v4/output/theprisons-hd-v4.zip
```

The gate rejects non-PNG, non-RGBA, animated, non-256px, opaque, transparent-only, escaping,
unhashed, duplicate/conflicting, wrong-model-target, and inconsistent random-variant inputs.
It validates the existing model JSON and checks a Classic texture exists for every model target.
Only complete approved families enter the overlay; all others remain Classic. The ZIP includes a
deterministic manifest with final validation status.

The V4 ZIP is an external overlay for artwork review. Keep the built-in Classic/HD V2 selection
unchanged until a separately reviewed runtime integration is approved.
