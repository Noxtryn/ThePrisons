# Nexora Core V2

Application: `1557515544230371338`. This integration is prepared on `dev`; deployment and Discord writes require the owner's migration approval.

## Commands and roles

`/mods` offers a multi-select menu and toggle buttons for ThePrisons and Sky Supra. Neither, either or both may be selected. Existing Discord roles are the authoritative state, including roles assigned by native Onboarding. Only the two mod roles are changed; no member, staff or alert roles are removed.

`/download mod`, `/changelog mod`, `/support mod`, `/bug mod`, `/suggest mod`, `/status`, `/setup preview`, `/setup apply approval` are available. Existing `/version` and `/roadmap` remain. Every command response is ephemeral. `/setup` also checks Manage Server at runtime.

`python -m prisonsbot panel --dry-run` previews the public selection panel. After approval, `panel` edits/posts this panel once in `DISCORD_CHANNEL_MODS`. Its buttons and menu have stable custom IDs, timeout=None and startup registration. An ephemeral `/mods` response is temporary; the public panel survives bot restarts.

Normal members see shared channels. ThePrisons and Sky Supra roles have zero global permissions and expose only their corresponding channels. Moderator, Developer, Administrator and Owner roles can access both mod areas and support tickets. Designer and Beta Tester get no automatic private ticket access. Release Alerts is separate from mod subscriptions and is not automatically pinged. Administrator and the Discord server owner inherently bypass channel restrictions.

## Safe migration

1. Configure `DISCORD_GUILD_ID` and known channel IDs from `server-config.example.json`. Supply existing role IDs using `DISCORD_ROLE_OWNER`, `DISCORD_ROLE_ADMINISTRATOR`, `DISCORD_ROLE_MODERATOR`, `DISCORD_ROLE_DEVELOPER`, `DISCORD_ROLE_DESIGNER`, `DISCORD_ROLE_BETA_TESTER`, `DISCORD_ROLE_THEPRISONS`, `DISCORD_ROLE_SKY_SUPRA`, `DISCORD_ROLE_RELEASE_ALERTS`, `DISCORD_ROLE_MEMBER` when names are ambiguous. The example is documentation; it is not automatically loaded.
2. Run `python -m prisonsbot setup snapshot` with a token held only in the process environment; redirect its JSON output to a local snapshot file. This makes GET requests only, including Onboarding and the bot's permissions.
3. Run `python -m prisonsbot setup preview --snapshot snapshot.json > plan.json`, or use `/setup preview`. Preview has no mutation calls. The plan lists existing IDs, before/after access overwrites, six audience checks, blocking findings, onboarding role choices and a SHA256 digest.
4. Review matches and the full overwrite replacement on each targeted channel. Explicit IDs win. Ambiguous names, wrong types, duplicated mappings, invalid guilds, bot Administrator permission and failed access checks stop apply. Unmatched channels are untouched; old announcements/changelog channels with separate new mod destinations stay in place.
5. Only after explicit owner approval, set `NEXORA_MIGRATION_APPROVED=true` and run `python -m prisonsbot setup apply --plan plan.json --approval <digest>` or `/setup apply approval:<digest>`. A fresh snapshot must reproduce the approved plan exactly. No DELETE operations or message changes occur. A durable operation reservation prevents concurrent/uncertain replay, and apply verifies the result afterward.
6. Save the returned IDs into deployment/GitHub configuration. Confirm native Onboarding selects those exact existing mod roles, with a multi-select optional question. The integration exports the choices and preserves native Onboarding; it does not overwrite existing onboarding prompts. Onboarding availability and its server prerequisites must be checked in the live server.
7. After reviewing the migration, enable `NEXORA_COMMUNITY_ENABLED=true` for role selections and private ticket creation. Publishing the public panel, release posts and registering commands are separate reviewed deployment operations. Enable `NEXORA_PUBLISH_ENABLED=true` only when public publishing is approved.

An interrupted apply is not rolled back by deleting objects. Generate a fresh snapshot to reconcile partial state. Pending SQLite operations deliberately fail closed; staff must verify Discord objects before resolving a pending entry. Keep `NEXORA_STATE_DB` (default `state/nexora.sqlite3`) on durable storage. Run one gateway instance per guild; local SQLite coordinates processes sharing this file, not separate hosts.

## Bot rights and setup blockers

Use non-privileged GUILDS intent only. No member-list or message-content privileged intent is required. Bot rights: View Channels, Send Messages, Embed Links, Read Message History, Manage Channels (private tickets/setup), Manage Roles (subscriptions/setup). Do not grant Administrator. Put the bot above both mod roles, below privileged staff roles.

