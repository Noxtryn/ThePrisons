# Claude next tasks

1. Perform in-game GUI-scale smoke tests for the three Session Dashboard styles and Bandit view.
2. Keep `stash@{0}` intact; do not apply it wholesale. Its pre-V4 HD assets are superseded and its
   navigation records are not wired into `LocalNavigator`.
3. Verify the V4 registry against live Cosmic item captures when artwork arrives, especially pets,
   masks, charge-orb progression, and random items.
4. Decide whether a reviewed V4 overlay becomes a built-in selectable pack; do not alter the
   existing Classic/HD V2 lifecycle without that decision.
5. If goal-aware Bandit navigation is resumed, implement and test its complete vertical slice
   (`LocalNavigator`, module inputs, telemetry and adversarial simulations) in a dedicated branch.
