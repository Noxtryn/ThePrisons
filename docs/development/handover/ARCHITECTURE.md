# Architecture map

- `core/`: client lifecycle, services, rendering primitives, navigation and control.
- `modules/mining/ore`: Ore Macro; keep planning and execution in the established core services.
- `modules/qol/bandit` and `bandit/`: Bandit Macro and its LocalNavigator/control integration.
- `items/market` and `items/energy`: passive market analysis and Energy Extractor presentation.
- `modules/hud`, `gui/hud`, and `hud/`: session information and dashboard rendering.
- `modules/qol/items`: item identity/model selection and Classic/HD V2 resource-pack selection.
- `src/main/resources/assets/theprisons`: canonical item definitions, models and Classic textures.
- `src/main/resources/resourcepacks/theprisons_items_hd`: optional HD V2 overlay.

V4 is an external overlay build: it copies only validated texture PNGs at existing model paths.
It must not rewrite item definitions or Classic models.
