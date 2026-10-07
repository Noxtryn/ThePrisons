"""Configuration: brand constants and environment variables. The token is never printed or stored."""
import os
import pathlib
import re

BOT_DIR = pathlib.Path(__file__).resolve().parents[1]
ROOT = BOT_DIR.parent
CONTENT_DIR = BOT_DIR / "content"

REPO = "Noxtryn/ThePrisons"
REPO_URL = f"https://github.com/{REPO}"
SITE_URL = "https://noxtryn.github.io/ThePrisons/"
ISSUES_URL = f"{REPO_URL}/issues/new"
RELEASES_URL = f"{REPO_URL}/releases"
# the official logo of the mod (src/main/resources/assets/theprisons/icon.png), served from the Release branch
LOGO_URL = f"https://raw.githubusercontent.com/{REPO}/Release/src/main/resources/assets/theprisons/icon.png"
COLOR = 0x7B3FFF  # one colour for every embed
USER_AGENT = f"DiscordBot ({REPO_URL}, 1)"

CHANNELS = ["welcome", "rules", "get-started", "faq", "announcements", "changelog", "roadmap", "known-issues"]


class ConfigError(Exception):
    """A setting is missing. The message never contains a secret."""


def channel_env(name: str) -> str:
    return "DISCORD_CHANNEL_" + name.upper().replace("-", "_")


class Config:
    def __init__(self, env=None):
        self._env = os.environ if env is None else env

    @property
    def token(self) -> str:
        value = (self._env.get("DISCORD_BOT_TOKEN") or "").strip()
        if not value:
            raise ConfigError("DISCORD_BOT_TOKEN is not set (GitHub: Settings > Secrets and variables > Actions > Secrets)")
        return value

    def has_token(self) -> bool:
        return bool((self._env.get("DISCORD_BOT_TOKEN") or "").strip())

    def channel_id(self, name: str):
        value = (self._env.get(channel_env(name)) or "").strip()
        if value and not re.fullmatch(r"\d{15,25}", value):
            raise ConfigError(f"{channel_env(name)} is not a Discord channel id (digits only)")
        return value or None

    def get(self, key: str, default=None):
        return (self._env.get(key) or "").strip() or default

    def __repr__(self):  # never show the token
        return "Config(token=<hidden>)"


def redact(text, *secrets: str) -> str:
    text = str(text)
    for secret in secrets:
        if secret:
            text = text.replace(secret, "<hidden>")
    return text
