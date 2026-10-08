# Legacy texture packs

Archived on 2026-10-08 during the official ThePrisons texture migration. These files are historical source only: Gradle does not package this directory and the resource-pack loader never reads it.

| Archive path | Former active path | Version / status | Restore procedure |
| --- | --- | --- | --- |
| `hd-v2-2026-10-08/` | `src/main/resources/resourcepacks/theprisons_items_hd/` | Optional “ThePrisons HD V2 Items”; 98 PNGs and 12 random-model overrides | Restore the directory to its former path, restore the retired V2 loader/config implementation from Git history, then run tests and reload resources. Do not copy individual files into the standard pack. |
| `hd-v4-staging-2026-10-08/` | `src/main/resources/resourcepacks/theprisons_items_hd_v4/` | Empty metadata-only V4 validated staging overlay | Restore only if a separately reviewed V4 pipeline is reinstated. It contains no artwork. |

The active replacement is `src/main/resources/resourcepacks/theprisons_items_standard/`. It contains exactly the 35 supplied approved paths and no model JSONs, so untouched model paths keep their existing Classic/Cosmic assets. The source archive was `ThePrisonsTextures(1).zip` (SHA-256 `145efe23df6b95ecea99962783d1251f586738382fa36e855baef7c3d1663bb7`). Its bundled manifest incorrectly listed every image as 256×256: 15 were 1254×1254 and were normalized to 256×256 on import; provenance and output hashes are in the active pack’s `manifest.json`.
