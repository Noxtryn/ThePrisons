# Website configuration

Everything the owner has to fill in lives in `site/config.json` (no code change needed):

```json
{
  "discord": "",
  "analytics": { "goatcounter": "" }
}
```

- `discord`: the public invite, e.g. `https://discord.gg/xxxxxxx`. Empty = the Discord button stays hidden and the site says
  GitHub Issues is the support channel. Nothing is invented.
- `analytics.goatcounter`: the GoatCounter site code (`https://<code>.goatcounter.com`). Empty = no tracking, no external script,
  no request. When set: page views plus the click events `download`, `install-interest`, `discord`, `github`, `issues`
  (elements with `data-track`). Cookie-free, Do Not Track respected, disclosed on `privacy.html`.

Release data (version, jar link, notes, download count) comes from the GitHub API at page load, not from this folder.
Preview: `scripts/preview-site.sh`. Deployment rules: `docs/marketing/pages-deployment.md`.
