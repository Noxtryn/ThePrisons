# Feature status

| Area | State | Notes |
| --- | --- | --- |
| Cosmic item look (own textures, tier frames, badges, comic filter, 35 standard textures) | **Paused 2026-10-09** | Not shipped; kept in `src/paused/cosmic-items`. See `docs/development/COSMIC_ITEM_PAUSE_PLAN.md`. |
| Legacy HD V2 / V4 | Archived | No longer selectable or loaded; restoration source is `archive/legacy-texturepacks/`. |
| Config GUI (Dashboard + ClickGui) | To be replaced | Full rebuild in Phase 2 after the approved design; see `docs/development/PHASE_2_IMPLEMENTATION_PLAN.md`. |
| Session dashboard | Phase 3 integration | Prepared view/layout/renderer with Minimal, Standard and Detailed styles; requires GUI-scale smoke test. |
| AH / Energy analytics | Phase 3 integration | Passive only; confirmed-sale quantities and invalid `/ee` quotes are fixture-tested. |
| Bandit navigation | Simulated / debug only | Collision, corner, jump, crowd, heading and camera-follow tests pass. Production remains on `CombatBrain`; stash is not applied. |
