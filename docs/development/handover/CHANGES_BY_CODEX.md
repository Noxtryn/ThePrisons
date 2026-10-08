# Changes by Codex

## V4 pipeline integration

- Added the V4 asset registry and offline import pipeline supplied in the worktree.
- Added handover documentation and repository hygiene rules.
- Removed tracked Python bytecode artifacts after confirming they are generated outputs.

Verification is recorded in `TEST_RESULTS.md`. The commit SHA is available from the Git log;
keep future entries scoped to one reviewed change set.
## Phase 3 (in review)

- Archived the retired redesign experiments under `archive/`; the archive is explicitly excluded from
  compilation and V4 imports.
- Replaced the session HUD draw-path with a prepared, responsive dashboard model and renderer. It has
  Minimal, Standard and Detailed layouts; layout regression tests cover widths, font metrics and both
  Ore and Bandit information.
- Corrected AH history ingestion so only the slot that is a confirmed sale is learned and fractional
  sale quantities produce the correct unit price. Hardened `/ee` analysis against invalid quotes.
- Added a non-invasive Bandit dashboard adapter. The incomplete goal-navigation experiment in
  `stash@{0}` was reviewed but deliberately not applied.

## Phase 4 (in review)

- Re-audited `LocalNavigator` and the untracked portion of `stash@{0}`. The latter remains an
  incomplete, incompatible goal-navigation experiment plus obsolete assets; no movement code was
  copied into the production macro.
- Added an explicit HD V4 validated overlay choice and an empty pack scaffold. It selects exactly
  one overlay (V2 or V4), keeps the always-on look pack last, and falls through to Classic while
  no V4 artwork is present.
- Added V4 partial-family gating, HD selection, GUI-scale layout, and age-shifted repeated-sale
  regression coverage.
