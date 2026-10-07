# Release process

1. Update `mod_version` in `gradle.properties`, add `## [x.y.z]` to `CHANGELOG.md` **and** `CHANGELOG.de.md`.
2. Optional: `docs/media/changelog-x.y.z.gif` card for the Discord embed.
3. `scripts/check-release.sh` (CI runs it too) - changelog sections, forbidden claims, website media.
4. Merge to `Release`, tag `vX.Y.Z`, push the tag.
5. `release.yml`: verifies tag = `mod_version`, runs tests + build, publishes the GitHub release (jar + EN/DE notes), posts the Discord embed (`[no-discord]` in the tagged commit skips it).
6. `pages.yml` runs on `release: published`: the site redeploys; its JavaScript reads the latest release (version, direct jar link, notes, download count) from GitHub, so no site edit is needed.
7. `ci.yml` builds and tests every push and PR.
