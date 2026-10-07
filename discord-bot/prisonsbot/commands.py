"""Slash commands: definitions, the pure handlers (no Discord library needed) and the GitHub release lookup."""
import json
import time
import urllib.request

from . import config as C
from . import content as K
from . import context as X

COMMANDS = [
    {"name": "download", "description": "Get the latest ThePrisons download", "de": "Den neuesten ThePrisons-Download holen"},
    {"name": "version", "description": "Show the latest ThePrisons version and requirements", "de": "Neueste ThePrisons-Version und Voraussetzungen zeigen"},
    {"name": "roadmap", "description": "Show what we are working on", "de": "Zeigen, woran wir arbeiten"},
    {"name": "support", "description": "How to get help with ThePrisons", "de": "So bekommst du Hilfe bei ThePrisons"},
    {"name": "changelog", "description": "Show what changed in the latest version", "de": "Zeigen, was sich in der neuesten Version geändert hat"},
]


def registration_payload() -> list:
    return [{"name": c["name"], "type": 1, "description": c["description"], "description_localizations": {"de": c["de"]}} for c in COMMANDS]


_cache = {"at": 0.0, "release": None}


def latest_release(fetch=None, now=time.time, ttl=600):
    """The latest GitHub release (cached for ttl seconds), or None when GitHub cannot be reached."""
    if _cache["release"] and now() - _cache["at"] < ttl:
        return _cache["release"]
    try:
        if fetch is None:
            request = urllib.request.Request(f"https://api.github.com/repos/{C.REPO}/releases/latest",
                                             headers={"User-Agent": C.USER_AGENT, "Accept": "application/vnd.github+json"})
            with urllib.request.urlopen(request, timeout=10) as response:
                data = json.loads(response.read().decode("utf-8"))
        else:
            data = fetch()
        _cache.update(at=now(), release=X.release_from_github(data))
    except Exception:  # GitHub down, rate limited, offline: the commands fall back to links
        return _cache["release"]
    return _cache["release"]


def _lang(locale) -> str:
    return "de" if str(locale or "").lower().startswith("de") else "en"


def handle(name: str, locale, cfg: C.Config, release=None) -> dict:
    """The embed (as a dict) that answers a slash command. `release` None = look it up."""
    lang = _lang(locale)
    release = release or latest_release()
    ctx = X.build(cfg, release) if release else X.build(cfg)
    known = release is not None
    if name == "roadmap":
        return K.roadmap_embeds(K.load("roadmap"), ctx, lang)[0]
    if name == "support":
        fields = ([{"name": "Dein erster Schritt", "value": "Schau in {channel_faq} und {channel_known_issues}."},
                   {"name": "Fehler melden", "value": "[Issue auf GitHub]({issues_url}) mit Version und `logs/latest.log`."},
                   {"name": "Webseite", "value": "[ThePrisons]({site_url})"}] if lang == "de" else
                  [{"name": "Start here", "value": "Check {channel_faq} and {channel_known_issues}."},
                   {"name": "Report a bug", "value": "[Open a GitHub issue]({issues_url}) with your version and `logs/latest.log`."},
                   {"name": "Website", "value": "[ThePrisons]({site_url})"}])
        return K.make_embed(lang, "support", ctx, "Support" if lang == "en" else "Support", "", fields)
    if name == "changelog" and known and not release["body"]:
        return K.make_embed(lang, "changelog", ctx, f"Changelog v{ctx['version']}", "[Release notes]({release_url}) · [Changelog]({changelog_url})")
    if name == "changelog" and known:
        from . import release as R
        text = release["body"].split("\n---\n")[1 if lang == "de" and "\n---\n" in release["body"] else 0]
        data = R.parse(text)
        fields = [{"name": label, "value": R.top(data["groups"][key], 4, lang)} for label, key in R.GROUPS[lang] if data["groups"].get(key)]
        fields.append({"name": "Links", "value": "[Release notes]({release_url}) · [Changelog]({changelog_url})"})
        return K.make_embed(lang, "changelog", ctx, f"Changelog v{ctx['version']}", data["banner"], fields, url=ctx["release_url"])
    if not known:  # offline fallback: never guess a version
        fb = ("Aktuelle Version konnte gerade nicht abgerufen werden." if lang == "de" else "The latest version could not be looked up right now.")
        return K.make_embed(lang, name, ctx, "ThePrisons", fb + "\n[Releases]({release_url})".replace("{release_url}", C.RELEASES_URL + "/latest"))
    if name == "download":
        fields = [{"name": "Download", "value": "[{jar_name}]({download_url})"},
                  {"name": "Voraussetzungen" if lang == "de" else "Requirements",
                   "value": "Minecraft {minecraft}, Fabric Loader {loader}+, Java {java}+, Fabric API"},
                  {"name": "Installation", "value": "Jar und Fabric API in `mods`, dann `/prisons`." if lang == "de" else "Put the jar and Fabric API into `mods`, then type `/prisons`."}]
        return K.make_embed(lang, "download", ctx, f"ThePrisons v{ctx['version']}", "", fields, url=ctx["release_url"])
    if name == "version":
        fields = [{"name": "Version", "value": "v{version}" + (" · {codename}" if ctx["codename"] else ""), "inline": True},
                  {"name": "Minecraft", "value": "{minecraft}", "inline": True},
                  {"name": "Fabric Loader", "value": "{loader}+", "inline": True},
                  {"name": "Java", "value": "{java}+", "inline": True}]
        return K.make_embed(lang, "version", ctx, "ThePrisons", "[Release]({release_url})", fields, url=ctx["release_url"])
    raise KeyError(name)
