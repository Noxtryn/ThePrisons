"""Mod-scoped release lookup, messages and private tickets."""
import json
import re
import time
import urllib.request
from pathlib import Path
from . import config as C, nexora as N, publish as P

_cache = {}


def release_data(cfg, mod, tag=None, fetch=None, now=time.time):
    N.project(mod)
    repo = C.REPO if mod == "theprisons" else cfg.get("SKY_SUPRA_REPOSITORY")
    if mod == "sky-supra" and not repo:
        source = Path(cfg.get("SKY_SUPRA_RELEASE_MANIFEST", str(C.CONTENT_DIR / "sky-supra-release.json")))
        try:
            data = json.loads(source.read_text(encoding="utf-8"))
        except (OSError, ValueError):
            raise C.ConfigError("Sky Supra release manifest is missing or invalid") from None
        if data.get("status") != "published" or not data.get("tag_name"):
            return None
        if data.get("mod") != mod:
            raise C.ConfigError("Sky Supra manifest belongs to another mod")
        if tag and data["tag_name"] != tag:
            raise C.ConfigError("Sky Supra manifest does not match the requested release")
        return dict(data, mod=mod)
    if not repo or not re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", repo):
        raise C.ConfigError("SKY_SUPRA_REPOSITORY must be configured as owner/repository")
    if tag and not re.fullmatch(r"[A-Za-z0-9_.-]{1,100}", tag):
        raise C.ConfigError("Invalid release tag")
    key = repo, mod, tag
    if key in _cache and now() - _cache[key][0] < 600:
        return _cache[key][1]
    if fetch:
        data = fetch()
    else:
        path = "tags/" + tag if tag else "latest"
        request = urllib.request.Request(f"https://api.github.com/repos/{repo}/releases/{path}", headers={"User-Agent": C.USER_AGENT, "Accept": "application/vnd.github+json"})
        with urllib.request.urlopen(request, timeout=10) as response:
            data = json.load(response)
    if data.get("draft") or not data.get("tag_name") or tag and data["tag_name"] != tag:
        raise C.ConfigError("Release is missing, draft or does not match the requested tag")
    data = {**data, "mod": mod}
    _cache[key] = now(), data
    return data


def release_messages(cfg, mod, data):
    spec = N.project(mod)
    if not data or data.get("draft") or data.get("status", "published") != "published":
        raise C.ConfigError("No published release is available for " + spec["name"])
    if data.get("mod") != mod:
        raise C.ConfigError("Release belongs to another mod")
    tag = data["tag_name"]
    if not re.fullmatch(r"[A-Za-z0-9_.-]{1,100}", tag):
        raise C.ConfigError("Invalid release tag")
    url = data.get("html_url", "")
    asset = next((a for a in data.get("assets", []) if a["name"].endswith(".jar")), None)
    links = f"[Release ansehen]({url})"
    if asset:
        links = f"[Download]({asset['browser_download_url']}) · " + links
    key = mod + "-release-" + tag
    notes = (data.get("body") or "Details stehen in den Release-Notizen.")
    def item(suffix, title, text):
        marker = key + suffix
        return {"key": marker, "payload": {"embeds": [N.embed(title, text, mod, marker, cfg)], "allowed_mentions": {"parse": []}}}
    update = item("", "✨ " + spec["name"] + " · " + tag, links)
    chunks = notes.split("\n---\n", 1)
    from . import release as R
    highlights = R.parse(chunks[0])
    fields = [{"name": label, "value": R.top(highlights["groups"][group], 3, "en")}
              for label, group in R.GROUPS["en"] if highlights["groups"].get(group)]
    if fields:
        update["payload"]["embeds"][0]["fields"] = fields
    if mod == "theprisons":
        card = C.ROOT / "docs" / "media" / f"changelog-{tag.lstrip('v')}.gif"
        if card.exists():
            update["payload"]["embeds"][0]["image"] = {"url": f"https://raw.githubusercontent.com/{C.REPO}/{tag}/docs/media/{card.name}"}
    changelog = [item("-changelog-en", "📜 " + spec["name"] + " · " + tag, chunks[0][:2800] + "\n\n" + links)]
    if len(chunks) > 1:
        changelog.append(item("-changelog-de", "📜 " + spec["name"] + " · " + tag, chunks[1][:2800] + "\n\n" + links))
    if mod == "theprisons":
        update["aliases"] = ["release-" + tag]
        for i, c in enumerate(changelog):
            c["aliases"] = ["changelog-" + tag + ("-en" if i == 0 else "-de")]
    return {"updates": [update], "downloads": [item("-download", "📥 " + spec["name"] + " · " + tag, links)], "changelog": changelog}


