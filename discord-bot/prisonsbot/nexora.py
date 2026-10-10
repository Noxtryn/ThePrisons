"""Dual-project definitions and validated Nexora embeds; no network side effects."""
import copy
import json
import re
from . import config as C, content as K

APPLICATION_ID = "1557515544230371338"
PROJECTS = {
    "theprisons": {"name": "ThePrisons", "color": 0xD633A6, "role": "ThePrisons"},
    "sky-supra": {"name": "Sky Supra", "color": 0x99DEFF, "role": "Sky Supra"},
}
GOLD = 0xD5AF55
GUNMETAL = 0x263238
ROLES = {"Owner": 0, "Administrator": 8, "Moderator": (1 << 13) | (1 << 40),
         "Developer": 0, "Designer": 0, "Beta Tester": 0, "ThePrisons": 0,
         "Sky Supra": 0, "Release Alerts": 0, "Member": 0}
STAFF = ("Owner", "Administrator", "Moderator", "Developer")
VIEW, SEND, HISTORY = 1 << 10, 1 << 11, 1 << 16
ACCESS = VIEW | SEND | HISTORY
BOT_PERMISSIONS = VIEW | SEND | HISTORY | (1 << 14) | (1 << 4) | (1 << 28)


def project(key):
    if key not in PROJECTS:
        raise C.ConfigError("Unknown mod; choose theprisons or sky-supra")
    return PROJECTS[key]


def setting(cfg, kind, key, required=True):
    env = "DISCORD_" + kind + "_" + key.upper().replace("-", "_")
    value = cfg.get(env)
    if value and not re.fullmatch(r"\d{15,25}", value):
        raise C.ConfigError(env + " must be a Discord snowflake")
    if required and not value:
        raise C.ConfigError(env + " is not set")
    return value


def guild_id(cfg):
    value = cfg.get("DISCORD_GUILD_ID")
    if not value or not re.fullmatch(r"\d{15,25}", value):
        raise C.ConfigError("DISCORD_GUILD_ID must be set to a Discord snowflake")
    return value


def embed(title, description="", mod=None, key="community", cfg=None, fields=None):
    spec = project(mod) if mod else {"name": "Nexora Community", "color": GUNMETAL}
    result = {"title": title[:256], "description": description[:4096], "color": spec["color"],
              "author": {"name": "Nexora Core · " + spec["name"]},
              "footer": {"text": "Nexora • Mod Community · " + key}}
    # Only configured/approved graphics; the existing official ThePrisons icon is retained.
    logo = cfg.get("NEXORA_" + (mod or "community").upper().replace("-", "_") + "_LOGO_URL") if cfg else None
    if logo or mod == "theprisons":
        result["author"]["icon_url"] = logo or C.LOGO_URL
    if fields:
        result["fields"] = fields
    check = copy.deepcopy(result)
    check = json.loads(K.EMOJI.sub("", json.dumps(check, ensure_ascii=False)))
    K.validate({"embeds": [check]})
    return result


def selection_delta(current_ids, selected, role_ids):
    selected = set(selected)
    if not selected <= PROJECTS.keys() or set(role_ids) != set(PROJECTS):
        raise C.ConfigError("Invalid mod selection or incomplete role mapping")
    if len(set(role_ids.values())) != 2:
        raise C.ConfigError("Mod roles must be distinct")
    desired = {role_ids[k] for k in selected}
    current = set(current_ids) & set(role_ids.values())
    return desired - current, current - desired


