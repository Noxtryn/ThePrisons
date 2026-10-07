# Recordings needed

Real in-game screenshots and clips cannot be taken by the tooling - please put them into this folder (`docs/raw/`).
Then run `scripts/media.sh`: images are cropped to 16:9 and saved as WebP + PNG, clips become GIFs in `docs/media/`.
Market screens (auction house, `/ee`, shops, item list) are NOT needed: that module is off in release builds.
The names below are the ones the README placeholders expect.

| File (any of png / jpg / webp / mp4 / mkv / webm / mov) | What to show |
| --- | --- |
| `dashboard` | The dashboard: Overview, then the Mining tab (sub-tabs) and the Bandits tab. A short clip switching tabs is ideal |
| `ore-macro-hud` | The session HUD while the Ore Macro mines (state, ores/s, Energy/h and XP/h with the average underneath) |
| `item-sorter` | An item sorter trip: spawn, vault, back to the mine (clip, 10-20 s) |
| `guard-zones` | The guarded zone and border marks (with the pathfinder line on) |
| `tunnel-vision` | Tunnel Vision: the iris transition and the rainbow road (clip) |
| `spear-helper` | The shooter crosshair, the sight point and the line bar with bandits |

Tips: GUI scale 2 or 3, no private information (chat names, balance) in the picture, 1920x1080 or similar.
Until a file exists, the README only has a placeholder comment for it - nothing is faked.
