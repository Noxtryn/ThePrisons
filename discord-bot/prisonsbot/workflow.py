"""GitHub Actions dispatch with typed arguments, no shell-expanded user inputs."""
import os
import shlex
from . import cli, config as C, nexora as N


def main():
    cfg = C.Config()
    action = cfg.get("ACTION", "publish")
    dry = cfg.get("DRY_RUN", "true") == "true"
    if action not in ("publish", "register", "release", "panel"):
        raise C.ConfigError("Unsupported workflow action")
    if not dry:
        if cfg.get("NEXORA_PUBLISH_ENABLED") != "true":
            raise C.ConfigError("Public Discord writes require NEXORA_PUBLISH_ENABLED=true after migration approval")
        if cfg.get("DISCORD_APPLICATION_ID") != N.APPLICATION_ID:
            raise C.ConfigError("DISCORD_APPLICATION_ID does not match Nexora Core")
        N.guild_id(cfg)
    args = [action]
    if action == "publish":
        names = shlex.split(cfg.get("NAMES", ""))
        if any(n not in ("welcome", "rules", "get-started", "faq", "roadmap", "known-issues") for n in names):
            raise C.ConfigError("Unknown content channel")
        args += names
    if action == "release":
        mod = cfg.get("MOD", "theprisons")
        N.project(mod)
        args += ["--mod", mod, "--version", cfg.get("VERSION", "")]
        if cfg.get("RELEASE_JSON"):
            args += ["--release-json", cfg.get("RELEASE_JSON")]
    if dry:
        args.append("--dry-run")
    return cli.main(args)


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except C.ConfigError as exc:
        print(C.redact(exc, os.environ.get("DISCORD_BOT_TOKEN", "")))
        raise SystemExit(2)
