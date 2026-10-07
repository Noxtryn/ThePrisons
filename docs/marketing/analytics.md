# Analytics (privacy-first)

Off by default. Nothing is loaded until `site/config.json` names a [GoatCounter](https://www.goatcounter.com/) site:
```json
{ "discord": "https://discord.gg/...", "analytics": { "goatcounter": "mycode" } }
```
No cookies, no personal profile, Do Not Track is honoured, disclosed on `site/privacy.html`.

| Metric | Source |
| --- | --- |
| Page views | GoatCounter pageviews |
| Download clicks | event `download` (hero + install section) |
| Installation interest | event `install-interest` (hero button "How to install") |
| Discord clicks | event `discord` |
| GitHub / support clicks | events `github`, `issues` |
| Real downloads | GitHub release `download_count` (shown on the site, no tracking needed) |
| Conversion rate | `download` ÷ page views (GoatCounter) and cross-check with GitHub downloads |

Funnel: visit → feature interest (scroll, `install-interest`) → download → install → first session (`/prisons`) → Discord.
The first-session step cannot be measured without telemetry in the mod; the mod sends none and that should stay so.
