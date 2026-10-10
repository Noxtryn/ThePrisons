"""Publishes official messages. Idempotent: finds the bot's own earlier message by the key in its footer and edits it."""
import json

from . import config as C
from . import content as K
from .api import DiscordError


def sync_messages(api, channel_id, desired, dry_run=False, log=print):
    """desired: [{"key", "payload"}]. Returns {"posted": n, "edited": n, "unchanged": n}."""
    existing = {}
    if not dry_run:
        me = api.me()["id"]
        for m in api.channel_messages(channel_id):  # newest first
            if m.get("author", {}).get("id") == me:
                key = K.key_of(m)
                if key and key not in existing:
                    existing[key] = m
    stats = {"posted": 0, "edited": 0, "unchanged": 0}
    for item in desired:
        key, payload = item["key"], item["payload"]
        if dry_run:
            log(f"  {key}: would post or edit ({len(payload['embeds'])} embeds)")
            log(json.dumps(payload, ensure_ascii=False, indent=2))
            continue
        old = existing.get(key) or next((existing[a] for a in item.get("aliases", []) if a in existing), None)
        if old and K.same(old, payload):
            stats["unchanged"] += 1
            log(f"  {key}: unchanged")
        elif old:
            api.edit(channel_id, old["id"], payload)
            stats["edited"] += 1
            log(f"  {key}: edited")
        else:
            # Discord enforces nonce uniqueness for recent messages; shared CI concurrency
            # and complete history cover subsequent runs. Never retry an uncertain POST.
            import hashlib
            nonce = str(int.from_bytes(hashlib.sha256((str(channel_id) + ':' + key).encode()).digest()[:8], 'big'))
            api.post(channel_id, dict(payload, nonce=nonce, enforce_nonce=True))
            stats["posted"] += 1
            log(f"  {key}: posted")
    return stats


def publish_channel(api, cfg, name, ctx, dry_run=False, strict=True, log=print):
    """Publishes one official channel. Returns the stats dict, or None when skipped."""
    if not K.approved(name):
        log(f"{name}: skipped (content status is not 'approved')")
        return None
    cid = cfg.channel_id(name)
    if not cid and dry_run:
        cid = "0"  # a dry run only shows the messages
    if not cid:
        if strict:
            raise C.ConfigError(f"{C.channel_env(name)} is not set")
        log(f"{name}: skipped ({C.channel_env(name)} is not set)")
        return None
    log(f"{name}:")
    return sync_messages(api, cid, K.render(name, ctx), dry_run=dry_run, log=log)


def publish_all(api, cfg, ctx, names=None, dry_run=False, log=print):
    explicit = bool(names)
    results, failed = {}, False
    for name in names or ["welcome", "rules", "get-started", "faq", "roadmap", "known-issues"]:
        try:
            results[name] = publish_channel(api, cfg, name, ctx, dry_run=dry_run, strict=explicit, log=log)
        except (DiscordError, C.ConfigError, K.ContentError) as error:
            failed = True
            log(f"{name}: FAILED - {error}")
    return results, failed
