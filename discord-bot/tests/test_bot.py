import io
import json
import re
import time
import unittest
from unittest import mock
from contextlib import redirect_stderr, redirect_stdout

from prisonsbot import cli, commands, config as C, content as K, context as X, publish as P, release as R
from prisonsbot.api import Discord, DiscordError

TOKEN = "SECRET-TOKEN-do-not-print-123"
CHANNEL_IDS = {C.channel_env(n): str(100000000000000000 + i) for i, n in enumerate(C.CHANNELS)}
ENV = {"DISCORD_BOT_TOKEN": TOKEN, "DISCORD_APPLICATION_ID": "999999999999999999", **CHANNEL_IDS}


class FakeDiscord:
    """In-memory Discord: channels hold messages; records every request; checks the token only in the header."""

    def __init__(self):
        self.channels, self.requests, self.next_id, self.fail_status = {}, [], 1, None

    def __call__(self, method, url, headers, data):
        path = url.split("/api/v10", 1)[1]
        assert headers["Authorization"] == "Bot " + TOKEN
        assert TOKEN not in path and (data is None or TOKEN.encode() not in data)
        self.requests.append((method, path))
        if self.fail_status:
            return self.fail_status, json.dumps({"message": "denied", "code": 50013}).encode()
        body = json.loads(data) if data else None
        if path == "/users/@me":
            return 200, json.dumps({"id": "42"}).encode()
        m = re.fullmatch(r"/channels/(\d+)/messages(\?limit=\d+)?", path)
        if m and method == "GET":
            return 200, json.dumps(list(reversed(self.channels.get(m[1], [])))).encode()
        if m and method == "POST":
            msg = dict(body, id=str(self.next_id), author={"id": "42"})
            self.next_id += 1
            self.channels.setdefault(m[1], []).append(msg)
            return 200, json.dumps(msg).encode()
        m = re.fullmatch(r"/channels/(\d+)/messages/(\d+)", path)
        if m and method == "PATCH":
            for msg in self.channels[m[1]]:
                if msg["id"] == m[2]:
                    msg.update(body)
                    return 200, json.dumps(msg).encode()
            return 404, json.dumps({"message": "Unknown Message", "code": 10008}).encode()
        if method == "PUT" and "/commands" in path:
            return 200, json.dumps(body).encode()
        return 404, b"{}"

    def posts(self):
        return [r for r in self.requests if r[0] == "POST"]

    def edits(self):
        return [r for r in self.requests if r[0] == "PATCH"]


def setUpModule():  # no network in tests: the "published release" is the checkout's own version
    commands._cache.update(at=time.time() + 1e9, release=X.release_from_repo())


def run_cli(args, env=ENV, fake=None):
    out = io.StringIO()
    code = cli.main(args, env=env, transport=fake, out=lambda *a: print(*a, file=out))
    return code, out.getvalue()


class ContentTests(unittest.TestCase):
    def test_every_content_file_parses_and_renders(self):
        ctx = X.build(C.Config(ENV))
        for name in ("welcome", "rules", "get-started", "faq", "roadmap", "known-issues"):
            msgs = K.render(name, ctx)
            self.assertTrue(msgs, name)
            for m in msgs:
                K.validate(m["payload"])  # limits, no emoji, no forbidden claims, no open placeholders

    def test_rules_are_approved_and_cover_the_eight_topics(self):
        self.assertTrue(K.approved("rules"))
        for lang in ("en", "de"):
            self.assertEqual(len(K.load("rules")["messages"][0][lang]["fields"]), 8)

    def test_roadmap_has_no_dates(self):
        text = json.dumps(K.load("roadmap"))
        self.assertIsNone(re.search(r"20\d\d|Q[1-4]\b|January|Januar", text))
        for section in ("now", "next", "later"):
            self.assertTrue(K.load("roadmap")[section])

    def test_forbidden_claims_and_emoji_are_rejected(self):
        ctx = {}
        for bad in ("this is undetectable", "ban-proof", "guaranteed safe", "nice \U0001F680"):
            with self.assertRaises(K.ContentError):
                K.message("x", [K.make_embed("en", "x", ctx, "t", bad)])

    def test_get_started_uses_derived_versions(self):
        ctx = X.build(C.Config(ENV))
        text = json.dumps(K.render("get-started", ctx)[0]["payload"], ensure_ascii=False)
        self.assertIn("1.21.11", text)
        self.assertIn("0.17.3", text)
        self.assertIn(f"v{ctx['version']}", text)


