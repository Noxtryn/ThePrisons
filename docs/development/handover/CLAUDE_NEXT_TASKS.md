# Claude next tasks — after Phase 1 (2026-10-09)

Work only on `dev`; preserve `Release`, `stash@{0}` and the six uncommitted mode diffs. Start from `CURRENT_STATE.md`.

1. **Wait for the approved Claude Design specification.** No new Config GUI and no repair of `DashboardScreen`/`ClickGuiScreen` before it arrives (the former P0 scale-3 fix is superseded by the rebuild).
2. **Decisions needed from the product owner** before Phase 2 or any release: public presentation of the paused item look (`docs/audit/RELEASE_AUDIT.md` §3), back-merge of `origin/Release` into `dev`, push of the local Phase-1 commits, open UI questions (`docs/design/UI_REPLACEMENT_REQUIREMENTS.md` §10).
3. **Phase 2** strictly in the order of `docs/development/PHASE_2_IMPLEMENTATION_PLAN.md` (P2.0 … P2.8). The old GUI is deleted in P2.4, in the same change that proves full coverage. No hidden alternative.
4. **Live acceptance** in Prism instance `Cosmic` on Cosmic Prisons (`/ah`, `/ee`, Item List, original tooltips/lore) remains **MANUAL TEST REQUIRED**. Never substitute synthetic prices.

**Hard stops:** Cosmic item system paused (no textures, item look or texture settings) until explicit re-approval. No Bandit Macro work before UI acceptance. Do not apply `stash@{0}`. No release, tag or Discord post without approval.

Previous task list (Codex handover, 18:34) is superseded; history in Git.
