# Positioning

**Line:** ThePrisons brings the most useful Cosmic Prisons tools into one clean client experience.
**DE:** ThePrisons bringt die nützlichsten Cosmic-Prisons-Tools in ein sauberes Client-Erlebnis.

## Check against the repository (2026-10-07)

| Claim | Evidence | Verdict |
| --- | --- | --- |
| "one client" | one jar: dashboard, HUD widgets, storage overlay, Ore Macro, bandit helpers, Tunnel Vision, friends, sneak trade, player cards | true |
| "clean" | one animated dashboard for all settings, HUD editor, themes | true (design-first is the project's stated goal) |
| "most useful tools" | subjective; backed by features that read the server (session HUD with Energy/h, storage overlay, item sorter) | fine as a tagline, never as a comparison to other mods |
| market / auction house | module OFF in release builds | **do not advertise** |
| Bandit Macro | ~2 % done | mention only as "work in progress" |
| "free" | no price, no account; code All Rights Reserved | say "free download", not "open source" |
| safety / detection | automation can break server rules | **never claim**; keep the disclaimer visible |

## Audience
Cosmic Prisons players on Minecraft 1.21.11 who mine a lot and want better HUDs, a tidier inventory/vault flow and optional automation.

## Key messages
1. **What:** a free, client-side Fabric mod for Cosmic Prisons.
2. **Why:** one install replaces a pile of single-purpose mods; everything is configured in one dashboard.
3. **What it does:** session HUD (ores/s, Energy/h, XP/h), storage overlay (`/pv`), Ore Macro + item sorter, bandit helpers, Tunnel Vision, friends/gang colours.
4. **Install:** Fabric + Fabric API + the jar in `mods`.
5. **Honest:** automation can break server rules; use at your own risk.

## Sprint 01 decisions (Product Owner)
- Hero and main message lead with HUDs, dashboard, insights, customization, storage and QoL. Automation (Ore Macro, Spear Helper, Bandit Macro) appears only as an optional, clearly labelled block with the server-rules warning.
- Primary CTA: "Try ThePrisons" / "ThePrisons ausprobieren".
- Market module stays off; trailer withdrawn (`docs/media/TRAILER-OUTDATED.md`).