class SecurityTests(unittest.TestCase):
    def test_missing_token_is_a_clean_configuration_error(self):
        code, out = run_cli(["publish", "faq"], env={})
        self.assertEqual(code, 2)
        self.assertIn("DISCORD_BOT_TOKEN is not set", out)

    def test_config_repr_hides_the_token(self):
        self.assertNotIn(TOKEN, repr(C.Config(ENV)))

    def test_token_never_in_output_or_errors(self):
        fake = FakeDiscord()
        fake.fail_status = 401
        code, out = run_cli(["publish", "faq"], fake=fake)
        self.assertEqual(code, 1)
        self.assertIn("401", out)
        self.assertNotIn(TOKEN, out)
        fake2 = FakeDiscord()
        code, out = run_cli(["publish", "faq"], fake=fake2)
        self.assertNotIn(TOKEN, out)

    def test_bad_channel_id_is_rejected(self):
        code, out = run_cli(["publish", "faq"], env=dict(ENV, DISCORD_CHANNEL_FAQ="not-an-id"), fake=FakeDiscord())
        self.assertEqual(code, 2)

    def test_no_secrets_in_the_repo_content(self):
        for path in C.CONTENT_DIR.glob("*.json"):
            self.assertNotRegex(path.read_text(encoding="utf-8"), r"discord(app)?\.com/api/webhooks|[MN][A-Za-z\d]{23}\.[\w-]{6}\.[\w-]{27}")


class PublishTests(unittest.TestCase):
    def test_publish_is_idempotent(self):
        fake = FakeDiscord()
        code, _ = run_cli(["publish", "welcome", "faq", "get-started", "roadmap", "known-issues"], fake=fake)
        self.assertEqual(code, 0)
        first = len(fake.posts())
        self.assertEqual(first, 5)
        code, out = run_cli(["publish", "welcome", "faq", "get-started", "roadmap", "known-issues"], fake=fake)
        self.assertEqual(code, 0)
        self.assertEqual(len(fake.posts()), first, "second run must not post again")
        self.assertEqual(len(fake.edits()), 0, "second run must not edit unchanged messages")
        self.assertEqual(out.count("unchanged"), 5)

    def test_changed_content_edits_instead_of_posting(self):
        fake = FakeDiscord()
        cfg = C.Config(ENV)
        api = Discord(TOKEN, fake)
        ctx = X.build(cfg)
        P.publish_channel(api, cfg, "faq", ctx, log=lambda *a: None)
        ctx2 = dict(ctx, minecraft="9.9.9")
        stats = P.publish_channel(api, cfg, "faq", ctx2, log=lambda *a: None)
        self.assertEqual((stats["edited"], stats["posted"]), (1, 0))
        self.assertEqual(len(fake.channels[ENV["DISCORD_CHANNEL_FAQ"]]), 1)

    def test_deleted_message_is_posted_again_once(self):
        fake = FakeDiscord()
        run_cli(["publish", "faq"], fake=fake)
        fake.channels[ENV["DISCORD_CHANNEL_FAQ"]].clear()
        run_cli(["publish", "faq"], fake=fake)
        self.assertEqual(len(fake.channels[ENV["DISCORD_CHANNEL_FAQ"]]), 1)

    def test_other_users_messages_are_ignored(self):
        fake = FakeDiscord()
        run_cli(["publish", "faq"], fake=fake)
        fake.channels[ENV["DISCORD_CHANNEL_FAQ"]][0]["author"] = {"id": "7"}  # a user pretending to be an official message
        run_cli(["publish", "faq"], fake=fake)
        self.assertEqual(len(fake.channels[ENV["DISCORD_CHANNEL_FAQ"]]), 2)

    def test_draft_content_is_not_published(self):
        fake = FakeDiscord()
        with mock.patch.object(K, "approved", return_value=False):
            code, out = run_cli(["publish", "rules"], fake=fake)
        self.assertEqual(len(fake.posts()), 0)
        self.assertIn("skipped", out)

    def test_dry_run_needs_no_token_and_posts_nothing(self):
        env = {k: v for k, v in ENV.items() if k != "DISCORD_BOT_TOKEN"}
        fake = FakeDiscord()
        code, out = run_cli(["publish", "faq", "--dry-run"], env=env, fake=fake)
        self.assertEqual(code, 0)
        self.assertEqual(fake.requests, [])

    def test_embed_payload_is_valid_json_for_discord(self):
        fake = FakeDiscord()
        run_cli(["publish", "get-started"], fake=fake)
        msg = fake.channels[ENV["DISCORD_CHANNEL_GET_STARTED"]][0]
        self.assertEqual(len(msg["embeds"]), 2)
        for e in msg["embeds"]:
            self.assertEqual(e["color"], C.COLOR)
            self.assertEqual(e["author"]["icon_url"], C.LOGO_URL)
            self.assertRegex(e["footer"]["text"], r"· get-started$")
        self.assertEqual(msg["allowed_mentions"], {"parse": []})


