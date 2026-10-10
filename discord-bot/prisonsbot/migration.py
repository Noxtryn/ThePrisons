"""Read-only snapshots, deterministic plans and explicitly approved non-destructive apply."""
import hashlib
import json
from . import config as C, nexora as N


def digest(value):
    return hashlib.sha256(json.dumps(value, sort_keys=True, separators=(",", ":")).encode()).hexdigest()


def snapshot(api, cfg):
    guild = N.guild_id(cfg)
    bot = api.me()
    if bot["id"] != cfg.get("DISCORD_APPLICATION_ID", N.APPLICATION_ID):
        raise C.ConfigError("Bot token belongs to another Discord application")
    return {"guild_id": guild, "guild": api.request("GET", f"/guilds/{guild}"),
            "roles": api.request("GET", f"/guilds/{guild}/roles"),
            "channels": api.request("GET", f"/guilds/{guild}/channels"),
            "bot": bot, "bot_member": api.request("GET", f"/guilds/{guild}/members/{bot['id']}"),
            "onboarding": api.request("GET", f"/guilds/{guild}/onboarding")}


def _resolve(items, name, explicit, aliases=(), expected_type=None):
    candidates = [i for i in items if i["id"] == explicit] if explicit else [i for i in items if i["name"] in (name, *aliases)]
    if explicit and not candidates:
        raise C.ConfigError("Configured ID does not exist: " + name)
    if len(candidates) > 1:
        raise C.ConfigError("Ambiguous existing object; configure ID for " + name)
    found = candidates[0] if candidates else None
    if found and expected_type is not None and found["type"] != expected_type:
        raise C.ConfigError("Wrong channel type: " + name)
    return found


def plan(state, cfg):
    if state["guild_id"] != N.guild_id(cfg):
        raise C.ConfigError("Snapshot belongs to another guild")
    roles, changes, warnings = {}, [], []
    bot = str(state["bot"]["id"])
    for name, permissions in N.ROLES.items():
        found = _resolve(state["roles"], name, N.setting(cfg, "ROLE", name.replace(" ", "-"), False))
        roles[name] = found["id"] if found else "role:" + name
        if not found:
            changes.append({"kind": "role", "key": name, "id": None,
                            "body": {"name": name, "permissions": str(permissions), "color": N.PROJECTS.get("theprisons" if name == "ThePrisons" else "sky-supra" if name == "Sky Supra" else "", {}).get("color", N.GOLD)}})
        elif found.get("managed") and name in ("ThePrisons", "Sky Supra"):
            raise C.ConfigError("Mod role is integration-managed: " + name)
        elif name in ("ThePrisons", "Sky Supra") and int(found["permissions"]) != 0:
            changes.append({"kind": "role", "key": name, "id": found["id"], "body": {"permissions": "0"}})
    if len(set(roles.values())) != len(roles):
        raise C.ConfigError("Role mappings must be distinct")
    targets = {}
    for spec in N.channels():
        explicit = N.setting(cfg, "CHANNEL", spec["key"], False)
        if not explicit:
            explicit = next((cfg.channel_id(a) for a in spec["aliases"] if cfg.channel_id(a)), None)
        pool = state["channels"] if explicit or not spec["parent"] else [c for c in state["channels"] if c["name"] in spec["aliases"] or c.get("parent_id") == targets[spec["parent"]]]
        found = _resolve(pool, spec["name"], explicit, spec["aliases"], 0 if spec["parent"] else 4)
        key = spec["key"]
        targets[key] = found["id"] if found else "channel:" + key
        body = {"name": spec["name"], "type": 0 if spec["parent"] else 4,
                "permission_overwrites": N.overwrites(state["guild_id"], roles, spec["audience"], spec["readonly"], bot=bot)}
        if spec["parent"]:
            body["parent_id"] = targets[spec["parent"]]
        if found:
            # Replace access policies only on explicitly matched targets. Other channels are untouched.
            body.pop("type")
            old = {k: found.get(k) for k in body}
            old["permission_overwrites"] = sorted(old.get("permission_overwrites") or [], key=lambda o: (o["type"], o["id"]))
            body["permission_overwrites"] = sorted(body["permission_overwrites"], key=lambda o: (o["type"], o["id"]))
            if old == body:
                continue
        changes.append({"kind": "channel", "key": key, "id": found["id"] if found else None, "body": body,
                        "before": {k: found.get(k) for k in body} if found else None})
    if len(set(targets.values())) != len(targets):
        raise C.ConfigError("Channel mappings must be distinct")
    managed = {r["id"]: int(r["permissions"]) for r in state["roles"]}
    positions = {r["id"]: r.get("position", 0) for r in state["roles"]}
    member = state.get("bot_member", {})
    bot_roles = member.get("roles", [])
    perms = N.effective_permissions(state["guild_id"], managed, bot_roles, [])
    if perms & 8:
        warnings.append("Bot currently has Administrator; remove it before approval")
    if (perms & N.BOT_PERMISSIONS) != N.BOT_PERMISSIONS:
        warnings.append("Bot lacks required permissions: Manage Roles/Channels, View/Send/Embed/History")
    highest = max((positions.get(r, 0) for r in bot_roles), default=0)
    for name in ("ThePrisons", "Sky Supra"):
        if not roles[name].startswith("role:") and positions.get(roles[name], 0) >= highest:
            warnings.append("Bot role must be above " + name)
    for change in changes:
        if change["kind"] == "role" and int(change["body"].get("permissions", "0")) & ~perms:
            warnings.append("Bot cannot grant requested staff permissions: " + change["key"])
    actual_permissions = {r["id"]: int(r["permissions"]) for r in state["roles"]}
    for name, rid in roles.items():
        if rid.startswith("role:") or name in ("ThePrisons", "Sky Supra"):
            actual_permissions[rid] = N.ROLES[name]
    checked_matrix = matrix(state["guild_id"], roles, bot, actual_permissions)
    expected_matrix = matrix(state["guild_id"], roles, bot)
    if checked_matrix != expected_matrix:
        warnings.append("Actual Member/staff permissions fail the requested visibility matrix; review existing role permissions")
    result = {"schema": 2, "guild_id": state["guild_id"], "snapshot_digest": digest(state),
              "roles": roles, "channels": targets, "changes": changes, "blocking": sorted(set(warnings)),
              "onboarding": {"action": "manual-review", "single_select": False, "required": False,
                             "options": [{"name": N.project(k)["name"], "role_ids": [roles[N.project(k)["role"]]]} for k in N.PROJECTS]},
              "permission_matrix": checked_matrix}
    result["digest"] = digest(result)
    return result


