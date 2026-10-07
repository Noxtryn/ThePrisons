"""Release posts from the real changelog: one announcement (EN + DE) and one changelog message per language."""
import re

from . import config as C
from . import content as K
from .publish import sync_messages

GROUPS = {"en": [("New", "Added"), ("Improved", "Changed"), ("Fixed", "Fixed")],
          "de": [("Neu", "Hinzugefügt"), ("Verbessert", "Geändert"), ("Behoben", "Behoben")]}
MORE = {"en": "more in the changelog", "de": "mehr im Changelog"}


def section(path, version) -> str:
    """The text of '## [version]' up to the next '## [' (same as scripts/changelog-section.sh)."""
    lines, on, out = path.read_text(encoding="utf-8").splitlines(), False, []
    for line in lines:
        if line.startswith("## ["):
            if on:
                break
            on = line.startswith(f"## [{version}]")
            continue
        if on and re.match(r"^\[[^\]]+\]: ", line):
            break
        if on:
            out.append(line)
    return "\n".join(out).strip()


def parse(text) -> dict:
    groups, current, banner = {}, None, []
    for line in text.splitlines():
        if line.startswith("### "):
            current = line[4:].strip()
        elif current and re.match(r"^\s*[-*] ", line):
            groups.setdefault(current, []).append(re.sub(r"^\s*[-*] ", "", line))
        elif line.startswith("> "):
            banner.append(line[2:])
    return {"groups": groups, "banner": " ".join(banner)}


def short(text, n):
    """Cut at a word boundary and close any markdown that the cut left open."""
    if len(text) <= n:
        return text
    cut = text[: n - 3].rsplit(" ", 1)[0].rstrip(" ,;:-(")
    if cut.count("**") % 2:
        cut += "**"
    if cut.count("`") % 2:
        cut += "`"
    return cut + "..."


def top(bullets, count, lang):
    """The first `count` bullets for the announcement, with a '+n more' line."""
    text = "\n".join("- " + short(b, 220) for b in bullets[:count])
    if len(bullets) > count:
        text += f"\n... +{len(bullets) - count} {MORE[lang]}"
    return text


def full_fields(groups, lang, budget=4800):
    """Every group in full for the changelog channel, in chunks below the 1024-character field limit."""
    fields = []
    for label, key in GROUPS[lang]:
        chunk, part = "", 0
        for b in groups.get(key, []):
            line = "- " + short(b, 600)
            if chunk and len(chunk) + len(line) + 1 > 1000:
                fields.append({"name": label if part == 0 else f"{label} (2)", "value": chunk})
                chunk, part = "", part + 1
            chunk += ("\n" if chunk else "") + line
        if chunk:
            fields.append({"name": label if part == 0 else f"{label} (2)", "value": chunk})
    kept, size = [], 0
    for f in fields:
        if size + len(f["name"]) + len(f["value"]) > budget:
            kept.append({"name": "...", "value": MORE[lang]})
            break
        kept.append(f)
        size += len(f["name"]) + len(f["value"])
    return kept


def build(cfg, ctx, version, root=None):
    """Returns (announcement messages, changelog messages) for a version; empty lists when the changelog has no section."""
    root = root or C.ROOT
    announce_embeds, changelog = [], []
    card = root / "docs" / "media" / f"changelog-{version}.gif"
    image = f"https://raw.githubusercontent.com/{C.REPO}/v{version}/docs/media/{card.name}" if card.exists() else None
    for lang, file in (("en", "CHANGELOG.md"), ("de", "CHANGELOG.de.md")):
        text = section(root / file, version)
        if not text:
            continue
        data = parse(text)
        head = f"**{ctx['codename']}** · Minecraft {ctx['minecraft']} · Fabric" if ctx["codename"] else f"Minecraft {ctx['minecraft']} · Fabric"
        if data["banner"]:
            head = data["banner"] + "\n\n" + head
        fields = [{"name": label, "value": top(data["groups"][key], 3, lang)} for label, key in GROUPS[lang] if data["groups"].get(key)]
        links = ("[Download the jar]({download_url}) · [Release notes]({release_url}) · [Full changelog]({changelog_url})" if lang == "en"
                 else "[Jar herunterladen]({download_url}) · [Release-Notizen]({release_url}) · [Voller Changelog]({changelog_url})")
        fields.append({"name": "Download", "value": links})
        title = f"ThePrisons v{version} is out" if lang == "en" else f"ThePrisons v{version} ist da"
        announce_embeds.append(K.make_embed(lang, f"release-v{version}", ctx, title, head, fields, url=ctx["release_url"], image=image if lang == "en" else None))
        key = f"changelog-v{version}-{lang}"
        changelog.append(K.message(key, [K.make_embed(
            lang, key, ctx, f"Changelog v{version}", data["banner"], full_fields(data["groups"], lang) + [{"name": "Links", "value": links}], url=ctx["release_url"])]))
    mentions = cfg.get("DISCORD_MENTIONS", "")
    announcement = []
    if announce_embeds:
        content = (mentions + "\n" if mentions else "") + f"ThePrisons v{version}"
        announcement = [K.message(f"release-v{version}", announce_embeds, content=content if mentions else None, mentions=bool(mentions))]
    return announcement, changelog


def post_release(api, cfg, ctx, version, dry_run=False, log=print):
    announcement, changelog = build(cfg, ctx, version)
    if not announcement:
        raise C.ConfigError(f"CHANGELOG.md has no section [{version}]")
    for name, messages in (("announcements", announcement), ("changelog", changelog)):
        cid = cfg.channel_id(name) or ("0" if dry_run else None)
        if not cid:
            raise C.ConfigError(f"{C.channel_env(name)} is not set")
        log(f"{name}:")
        sync_messages(api, cid, messages, dry_run=dry_run, log=log)