class ReleaseTests(unittest.TestCase):
    def test_release_posts_use_the_real_changelog_and_are_idempotent(self):
        fake = FakeDiscord()
        code, out = run_cli(["release", "--version", "1.2.1"], fake=fake)
        self.assertEqual(code, 0, out)
        ann = fake.channels[ENV["DISCORD_CHANNEL_ANNOUNCEMENTS"]]
        chg = fake.channels[ENV["DISCORD_CHANNEL_CHANGELOG"]]
        self.assertEqual(len(ann), 1)
        self.assertEqual(len(chg), 2)  # EN + DE
        self.assertIn("Item sorter", json.dumps(ann[0]["embeds"][0]))  # from CHANGELOG.md v1.2.1
        posts = len(fake.posts())
        run_cli(["release", "--version", "v1.2.1"], fake=fake)
        self.assertEqual(len(fake.posts()), posts, "re-running a release must not duplicate posts")

    def test_unknown_version_fails_clearly(self):
        code, out = run_cli(["release", "--version", "9.9.9"], fake=FakeDiscord())
        self.assertEqual(code, 2)
        self.assertIn("no section [9.9.9]", out)

    def test_parse_groups(self):
        data = R.parse("> note\n\n### Added\n\n- **A** one\n- B\n\n### Fixed\n\n- C\n")
        self.assertEqual(data["banner"], "note")
        self.assertEqual(data["groups"]["Added"], ["**A** one", "B"])

    def test_every_release_in_the_changelog_builds(self):
        ctx_cfg = C.Config(ENV)
        for version in re.findall(r"^## \[(\d+\.\d+\.\d+(?:-[0-9A-Za-z.-]+)?)\]", (C.ROOT / "CHANGELOG.md").read_text(encoding="utf-8"), re.M):
            ctx = X.build(ctx_cfg, X.release_from_repo(version))
            ann, chg = R.build(ctx_cfg, ctx, version)
            self.assertTrue(ann and chg, version)

    def test_public_beta_announcement_includes_limitations(self):
        cfg = C.Config(ENV)
        ann, chg = R.build(cfg, X.build(cfg), "1.3.0-beta.1")
        rendered = json.dumps([ann, chg], ensure_ascii=False)
        self.assertIn("COSMIC EVOLUTION", rendered)
        self.assertIn("PUBLIC BETA", rendered)
        self.assertIn("Known limitations", rendered)
        self.assertIn("Bekannte Einschränkungen", rendered)
        self.assertIn("1280×720", rendered)


