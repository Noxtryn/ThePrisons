# Claude next tasks

1. Perform an in-game resource-reload and visual smoke test for all 35 standard textures, then
   capture real Cosmic examples for each of the six families.
2. For future artwork, extend only `theprisons_items_standard/manifest.json`, its exact model
   allow-list and corresponding tests after a registry/model match; never restore V2/V4 selection.
3. Keep `stash@{0}` intact; do not apply it wholesale. Its pre-V4 HD assets are superseded and its
   navigation records are not wired into `LocalNavigator`.
4. Verify the V4 registry against live Cosmic item captures when artwork arrives, especially pets,
   masks, charge-orb progression, and random items.
5. If goal-aware Bandit navigation is resumed, replace rather than parallel `CombatBrain` movement;
   first supply a complete vertical slice (`LocalNavigator`, module inputs, telemetry and adversarial
   simulations) in a dedicated branch.
6. Continue Unified UI V4 from the shared `ItemRarity` / `ItemMarketTooltip` base: add screen-level
   screenshots or live-server captures for original Cosmic tooltip fidelity, then connect the
   Dashboard's generic module settings and market controls without duplicating their services.
7. Decide with product ownership whether the selectable, empty `HD V4 (validated)` entry remains
   user-visible before artwork ships. It is technically safe, but prior documentation framed V4 as
   an external review overlay; do not change the choice without that decision.
