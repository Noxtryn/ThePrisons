# ThePrisons Discord bot

Posts and updates the **official** messages of the ThePrisons Discord, announces releases, and answers a few slash commands.
It is not a moderation bot (AutoMod and the ticket/support system stay separate).

## Stack (why)

Python 3.12. The repository already uses Python for its tooling (`scripts/*.py`); no Node build exists. Publishing, release posts,
command registration and the tests use **only the standard library** (nothing to install in CI). Only the always-on slash-command
listener needs one dependency, `discord.py` (`requirements.txt`).

```
discord-bot/
  content/          official texts (JSON, EN + DE, no secrets)
  prisonsbot/       the bot (config, api, content, publish, release, commands, runner, cli)
  tests/            unit tests with a fake Discord (python3 -m unittest discover -s tests -t .)
```

## Content

`content/*.json`: `welcome`, `rules`, `get-started`, `faq`, `roadmap`, `known-issues`. Edit the text, merge it to `Release`,
then run the "Discord" workflow by hand (publishing is manual-only for now) and the existing messages are edited in place. Every message has an English and a German embed.
Placeholders such as `{version}`, `{minecraft}`, `{loader}`, `{java}`, `{download_url}`, `{jar_name}`, `{channel_faq}` come from
`gradle.properties`, `fabric.mod.json` and the latest GitHub release, so versions are not hard-coded.

- `rules.json` is approved (8 rules, EN/DE). Any file with `"status": "draft"` is skipped by the publisher until it is set to `"approved"`.
- `known-issues.json` is edited by hand (`issues[]`, status `open` / `disabled` / `wip`, plus `updated`). Nothing is read from developer notes.
- `roadmap.json`: Now / Next / Later, no dates, with a disclaimer.

Rules for every text (checked by `python3 -m prisonsbot check` and in CI): fits Discord's limits, no emoji, no safety or
"undetectable" claims, no unresolved placeholders.

## Update strategy (no duplicates, no state file)

Each official message carries a key in its footer (`ThePrisons Official · get-started`). On every run the bot reads the last 100
messages of the channel, keeps those **authored by itself** (checked against `GET /users/@me`), finds the message by key and
- unchanged: does nothing, - changed: edits it, - missing (deleted, new): posts it.
Release posts use per-release keys (`release-v1.2.1`, `changelog-v1.2.1-en/-de`), so re-running a release workflow edits instead of
duplicating. Order: a message that is new is posted at the bottom; to reorder, delete the bot's messages in that channel and publish again.
Only the bot's own messages are ever touched; it deletes nothing.

## Configuration

Secret (GitHub: Settings > Secrets and variables > Actions > **Secrets**):

| Name | What |
| --- | --- |
| `DISCORD_BOT_TOKEN` | the bot token. Never commit, never log. Missing = the bot stops with a clear message (exit 2) |

Variables (**Variables** tab; ids are not secret): `DISCORD_CHANNEL_WELCOME`, `_RULES`, `_GET_STARTED`, `_FAQ`,
`_ANNOUNCEMENTS`, `_CHANGELOG`, `_ROADMAP`, `_KNOWN_ISSUES`, `DISCORD_APPLICATION_ID`, optional `DISCORD_GUILD_ID`
(commands appear instantly in that server; without it they are global and can take up to an hour), optional `DISCORD_MENTIONS`
(e.g. `<@123>` mentioned in release announcements).

## Bot setup (owner, one time)

1. discord.com/developers/applications > New Application "ThePrisons", set the **official logo** as the app icon (`src/main/resources/assets/theprisons/icon.png`).
2. Bot tab > Reset Token > store it as the secret `DISCORD_BOT_TOKEN`. No privileged intents are needed.
3. Invite with scopes `bot applications.commands` and permissions **84992** = View Channels + Send Messages + Embed Links +
   Read Message History (`https://discord.com/oauth2/authorize?client_id=<APPLICATION_ID>&scope=bot%20applications.commands&permissions=84992`).
   Not Administrator. Manage Messages is **not** needed (a bot can edit its own messages). "Use Application Commands" is a permission of
   the members: leave it on for @everyone in the channels where the commands should work.
4. Give the bot access only to the official channels (view + send + embed + history), in the others it needs nothing.
5. Create the variables above (Developer Mode > right click a channel > Copy Channel ID).

## First live publish (manual only)

`discord.yml` has **no automatic trigger**: merging into `Release` posts nothing. Release posts on a new tag (`release.yml`) stay as they are.

1. Merge the PR into `Release`.
2. Verify CI and the Website (Pages) workflow are green.
3. Actions > **Discord** > Run workflow on `Release`: leave `dry_run` ticked first (it shows what would be posted, sends nothing), then run again with `dry_run` unticked. `register_commands` stays off until the commands should exist.
4. Watch the log: every channel prints `posted`, later runs print `unchanged` or `edited`. The token is never printed.
5. Check every official channel in Discord (EN + DE embeds, links, channel mentions).
6. Only after that may automatic publishing be switched on again: the commented `push:` trigger is in `discord.yml`.

## Commands

```
python3 -m prisonsbot check                  # validate content offline
python3 -m prisonsbot publish [names] [--dry-run]
python3 -m prisonsbot release --version 1.2.1 [--dry-run]
python3 -m prisonsbot register [--dry-run]   # slash commands (PUT = replaces the list)
python3 -m prisonsbot run                    # answer slash commands (needs discord.py)
```

Slash commands: `/download`, `/version`, `/roadmap`, `/support`, `/changelog`. They answer in German for German Discord clients,
English otherwise, from the latest GitHub release (cached 10 minutes). If GitHub cannot be reached they say so and link the
releases page; they never invent a version.

## Hosting the slash commands (owner decision)

Posting and release announcements run in GitHub Actions and need no server. Slash commands need `python3 -m prisonsbot run`
**running somewhere** (a small VPS, a home server, a container: `pip install -r requirements.txt`, set `DISCORD_BOT_TOKEN`
and `DISCORD_CHANNEL_*`). GitHub Actions cannot host it. Until then the commands are registered but do not answer.

## Release integration

`release.yml`: after the GitHub release, if the secret exists, the bot posts the announcement (EN + DE embeds: New / Improved / Fixed
(top 3 each), Download, Changelog) to `announcements`, the full changelog (one message per language) to `changelog`, then refreshes
`get-started` and `faq` (they show the current version). The text comes from `CHANGELOG.md` / `CHANGELOG.de.md`, the links from the tag.
Without the secret the previous webhook embed (`scripts/discord-embed.sh`, secret `DISCORD_WEBHOOK`) is used instead; never both.
A Discord problem cannot fail the release; `[no-discord]` in the tagged commit skips Discord entirely.
