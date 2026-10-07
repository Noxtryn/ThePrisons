"""python -m prisonsbot <check|publish|release|register|run>. Exit codes: 0 ok, 1 failure, 2 configuration problem."""
import argparse
import json

from . import commands as CM
from . import config as C
from . import content as K
from . import context as X
from . import publish as P
from . import release as R
from .api import Discord, DiscordError


def main(argv, env=None, transport=None, out=print) -> int:
    cfg = C.Config(env)
    parser = argparse.ArgumentParser(prog="prisonsbot")
    sub = parser.add_subparsers(dest="cmd", required=True)
    sub.add_parser("check", help="validate all content offline")
    p = sub.add_parser("publish", help="post or update official messages")
    p.add_argument("names", nargs="*", help="welcome rules get-started faq roadmap known-issues (default: all)")
    p.add_argument("--dry-run", action="store_true")
    r = sub.add_parser("release", help="announcement + changelog of a release, then refresh the version-dependent messages")
    r.add_argument("--version", required=True)
    r.add_argument("--dry-run", action="store_true")
    g = sub.add_parser("register", help="register the slash commands")
    g.add_argument("--dry-run", action="store_true")
    sub.add_parser("run", help="answer slash commands (needs discord.py)")
    args = parser.parse_args(argv)
    secret = (cfg.get("DISCORD_BOT_TOKEN") or "")

    try:
        if args.cmd == "check":
            ctx = X.build(cfg)
            for name in ("welcome", "rules", "get-started", "faq", "roadmap", "known-issues"):
                K.render(name, ctx)
            for c in CM.COMMANDS:
                CM.handle(c["name"], "en", cfg, release=X.release_from_repo())
                CM.handle(c["name"], "de", cfg, release=X.release_from_repo())
            version = ctx["version"]
            if R.section(C.ROOT / "CHANGELOG.md", version):
                R.build(cfg, ctx, version)
            out(f"content OK (v{version})")
            return 0
        if args.cmd == "run":
            from . import runner
            return runner.run(cfg)

        dry = getattr(args, "dry_run", False)
        api = None if dry and not cfg.has_token() else Discord(cfg.token, transport)
        if args.cmd == "register":
            payload = CM.registration_payload()
            if dry:
                out(json.dumps(payload, ensure_ascii=False, indent=2))
                return 0
            app = cfg.get("DISCORD_APPLICATION_ID")
            if not app:
                raise C.ConfigError("DISCORD_APPLICATION_ID is not set")
            api.register_commands(app, cfg.get("DISCORD_GUILD_ID"), payload)
            out(f"registered {len(payload)} commands" + (" (guild)" if cfg.get("DISCORD_GUILD_ID") else " (global)"))
            return 0
        if args.cmd == "publish":
            ctx = X.build(cfg, _published_release())
            _, failed = P.publish_all(api, cfg, ctx, args.names, dry_run=dry, log=out)
            return 1 if failed else 0
        if args.cmd == "release":
            version = args.version.lstrip("v")
            ctx = X.build(cfg, X.release_from_repo(version))
            R.post_release(api, cfg, ctx, version, dry_run=dry, log=out)
            _, failed = P.publish_all(api, cfg, ctx, ["get-started", "faq"], dry_run=dry, log=out)
            return 1 if failed else 0
    except C.ConfigError as error:
        out(f"configuration: {C.redact(error, secret)}")
        return 2
    except (DiscordError, K.ContentError) as error:
        out(f"error: {C.redact(error, secret)}")
        return 1
    return 1


def _published_release():
    """The latest published release (so the messages show what users can download), else the checkout's version."""
    return CM.latest_release() or X.release_from_repo()
