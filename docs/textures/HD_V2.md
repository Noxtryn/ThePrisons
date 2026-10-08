# HD V2 item textures (optional, built in)

GUI -> Items -> Item Textures -> **Texture pack: Classic | HD V2**. HD V2 is a second built-in resource pack (`resourcepacks/theprisons_items_hd`, id
`theprisons_items_hd`, shown as "ThePrisons HD V2 Items") that is appended after the base mod resources (and before `theprisons_look`, which stays last).
It only replaces PNGs at the SAME paths as the classic textures; there are no extra model files. Everything the pack does not contain keeps its classic
texture - that is intended: the export is partial.

`Texture source` (Cosmic Textures first / ThePrisons first / Off) is untouched: it chooses which MODEL wins, HD V2 chooses which PNG sits behind the
ThePrisons models.

## Contents (122 textures, 256x256, transparent)
XP Bottle, Book (+ revealed, + random), Clue Scroll, Randomization Scroll, Dust, Secret Dust, Enchant Orb, Page, Upgrade, Charge Orb (4 stages), Booster
(energy / gp / xp), Powerups (4 families x base + 6 tiers), Prestige Token levels 1-5, Masks (10 of 15: anonymous, clue, elite, godly, legendary, leprechaun,
nitro, outpost, prisoner, sentinel), Random Contraband (one neutral image for all six tiers).

## Not in the pack (stay classic) - from the quality review
Pets, Satchels, Reroll, Spear Orb, Executive Shard, Random Shard; Prestige 6-10; Masks simple / turkey / ultimate / uncommon / valor.

## Notes
* The export names powerups `powerup/<family>/<tier>.png`; the models use `powerup/<family>_<tier>.png` and `powerup/<family>.png` for the base: the bundled
  copy uses the model names (otherwise those 28 textures would never be shown).
* `Random Contraband` is not in the quality review (neither included nor skipped); it is the padlocked black chest, one image for all six tiers. Delete
  `contraband/random_*.png` from the pack to keep the classic art.
* Memory: 122 textures of 256x256 are about 32 MB of RGBA in the item atlas (plus mipmaps) while HD V2 is on.
* Lifecycle: the setting is read from the config at start; `HdPackSync` reloads the resources at most once, and only when the chosen state differs from
  the state the pack list was last built with (so loading the config, listing the packs or reloading never loops).