def post_release(api, cfg, mod, data, dry_run=False, log=print):
    messages = release_messages(cfg, mod, data)
    # Validate all destinations before sending anything; no shared-channel fallback.
    destinations = {k: N.setting(cfg, "CHANNEL", mod + "-" + k, not dry_run) for k in messages}
    if len({v for v in destinations.values() if v}) != len([v for v in destinations.values() if v]):
        raise C.ConfigError("Release destinations must be distinct")
    archived = {}
    if mod == "theprisons" and not dry_run:
        me = None
        for kind, legacy in (("updates", "announcements"), ("changelog", "changelog")):
            old_channel = cfg.channel_id(legacy)
            if old_channel and old_channel != destinations[kind]:
                from .content import key_of
                me = me or api.me()["id"]
                archived[kind] = {key_of(m) for m in api.channel_messages(old_channel) if m.get("author", {}).get("id") == me}
    for key, items in messages.items():
        if key in archived:
            kept = []
            for item in items:
                if any(alias in archived[key] for alias in item.get("aliases", [])):
                    log(f"{item['key']}: retained historical release in legacy channel")
                else:
                    kept.append(item)
            items = kept
        if not items:
            continue
        P.sync_messages(api, destinations[key] or "0", items, dry_run=dry_run, log=log)


def ticket_spec(cfg, mod, kind, requester, bot, roles):
    N.project(mod)
    if kind not in ("support", "bug", "suggest"):
        raise C.ConfigError("Invalid ticket type")
    guild = N.guild_id(cfg)
    parent = N.setting(cfg, "CHANNEL", "support-development")
    marker = f"nexora:ticket:{mod}:{kind}:{requester}"
    return {"name": f"{kind}-{mod}-{requester}", "type": 0, "parent_id": parent, "topic": marker,
            "permission_overwrites": N.overwrites(guild, roles, "ticket", bot=bot, requester=requester)}


def create_ticket(api, cfg, mod, kind, requester, store):
    guild = N.guild_id(cfg)
    role_ids = {name: N.setting(cfg, "ROLE", name.replace(" ", "-")) for name in N.STAFF}
    spec = ticket_spec(cfg, mod, kind, str(requester), api.me()["id"], role_ids)
    channels = api.request("GET", f"/guilds/{guild}/channels")
    existing = [c for c in channels if c.get("topic") == spec["topic"]]
    if len(existing) > 1:
        raise C.ConfigError("Duplicate ticket marker; staff must reconcile")
    if existing:
        # Reapply private access policy before returning an existing ticket.
        api.request("PATCH", "/channels/" + existing[0]["id"], {"permission_overwrites": spec["permission_overwrites"]})
        return existing[0]["id"]
    key = guild + ":" + spec["topic"]
    previous = store.reserve(key)
    if previous:
        raise C.ConfigError("Stored ticket no longer exists; staff must reconcile")
    result = api.request("POST", f"/guilds/{guild}/channels", spec)
    store.complete(key, result["id"])
    return result["id"]


def answer(cfg, name, mod, data=None, locale="de"):
    spec = N.project(mod)
    if name in ("support", "bug", "suggest"):
        return N.embed("🛠️ " + spec["name"] + " · " + name, "Dein privater Bereich wird für diese Mod erstellt. Beschreibe Version, Schritte und erwartetes Ergebnis.", mod, cfg=cfg)
    if not data:
        return N.embed(spec["name"], "Release-Daten sind derzeit nicht verfügbar. Bitte später erneut versuchen.", mod, cfg=cfg)
    if mod == "theprisons":
        from . import commands as CM, context as X
        result = CM.handle(name, locale, cfg, release=X.release_from_github(data))
        result["author"]["name"] = "Nexora Core · ThePrisons"
        result["color"] = spec["color"]
        return result
    return release_messages(cfg, mod, data)["downloads" if name == "download" else "changelog"][0]["payload"]["embeds"][0]