class CommandTests(unittest.TestCase):
    release = X.release_from_github({
        "tag_name": "v1.2.1", "name": "ThePrisons v1.2.1 · Nebula · MC 1.21.11", "html_url": "https://github.com/Noxtryn/ThePrisons/releases/tag/v1.2.1",
        "assets": [{"name": "ThePrisons-Nebula-v1.2.1-mc1.21.11.jar", "browser_download_url": "https://github.com/x/y.jar"}],
        "body": "### Added\n\n- one\n\n---\n\n## Deutsch\n\n### Hinzugefügt\n\n- eins\n"})

    def test_registration_payload_is_valid(self):
        for c in commands.registration_payload():
            self.assertRegex(c["name"], r"^[a-z0-9_-]{1,32}$")
            self.assertTrue(1 <= len(c["description"]) <= 100 and len(c["description_localizations"]["de"]) <= 100)
        self.assertEqual({c["name"] for c in commands.registration_payload()} >= {"download", "version", "roadmap", "support"}, True)

    def test_every_command_answers_in_both_languages(self):
        cfg = C.Config(ENV)
        for c in commands.COMMANDS:
            for locale in ("en-US", "de"):
                embed = commands.handle(c["name"], locale, cfg, release=self.release)
                K.validate({"embeds": [embed]})

    def test_download_and_version_use_release_data(self):
        cfg = C.Config(ENV)
        d = json.dumps(commands.handle("download", "en", cfg, release=self.release))
        self.assertIn("https://github.com/x/y.jar", d)
        v = json.dumps(commands.handle("version", "en", cfg, release=self.release))
        self.assertIn("v1.2.1", v)
        self.assertIn("Nebula", v)

    def test_german_locale_gives_german(self):
        self.assertIn("Voraussetzungen", json.dumps(commands.handle("download", "de", C.Config(ENV), release=self.release), ensure_ascii=False))

    def test_github_down_falls_back_without_inventing_a_version(self):
        def boom():
            raise OSError("offline")
        commands._cache.update(at=0, release=None)
        self.assertIsNone(commands.latest_release(fetch=boom))
        with mock.patch.object(commands, "latest_release", return_value=None):
            for name in ("download", "version", "changelog"):
                embed = json.dumps(commands.handle(name, "en", C.Config(ENV)))
                self.assertIn("could not be looked up", embed)
                self.assertNotRegex(embed, r"v\d+\.\d+")
        commands._cache.update(at=time.time() + 1e9, release=X.release_from_repo())

    def test_register_command_sends_commands(self):
        fake = FakeDiscord()
        code, out = run_cli(["register"], env=dict(ENV, DISCORD_GUILD_ID="123456789012345678"), fake=fake)
        self.assertEqual(code, 0)
        self.assertIn(("PUT", "/applications/999999999999999999/guilds/123456789012345678/commands"), fake.requests)
        self.assertNotIn(TOKEN, out)


class RunnerTests(unittest.TestCase):
    def test_client_and_commands_build_without_connecting(self):
        try:
            import discord  # noqa: F401
        except ImportError:
            self.skipTest("discord.py is not installed (only needed to run the listener)")
        from prisonsbot import runner
        client, tree = runner.build_client(C.Config(ENV))
        self.assertEqual({c.name for c in tree.get_commands()}, {c["name"] for c in commands.COMMANDS})

    def test_run_without_token_aborts_cleanly(self):
        code, out = run_cli(["run"], env={})
        self.assertEqual(code, 2)
        self.assertIn("DISCORD_BOT_TOKEN is not set", out)


if __name__ == "__main__":
    unittest.main()