def mods_message(cfg):
    """Public panel payload; stable component IDs are registered by setup_hook."""
    return {"key": "nexora-mods", "payload": {"embeds": [embed("🎮 Deine Mods", "ThePrisons, Sky Supra oder beide: Wähle deine Bereiche. Du kannst die Auswahl jederzeit ändern.", key="nexora-mods", cfg=cfg)],
            "allowed_mentions": {"parse": []}, "components": [
                {"type": 1, "components": [{"type": 3, "custom_id": "nexora:v2:mods:select", "min_values": 0, "max_values": 2,
                    "placeholder": "Deine Mods auswählen", "options": [{"label": project(k)["name"], "value": k} for k in PROJECTS]}]},
                {"type": 1, "components": [{"type": 2, "style": 2, "label": label, "custom_id": "nexora:v2:mods:" + k}
                    for k, label in (("theprisons", "ThePrisons"), ("sky-supra", "Sky Supra"), ("none", "Keine Mods"))]}]}}


def overwrites(guild, role_ids, audience="common", readonly=False, bot=None, requester=None):
    base_allow = VIEW | HISTORY | (0 if readonly else SEND)
    result = [{"id": guild, "type": 0, "allow": str(base_allow if audience == "common" else 0),
               "deny": str(SEND if audience == "common" and readonly else VIEW if audience != "common" else 0)}]
    allowed = [PROJECTS[audience]["role"]] if audience in PROJECTS else []
    if audience != "common" or readonly:
        allowed += list(STAFF)
    for name in dict.fromkeys(allowed):
        result.append({"id": role_ids[name], "type": 0,
                       "allow": str(base_allow if name not in STAFF else ACCESS), "deny": str(SEND if readonly and name not in STAFF else 0)})
    for member in (bot, requester):
        if member:
            result.append({"id": str(member), "type": 1, "allow": str(ACCESS | (1 << 14)), "deny": "0"})
    return result


def effective_permissions(guild, roles, member_roles, overrides, member_id=None, owner=False):
    """Discord's base -> everyone -> aggregated roles -> member overwrite order."""
    p = int(roles.get(guild, 0))
    for role in member_roles:
        p |= int(roles.get(role, 0))
    if owner or p & 8:
        return (1 << 53) - 1
    for o in overrides:
        if o["type"] == 0 and o["id"] == guild:
            p = (p & ~int(o["deny"])) | int(o["allow"])
    deny = allow = 0
    for o in overrides:
        if o["type"] == 0 and o["id"] != guild and o["id"] in member_roles:
            deny |= int(o["deny"])
            allow |= int(o["allow"])
    p = (p & ~deny) | allow
    for o in overrides:
        if o["type"] == 1 and o["id"] == member_id:
            p = (p & ~int(o["deny"])) | int(o["allow"])
    return p


def channels():
    result = []
    def add(key, name, parent, audience="common", readonly=False, aliases=()):
        result.append(dict(key=key, name=name, parent=parent, audience=audience, readonly=readonly, aliases=list(aliases)))
    for category in ("START HERE", "COMMUNITY", "THEPRISONS", "SKY SUPRA", "SUPPORT & DEVELOPMENT", "STAFF ONLY"):
        add(category.lower().replace(" & ", "-").replace(" ", "-"), category, None,
            "theprisons" if category == "THEPRISONS" else "sky-supra" if category == "SKY SUPRA" else "staff" if category == "STAFF ONLY" else "common")
    for key in ("welcome", "rules", "get-started", "mods", "faq"):
        add(key, "📌・" + key, "start-here", readonly=True, aliases=(key,))
    add("general", "💬・general", "community", aliases=("general",))
    for mod in PROJECTS:
        for key, icon in (("updates", "📣"), ("downloads", "📥"), ("changelog", "📜"), ("discussion", "💬"), ("suggestions", "💡"), ("bugreports", "🐛")):
            legacy = ("announcements",) if key == "updates" else ("changelog",) if key == "changelog" else ()
            add(mod + "-" + key, icon + "・" + key, mod, mod, key in ("updates", "downloads", "changelog"), legacy if mod == "theprisons" else ())
    for key in ("support", "roadmap", "known-issues"):
        add(key, "🛠️・" + key, "support-development", readonly=key != "support", aliases=(key,))
    add("staff-chat", "🔒・staff-chat", "staff-only", "staff")
    return result
