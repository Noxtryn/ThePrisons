"""Official message content: loads discord-bot/content/*.json, fills placeholders, builds embeds."""
import json
import re

from . import config as C

LANGS = ("en", "de")
LANG_NAME = {"en": "English", "de": "Deutsch"}
FOOTER = {"en": "ThePrisons Official", "de": "ThePrisons Offiziell"}
KEY_RE = re.compile(r"· ([a-z0-9._-]+)$")

# limits of the Discord API
MAX_TITLE, MAX_DESC, MAX_FIELDS, MAX_FIELD_NAME, MAX_FIELD_VALUE, MAX_TOTAL = 256, 4096, 25, 256, 1024, 6000

FORBIDDEN = re.compile(r"undetect|ban-?proof|guaranteed safe|100 ?% ?(safe|undetect)|nicht erkennbar|unbannbar|risk-?free|risikofrei", re.I)
EMOJI = re.compile("[\U0001F000-\U0001FAFF☀-➿⭐⭕️]")


class ContentError(Exception):
    pass


def load(name: str) -> dict:
    path = C.CONTENT_DIR / f"{name}.json"
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except (OSError, ValueError) as error:
        raise ContentError(f"{path.name}: {error}") from None


def fill(text, ctx: dict) -> str:
    """Replaces {name} placeholders that exist in ctx; unknown ones are left visible so check() catches them."""
    return re.sub(r"\{([a-z_]+)\}", lambda m: str(ctx[m.group(1)]) if m.group(1) in ctx else m.group(0), text or "").strip()


def make_embed(lang, key, ctx, title, description="", fields=None, url=None, image=None) -> dict:
    embed = {
        "author": {"name": f"ThePrisons · {LANG_NAME[lang]}", "icon_url": C.LOGO_URL},
        "title": fill(title, ctx),
        "color": C.COLOR,
        "footer": {"text": f"{FOOTER[lang]} · {key}"},
    }
    if description:
        embed["description"] = fill(description, ctx)
    if fields:
        embed["fields"] = [{"name": fill(f["name"], ctx), "value": fill(f["value"], ctx), "inline": bool(f.get("inline", False))} for f in fields]
    if url:
        embed["url"] = url
    if image:
        embed["image"] = {"url": image}
    return embed


def embed_size(embed: dict) -> int:
    size = len(embed.get("title", "")) + len(embed.get("description", "")) + len(embed.get("footer", {}).get("text", "")) + len(embed.get("author", {}).get("name", ""))
    for field in embed.get("fields", []):
        size += len(field["name"]) + len(field["value"])
    return size


def validate(payload: dict) -> None:
    embeds = payload.get("embeds", [])
    if not embeds or len(embeds) > 10:
        raise ContentError("a message needs 1-10 embeds")
    total = 0
    for e in embeds:
        if len(e.get("title", "")) > MAX_TITLE or len(e.get("description", "")) > MAX_DESC:
            raise ContentError(f"title/description too long: {e.get('title', '')[:40]}")
        if len(e.get("fields", [])) > MAX_FIELDS:
            raise ContentError("too many fields")
        for f in e.get("fields", []):
            if not f["name"] or not f["value"] or len(f["name"]) > MAX_FIELD_NAME or len(f["value"]) > MAX_FIELD_VALUE:
                raise ContentError(f"bad field: {f['name'][:40]!r}")
        total += embed_size(e)
        text = json.dumps(e, ensure_ascii=False)
        if FORBIDDEN.search(text):
            raise ContentError("forbidden safety claim in: " + e.get("title", ""))
        if EMOJI.search(text):
            raise ContentError("emoji in: " + e.get("title", ""))
        if re.search(r"\{[a-z_]+\}", text):
            raise ContentError("unresolved placeholder in: " + e.get("title", ""))
    if total > MAX_TOTAL:
        raise ContentError(f"message too long ({total} > {MAX_TOTAL})")


def message(key, embeds, content=None, mentions=False) -> dict:
    payload = {"embeds": embeds, "allowed_mentions": {"parse": ["users"] if mentions else []}}
    if content:
        payload["content"] = content
    validate(payload)
    return {"key": key, "payload": payload}


def _pair(spec, key, ctx, builder):
    return [builder(lang, key, ctx, spec) for lang in LANGS]


def render(name: str, ctx: dict) -> list:
    """The messages of one official channel: [{"key", "payload"}]. Raises for unknown names; status != approved is checked by the caller."""
    data = load(name)
    if name == "roadmap":
        return [message("roadmap", roadmap_embeds(data, ctx))]
    if name == "known-issues":
        return [message("known-issues", known_issue_embeds(data, ctx))]
    out = []
    for m in data["messages"]:
        embeds = [make_embed(lang, m["key"], ctx, m[lang]["title"], m[lang].get("description", ""), m[lang].get("fields")) for lang in LANGS]
        out.append(message(m["key"], embeds))
    return out


def roadmap_embeds(data, ctx, lang_only=None):
    embeds = []
    labels = {"en": ("Now", "Next", "Later"), "de": ("Jetzt", "Als Nächstes", "Später")}
    for lang in ([lang_only] if lang_only else LANGS):
        fields = []
        for label, section in zip(labels[lang], ("now", "next", "later")):
            items = [fill(i[lang], ctx) for i in data[section]]
            fields.append({"name": label, "value": "\n".join("- " + i for i in items)})
        embeds.append(make_embed(lang, "roadmap", ctx, data["title"][lang], data["disclaimer"][lang], fields))
    return embeds


def known_issue_embeds(data, ctx, lang_only=None):
    status = {"en": {"open": "Open", "disabled": "Switched off", "wip": "In progress"},
              "de": {"open": "Offen", "disabled": "Abgeschaltet", "wip": "In Arbeit"}}
    embeds = []
    for lang in ([lang_only] if lang_only else LANGS):
        fields = [{"name": f"{status[lang][i['status']]}: {i['title'][lang]}", "value": i["note"][lang]} for i in data["issues"]]
        desc = data["intro"][lang] + "\n" + ("Last updated: " if lang == "en" else "Zuletzt aktualisiert: ") + data["updated"]
        embeds.append(make_embed(lang, "known-issues", ctx, data["title"][lang], desc, fields))
    return embeds


def approved(name: str) -> bool:
    return load(name).get("status") == "approved"


def key_of(message_obj: dict):
    embeds = message_obj.get("embeds") or []
    match = KEY_RE.search((embeds[0].get("footer") or {}).get("text", "")) if embeds else None
    return match.group(1) if match else None


def normalize(embed: dict) -> dict:
    """The parts we set (Discord adds proxy urls etc. to what it returns)."""
    return {
        "title": embed.get("title", ""), "url": embed.get("url", ""), "description": embed.get("description", ""),
        "color": embed.get("color"), "author": (embed.get("author") or {}).get("name", ""),
        "footer": (embed.get("footer") or {}).get("text", ""), "image": (embed.get("image") or {}).get("url", ""),
        "fields": [(f["name"], f["value"], bool(f.get("inline"))) for f in embed.get("fields", [])],
    }


def same(existing: dict, payload: dict) -> bool:
    return ([normalize(e) for e in existing.get("embeds", [])] == [normalize(e) for e in payload["embeds"]]
            and (existing.get("content") or "") == (payload.get("content") or ""))
