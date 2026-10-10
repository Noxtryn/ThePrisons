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
    r.add_argument("--mod", choices=("theprisons", "sky-supra"))
    r.add_argument("--release-json", help="GitHub release object for offline preview")
    s = sub.add_parser("setup", help="snapshot/preview/apply a non-destructive migration")
    s.add_argument("action", choices=("snapshot", "preview", "apply"))
    s.add_argument("--snapshot")
    s.add_argument("--plan")
    s.add_argument("--approval")
    panel = sub.add_parser("panel", help="publish the persistent /mods panel after migration approval")
    panel.add_argument("--dry-run", action="store_true")
    g = sub.add_parser("register", help="register the slash commands")
    g.add_argument("--dry-run", action="store_true")
    sub.add_parser("run", help="answer slash commands (needs discord.py)")
    args = parser.parse_args(argv)
    secret = (cfg.get("DISCORD_BOT_TOKEN") or "")

    try:
        if args.cmd == "setup":
            from . import migration as M
            from pathlib import Path
            if args.action == "snapshot":
                state = M.snapshot(Discord(cfg.token, transport), cfg)
                out(json.dumps(state, ensure_ascii=False, indent=2))
            elif args.action == "preview":
                if not args.snapshot:
                    raise C.ConfigError("preview requires --snapshot (or use /setup preview)")
                state = json.loads(Path(args.snapshot).read_text(encoding="utf-8"))
                out(json.dumps(M.plan(state, cfg), ensure_ascii=False, indent=2))
            else:
                if not args.plan:
                    raise C.ConfigError("apply requires --plan and --approval")
                approved = json.loads(Path(args.plan).read_text(encoding="utf-8"))
                # Gate before even constructing the API client.
                if cfg.get("NEXORA_MIGRATION_APPROVED") != "true" or args.approval != approved.get("digest"):
                    raise C.ConfigError("Migration is not explicitly approved")
                out(json.dumps(M.apply(Discord(cfg.token, transport), cfg, approved, args.approval), ensure_ascii=False, indent=2))
            return 0
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
        if not dry:
            cfg.token  # retain clean missing-secret diagnostics before approval checks
        if not dry and args.cmd in ("publish", "register", "release", "panel") and transport is None and cfg.get("NEXORA_PUBLISH_ENABLED") != "true":
            raise C.ConfigError("Discord writes require NEXORA_PUBLISH_ENABLED=true after explicit approval")
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
        if args.cmd == "panel":
            from . import nexora as N
            if not dry and cfg.get("NEXORA_PUBLISH_ENABLED") != "true":
                raise C.ConfigError("Panel requires explicit public-publishing approval")
            channel = N.setting(cfg, "CHANNEL", "mods", not dry)
            P.sync_messages(api, channel or "0", [N.mods_message(cfg)], dry_run=dry, log=out)
            return 0
        if args.cmd == "publish":
            ctx = X.build(cfg, _published_release())
            _, failed = P.publish_all(api, cfg, ctx, args.names, dry_run=dry, log=out)
            return 1 if failed else 0
        if args.cmd == "release":
            if args.mod:
                from . import community as V
                from pathlib import Path
                data = json.loads(Path(args.release_json).read_text(encoding="utf-8")) if args.release_json else V.release_data(cfg, args.mod, "v" + args.version.lstrip("v"))
                if not data:
                    raise C.ConfigError("No published release is available for " + args.mod)
                if data.get("mod") and data["mod"] != args.mod:
                    raise C.ConfigError("Release JSON belongs to another mod")
                data = dict(data, mod=args.mod)
                if data.get("tag_name") != "v" + args.version.lstrip("v"):
                    raise C.ConfigError("Release JSON tag does not match --version")
                V.post_release(api, cfg, args.mod, data, dry_run=dry, log=out)
                return 0
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
    except (OSError, ValueError) as error:
        out(f"input/network error: {C.redact(error, secret)}")
        return 1
    return 1


def _published_release():
    """The latest published release (so the messages show what users can download), else the checkout's version."""
    return CM.latest_release() or X.release_from_repo()
