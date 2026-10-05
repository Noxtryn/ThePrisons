---
name: release
description: Cut a ThePrisons release - group the commits since the last tag, propose the SemVer bump, update CHANGELOG.md and mod_version, build, make the changelog card, tag vX.Y.Z and push. Only when the user says /release.
disable-model-invocation: true
---

# /release - ThePrisons

Releases happen **only when the user says `/release`**. Everything else (intermediate work) goes to the branch `dev` after green tests, never as a tag.

## Rules (always)
- Never write the Discord webhook URL anywhere (files, commits, logs, chat). It exists only as the GitHub secret `DISCORD_WEBHOOK` (`gh secret list`; if missing tell the user to run `gh secret set DISCORD_WEBHOOK`).
- No force-push; never delete tags or branches without asking. Commit messages: `feat:`, `fix:`, `perf:`, `docs:` (`chore:` / `refactor:` / `test:` are allowed but do not reach the changelog).
- Before every push run `git diff --cached` (and `git diff origin/<branch>...HEAD` for already committed work) and make sure nothing contains `discord.com/api/webhooks`, `discordapp.com/api/webhooks`, tokens or keys: `git grep -n -E 'discord(app)?\.com/api/webhooks|ghp_|github_pat_|xox[bp]-' -- . ':!.claude/skills/release/SKILL.md'` must print nothing.
- Tell the user honestly what could not be tested.

## Steps

1. **Check the state**: `gh auth status`; `git status` (a dirty tree: ask what belongs into the release); current branch; `git describe --tags --abbrev=0 --match 'v*'` = last tag (none = first release).
2. **Read the commits** since the last tag: `git log <last-tag>..HEAD --pretty='%h %s'`. Group by prefix:
   - `feat:` -> **Added** (✨ Neu), `perf:` and improving `fix:`-less changes -> **Changed** (🔧 Verbessert), `fix:` -> **Fixed** (🐛 Behoben), `docs:` only if user-visible.
   - A `!` after the type (`feat!:`) or a `BREAKING CHANGE:` footer is a breaking change.
   - Commits without a prefix: read the diff and classify, or ask once.
3. **Propose the version** (SemVer, from the last tag): patch for fixes only, minor for any `feat:`, major for breaking changes. Show the user the proposal with the grouped notes and wait for a yes or another number.
4. **Write `CHANGELOG.md`** (Keep a Changelog, English): move the notes of `## [Unreleased]` into a new `## [X.Y.Z] - YYYY-MM-DD` with `### Added`, `### Changed`, `### Fixed` (omit empty ones); one bullet per line starting with `- ` and a short bold lead (`- **Name**: what it does`), because `scripts/changelog-section.sh` and `scripts/discord-embed.sh` parse exactly this. Update the link lines at the bottom (`[X.Y.Z]: .../releases/tag/vX.Y.Z`, `[Unreleased]: .../compare/vX.Y.Z...HEAD`).
5. **Codename**: `release_codename` in `gradle.properties` (currently `cool`) is added to the GitHub release title and the Discord embed; change it only when the user asks.
5b. **Bump `mod_version`** in `gradle.properties` to `X.Y.Z` (the jar is `build/libs/theprisons-X.Y.Z.jar`; `fabric.mod.json` takes the version from it).
6. **Test locally**: `./gradlew test build`. On failure stop, report, and do not tag. Also check `scripts/changelog-section.sh X.Y.Z` is not empty and `VERSION=X.Y.Z NOTES=<that file> DRY_RUN=1 scripts/discord-embed.sh | jq .` is valid JSON.
7. **Changelog card**: `python3 scripts/make_graphics.py changelog X.Y.Z` -> `docs/media/changelog-X.Y.Z.gif` (a generated graphic, say so - it is not a screenshot). If new raw recordings are in `docs/raw/`, run `scripts/media.sh` first.
8. **Commit and tag**: `git add CHANGELOG.md gradle.properties docs/media README.md` (only what changed), `git diff --cached` + the secret check above, commit `docs: release vX.Y.Z`, `git tag -a vX.Y.Z -m "ThePrisons vX.Y.Z"`.
9. **Push** the branch and then the tag: `git push origin <branch>` and `git push origin vX.Y.Z`. The GitHub Action `.github/workflows/release.yml` takes over (tests, build, GitHub Release with the jar, Discord embed).
10. **Watch**: `gh run watch $(gh run list --workflow release.yml --limit 1 --json databaseId -q '.[0].databaseId')`, then `gh release view vX.Y.Z` and report: release URL, jar attached yes/no, whether the Discord step succeeded (it must never fail the release).

## Intermediate work (no `/release`)
After green `./gradlew test build`: commit with a prefixed message and `git push origin dev` (create `dev` once with `git switch -c dev`). Never tag.

## Test tag
To test the workflow use `v0.0.1-test` (a pre-release; `mod_version` must be `0.0.1-test` on that commit). Afterwards ask before deleting the release and the tag (`gh release delete v0.0.1-test --cleanup-tag`).
