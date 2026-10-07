"""Placeholder values: derived from the repository (gradle.properties, fabric.mod.json) and, when known, the latest release."""
import json
import re

from . import config as C


def _props() -> dict:
    out = {}
    for line in (C.ROOT / "gradle.properties").read_text(encoding="utf-8").splitlines():
        if "=" in line and not line.startswith("#"):
            key, value = line.split("=", 1)
            out[key.strip()] = value.strip()
    return out


def _version_of(spec: str) -> str:
    match = re.search(r"\d+(\.\d+)*", spec or "")
    return match.group(0) if match else (spec or "").strip("*")


def release_from_repo(version=None) -> dict:
    """Release facts from the checkout (used by the release workflow and as the offline fallback)."""
    p = _props()
    version = version or p["mod_version"]
    mc, codename = p["minecraft_version"], p.get("release_codename", "")
    jar = f"ThePrisons-{codename}-v{version}-mc{mc}.jar"
    tag = f"v{version}"
    return {"version": version, "tag": tag, "minecraft": mc, "codename": codename, "jar_name": jar,
            "download_url": f"{C.RELEASES_URL}/download/{tag}/{jar}", "release_url": f"{C.RELEASES_URL}/tag/{tag}", "body": ""}


def release_from_github(data: dict) -> dict:
    """A GitHub 'latest release' API object -> the same shape as release_from_repo."""
    tag = data.get("tag_name", "")
    version = tag.lstrip("v")
    jar = next((a for a in data.get("assets", []) if a.get("name", "").endswith(".jar")), None)
    name = data.get("name") or ""
    parts = [x.strip() for x in name.split("·")]
    codename = parts[1] if len(parts) > 2 else ""
    mc = re.sub(r"^MC\s*", "", parts[2]) if len(parts) > 2 else ""
    if jar and not mc:
        m = re.search(r"-mc([\d.]+)\.jar$", jar["name"])
        mc = m.group(1) if m else ""
    return {"version": version, "tag": tag, "minecraft": mc, "codename": codename,
            "jar_name": jar["name"] if jar else "", "download_url": jar["browser_download_url"] if jar else f"{C.RELEASES_URL}/latest",
            "release_url": data.get("html_url") or f"{C.RELEASES_URL}/latest", "body": data.get("body") or ""}


def build(cfg: C.Config, release: dict = None) -> dict:
    """The placeholder dictionary. `release` overrides the repository's own version (e.g. the tag being released)."""
    mod = json.loads((C.ROOT / "src/main/resources/fabric.mod.json").read_text(encoding="utf-8"))
    depends = mod.get("depends", {})
    rel = release or release_from_repo()
    ctx = {
        "version": rel["version"], "codename": rel["codename"], "jar_name": rel["jar_name"],
        "download_url": rel["download_url"], "release_url": rel["release_url"],
        "minecraft": rel["minecraft"] or _version_of(depends.get("minecraft")),
        "loader": _version_of(depends.get("fabricloader")), "java": _version_of(depends.get("java")),
        "site_url": C.SITE_URL, "repo_url": C.REPO_URL, "issues_url": C.ISSUES_URL,
        "changelog_url": f"{C.REPO_URL}/blob/{rel['tag']}/CHANGELOG.md",
    }
    for name in C.CHANNELS:
        cid = cfg.channel_id(name)
        ctx["channel_" + name.replace("-", "_")] = f"<#{cid}>" if cid else f"#{name}"
    return ctx
