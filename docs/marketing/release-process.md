# Release process

1. Update `mod_version` in `gradle.properties`, add `## [x.y.z]` to `CHANGELOG.md` **and** `CHANGELOG.de.md`.
2. Optional: `docs/media/changelog-x.y.z.gif` card for the Discord embed.
3. `scripts/check-release.sh` (CI runs it too) - changelog sections, forbidden claims, website media.
4. Open and validate the controlled PR from `dev` to `Release`; do not update `Release` directly. After checks pass and merge, create/push tag `vX.Y.Z` on the approved Release commit.
5. `release.yml`: verifies tag = `mod_version`, runs tests + build, publishes the GitHub release (jar + EN/DE notes), then posts through the official Discord bot. If the bot is unavailable, it sends no Discord message. Webhook fallback is intentionally disabled; never restore an exposed webhook secret.
6. `pages.yml` runs on `release: published`: the site redeploys; its JavaScript selects the newest published release including prereleases, links the direct jar, shows notes and marks beta releases. Verify the Pages deployment after publication.
7. `ci.yml` builds and tests every push and PR.
