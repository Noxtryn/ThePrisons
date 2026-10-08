#!/usr/bin/env bash
# Consistency checks that keep the marketing surface honest. Run by CI; also runnable locally.
#  - CHANGELOG.md / CHANGELOG.de.md have a section for mod_version
#  - website: no forbidden safety claims, config.json is valid, referenced media exists
set -uo pipefail
cd "$(dirname "$0")/.."
fail=0
err() { echo "ERROR: $*"; fail=1; }

version="$(sed -n 's/^mod_version=//p' gradle.properties)"
[ -n "$version" ] || err "mod_version missing in gradle.properties"
for f in CHANGELOG.md CHANGELOG.de.md; do
  grep -q "^## \[$version\]" "$f" || err "$f has no section [$version]"
done

# no claims about being undetectable / safe
if grep -rniE "undetect|ban-?proof|guaranteed safe|100 ?% (safe|undetect)|nicht erkennbar|unbannbar" site README.md README.de.md docs/marketing 2>/dev/null \
   | grep -viE "no promise|kein Versprechen|never say|nie sagen|forbidden|verboten|not (say|claim)" ; then
  err "forbidden safety claim found (see lines above)"
fi

# the repository moved to Noxtryn: no tracked file may point at the old owner or old Pages host
old="olb-""freelocs"
if git ls-files -z | xargs -0 grep -InE "$old" 2>/dev/null | grep -v "^docs/media/"; then
  err "reference to the old repository owner found (use Noxtryn/ThePrisons and noxtryn.github.io)"
fi

# Discord content: parses, fits Discord's limits, no emoji / forbidden claims / open placeholders
(cd discord-bot && python3 -m prisonsbot check) || err "discord-bot content check failed (see above)"

# disabled market module must not be advertised as available (site, READMEs); a line is fine if it says it is off
market_re='auction house|auktionshaus|shop overlay|shop-overlay|item list|item-list|market tracker|markt-tracker|/prisons price|market scan'
off_re='switched off|off in release|abgeschaltet|not available|nicht verfügbar|re-enable|wieder aktiv|reviewed|überprüft|disabled|deaktiviert|not in the mod|fehlt|beta'
if grep -niE "$market_re" site/index.html site/app.js README.md README.de.md | grep -viE "$off_re"; then
  err "disabled market feature mentioned as available (see lines above)"
fi
for f in market shops item-list; do
  grep -q "\"$f\"" site/app.js && err "feature card '$f' (market module) on the website"
done

# workflows: CI and Pages must never publish a release; Pages must not deploy from dev
grep -nE "gh release|action-gh-release|create-release" .github/workflows/ci.yml .github/workflows/pages.yml && err "a CI/Pages workflow creates a release"
grep -nE "branches: *\[.*dev" .github/workflows/pages.yml .github/workflows/release.yml && err "pages/release workflow triggers on dev"
grep -q "tags:" .github/workflows/release.yml || err "release.yml has no tag trigger"

python3 -I - <<'PY' || fail=1
import json, re, sys, pathlib
ok = True
site = pathlib.Path("site")
json.load(open(site / "config.json"))
html = (site / "index.html").read_text(encoding="utf-8")
for src in re.findall(r'(?:src|href)="(media/[^"]+)"', html):
    # media/ is copied from docs/media at deploy time
    if not pathlib.Path("docs", src).exists():
        print("ERROR: missing", src); ok = False
js = (site / "app.js").read_text(encoding="utf-8")
for card in re.findall(r'^    \["([a-z-]+)", "[A-Z]', js, re.M):
    if not pathlib.Path("docs/media/cards", card + ".png").exists():
        print("ERROR: no card image for", card); ok = False
for a in set(re.findall(r'href="#([^"]+)"', html)):
    if a != "top" and f'id="{a}"' not in html:
        print("ERROR: broken anchor #" + a); ok = False
for url in set(re.findall(r'href="(https?://[^"]+)"', html)):
    if not re.match(r"^https://[A-Za-z0-9.-]+(/\S*)?$", url):
        print("ERROR: odd link", url); ok = False
if 'href="https://github.com/Noxtryn/ThePrisons/issues' not in html:
    print("ERROR: GitHub issues link (support fallback) missing"); ok = False
for need in ("download", "install", "compat", "faq", "support", "roadmap", "gallery", "features", "changelog"):
    if f'id="{need}"' not in html:
        print("ERROR: section missing:", need); ok = False
sys.exit(0 if ok else 1)
PY
[ $? -eq 0 ] || fail=1

# optional (needs network): CHECK_LINKS=1 requests the external links of the page
if [ "${CHECK_LINKS:-0}" = 1 ]; then
  for u in $(grep -oE 'href="https://[^"]+' site/index.html | cut -c7- | sort -u); do
    code=$(curl -s -o /dev/null -L -m 15 -w '%{http_code}' "$u")
    [ "$code" = 200 ] || echo "WARN: $u -> HTTP $code"
  done
fi

[ $fail -eq 0 ] && echo "release check OK ($version)"
exit $fail