The bot cannot create a role with permissions it does not possess. If Administrator or Moderator is absent, the owner must create/configure that staff role before approving migration; this is surfaced as a blocker. Staff permissions on existing roles are preserved. The planner evaluates their actual permissions with Member and @everyone; inconsistent visibility fails approval.

Invite (normal bot install, no RPC or authorization-code redirect):

`https://discord.com/oauth2/authorize?client_id=1557515544230371338&scope=bot%20applications.commands&permissions=268520464`

## Releases without duplicates

`python -m prisonsbot release --mod theprisons --version vX.Y.Z --dry-run` reads the published GitHub release and previews the three destinations. ThePrisons and Sky Supra use different marker namespaces, caches and channel variables. All destinations are validated before posting, role/user mentions are disabled, and full channel history is searched for bot-owned markers. Existing legacy ThePrisons posts are edited in place when they are in the target, or retained in the former channel instead of reposted into a new channel. Deleted messages may be recreated; user-authored lookalikes are ignored.

Both release and manual Actions jobs share `nexora-discord` concurrency with cancel-in-progress=false. Deterministic enforced nonces protect recent duplicate sends. Uncertain POSTs are not retried blindly. External callers must use the same serialized deployment route; independent bots/hosts outside that route cannot be guaranteed exclusive publication.

Sky Supra has no repository yet. `content/sky-supra-release.json` therefore starts `unreleased`, with no guessed version or download. Supply a reviewed published manifest via `SKY_SUPRA_RELEASE_MANIFEST`, or later configure `SKY_SUPRA_REPOSITORY`. A manifest uses `status: published`, `mod: sky-supra`, `tag_name`, `html_url`, `body`, and `assets` containing `name` / `browser_download_url`. For an offline review: `release --mod sky-supra --version X.Y.Z --release-json approved-release.json --dry-run`. Never place tokens in the manifest.

## GitHub configuration

Secret: `DISCORD_BOT_TOKEN` only. Variables: `DISCORD_APPLICATION_ID`, `DISCORD_GUILD_ID`, `DISCORD_CHANNEL_THEPRISONS_UPDATES`, `DISCORD_CHANNEL_THEPRISONS_DOWNLOADS`, `DISCORD_CHANNEL_THEPRISONS_CHANGELOG`, `DISCORD_CHANNEL_SKY_SUPRA_UPDATES`, `DISCORD_CHANNEL_SKY_SUPRA_DOWNLOADS`, `DISCORD_CHANNEL_SKY_SUPRA_CHANGELOG`, existing eight content channel variables, and `DISCORD_CHANNEL_MODS`. The live runner also needs `DISCORD_CHANNEL_SUPPORT_DEVELOPMENT` and the staff/mod role variables above.

Manual Actions default to dry-run and run only on dev. Automatic release posts are disabled unless `NEXORA_PUBLISH_ENABLED` is exactly `true`. Configure required reviewers on the `nexora-discord` GitHub environment before enabling it. Merely naming an environment does not create an approval policy. Runtime flags `NEXORA_MIGRATION_APPROVED` and `NEXORA_COMMUNITY_ENABLED` are intentionally not supplied by publishing workflows.

CI installs discord.py and runs the full bot suite without a live connection. Java/mod build stages are unchanged. User inputs are passed as structured Python argument lists, never interpolated into shell commands.

## Design and graphics

ThePrisons uses magenta, Sky Supra ice blue, shared community embeds gunmetal. Gold accents are reserved for approved graphic assets (Discord embeds support one accent color). Existing official ThePrisons icon/release graphics and approved bilingual content remain. Configure `NEXORA_THEPRISONS_LOGO_URL`, `NEXORA_SKY_SUPRA_LOGO_URL`, `NEXORA_COMMUNITY_LOGO_URL` only with approved HTTPS images. No unapproved Sky Supra graphic is invented. Emoji are confined to the new Nexora components and validated embeds; the legacy content validator and its safety checks remain intact.

## Verification

Windows hosting and secure local credential entry: [hosting/windows/README.md](hosting/windows/README.md). The supervisor is independent of Codex and does not enable Discord mutation gates.

From discord-bot: `python -m pip install -r requirements.txt`, `python -m unittest discover -s tests -t .`, `python -m prisonsbot check`. Tests cover the original bot, role selections, permission precedence/matrix, ticket privacy/deduplication, mod release routing/cache, missing configuration, persistent controls and non-destructive/idempotent migration with simulated REST responses. Live Discord permissions and actual Onboarding cannot be certified without a real read-only snapshot.
