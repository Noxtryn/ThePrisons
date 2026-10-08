# Claude next tasks

1. When V4 artwork arrives, validate its hashes and complete-family gate, then populate only
   `resourcepacks/theprisons_items_hd_v4` from the reviewed artifact; test resource reload and
   Classic / V2 / V4 switching in-game.
2. Perform in-game GUI-scale smoke tests for the three Session Dashboard styles and Bandit view.
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