def matrix(guild, roles, bot, actual_permissions=None):
    cases = {"Nur ThePrisons": ["ThePrisons"], "Nur Sky Supra": ["Sky Supra"],
             "Beide Mods": ["ThePrisons", "Sky Supra"], "Keine Mod": [],
             "Moderator": ["Moderator"], "Administrator": ["Administrator"]}
    permissions = actual_permissions if actual_permissions is not None else {guild: 0, **{roles[n]: p for n, p in N.ROLES.items()}}
    result = {}
    for label, names in cases.items():
        member = [roles[n] for n in names] + [roles["Member"]]
        result[label] = {a: bool(N.effective_permissions(guild, permissions, member,
                                  N.overwrites(guild, roles, a, bot=bot)) & N.VIEW)
                         for a in ("common", "theprisons", "sky-supra", "staff", "ticket")}
    return result


def apply(api, cfg, approved_plan, approval):
    content = {k: v for k, v in approved_plan.items() if k != "digest"}
    if cfg.get("NEXORA_MIGRATION_APPROVED") != "true" or approval != approved_plan.get("digest") or digest(content) != approval:
        raise C.ConfigError("Apply requires explicit migration approval and the exact plan digest")
    current = plan(snapshot(api, cfg), cfg)
    if current != approved_plan:
        raise C.ConfigError("Server/config changed; generate and approve a new preview")
    if current["blocking"]:
        raise C.ConfigError("Resolve blocking permission checks before apply")
    from .store import Store
    store = Store(cfg.get("NEXORA_STATE_DB", "state/nexora.sqlite3"))
    operation = "migration:" + current["guild_id"] + ":" + approval
    if not current["changes"]:
        return {"roles": current["roles"], "channels": current["channels"], "verification": current}
    if store.reserve(operation):
        raise C.ConfigError("This migration was already applied; create a fresh preview")
    mapping = {}
    def resolve(value):
        if isinstance(value, dict):
            return {k: resolve(v) for k, v in value.items()}
        if isinstance(value, list):
            return [resolve(v) for v in value]
        return mapping.get(value, value) if isinstance(value, str) else value
    for change in current["changes"]:
        body = resolve(change["body"])
        if change["kind"] == "role":
            route = f"/guilds/{current['guild_id']}/roles"
            result = api.request("PATCH" if change["id"] else "POST", route + ("/" + change["id"] if change["id"] else ""), body)
            mapping["role:" + change["key"]] = result["id"]
        else:
            route = "/channels/" + change["id"] if change["id"] else f"/guilds/{current['guild_id']}/channels"
            result = api.request("PATCH" if change["id"] else "POST", route, body)
            mapping["channel:" + change["key"]] = result["id"]
    verification = plan(snapshot(api, cfg), cfg)
    if verification["changes"] or verification["blocking"]:
        raise C.ConfigError("Post-apply verification failed; reconcile partial migration and preview again")
    store.complete(operation, current["guild_id"])
    return {"roles": resolve(current["roles"]), "channels": resolve(current["channels"]), "verification": verification}
