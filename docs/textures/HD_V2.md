# HD V2 item textures (optional, built in)

GUI -> Items -> Item Textures -> **Texture pack: Classic | HD V2**. HD V2 is a second built-in resource pack (`resourcepacks/theprisons_items_hd`, id
`theprisons_items_hd`, shown as "ThePrisons HD V2 Items") that is appended after the base mod resources (and before `theprisons_look`, which stays last).
It only replaces PNGs at the SAME paths as the classic textures; there are no extra model files. Everything the pack does not contain keeps its classic
texture - that is intended: the export is partial.

`Texture source` (Cosmic Textures first / ThePrisons first / Off) is untouched: it chooses which MODEL wins, HD V2 chooses which PNG sits behind the
ThePrisons models.

## Contents (V2.1: 98 textures, 256x256, transparent; 12 small model overrides)
XP Bottle, Book (6 tiers) + one canonical Random Book, Revealed Book, Clue Scroll, Randomization Scroll, Dust, Secret Dust, Enchant Orb, Page, Upgrade,
Booster (energy / gp / xp), Powerups (4 families x base + 6 tiers), Prestige Token levels 1-5, one canonical Random Contraband.

**Random Book / Random Contraband:** the six technical ids of each (`random_simple` ... `random_godly`) are ONE neutral picture. The pack therefore holds
`book/random.png` and `contraband/random.png` and twelve tiny model files (`models/item/prisons/{book,contraband}/random_<tier>.json`) that point the six ids
of a family at it. They replace the classic model files at the same path while HD V2 is on and disappear with it. Nothing else is overridden: no item
definitions, no other models.

## Not in the pack (stay classic)
Rejected or unverified in the V2.1 review (`HD_V2_QUALITY_REVIEW.csv`): **Charge Orb** (old progression rejected), **Masks** (generated art was not the real
identity), **Pets**, **Satchels**, **Reroll**, **Spear Orb**, **Random Shard**, the normal Contraband tiers, Prestige 6-10, random variants of revealed books,
scrolls, dust, orbs, pages and upgrades.

## Import gate
`HD_V2_VISUAL_ASSET_MAP.csv` lists every technical id with its status. Only `VERIFIED_MAPPING` rows may be in the pack; every `NEEDS_REFERENCE`,
`NEEDS_REDESIGN` and `REJECTED_CURRENT_EXPORT` row must be absent. `HdPackAssetsTest.theVisualAssetMapIsTheImportGate` enforces it, so a rejected asset cannot
slip back in with a later export. Future imports are mapped by what they show, not by file name or count.

## Notes
* The export names powerups `powerup/<family>/<tier>.png`; the models use `powerup/<family>_<tier>.png` and `powerup/<family>.png` for the base: the bundled
  copy uses the model names (otherwise those 28 textures would never be shown).
* Memory: 98 textures of 256x256 are about 26 MB of RGBA in the item atlas (plus mipmaps) while HD V2 is on.
* Lifecycle: the setting is read from the config at start; `HdPackSync` reloads the resources at most once, and only when the chosen state differs from
  the state the pack list was last built with (so loading the config, listing the packs or reloading never loops).
