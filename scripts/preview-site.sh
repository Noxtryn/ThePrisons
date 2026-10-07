#!/usr/bin/env bash
# Local website preview: assembles the same files as pages.yml into a temp folder and serves them on :8080.
set -euo pipefail
cd "$(dirname "$0")/.."
out="$(mktemp -d)"
cp -r site/. "$out/"
mkdir -p "$out/media/cards"
cp docs/media/banner.png "$out/media/"
cp docs/media/cards/*.png "$out/media/cards/"
rm -f "$out/media/cards/"{market,shops,item-list}.png
find docs/media -maxdepth 1 -type f \( -name '*.webp' \) ! -name 'market-*' ! -name 'dashboard.webp' ! -name 'scoreboard.webp' -exec cp {} "$out/media/" \;
(cd "$out/media" && find . -maxdepth 1 -type f \( -name '*.webp' -o -name '*.gif' \) ! -name 'changelog-*' -printf '%f\n' | sort | python3 -c 'import sys,json;print(json.dumps([l.strip() for l in sys.stdin]))') > "$out/media/manifest.json"
echo "serving $out on http://localhost:8080"
cd "$out" && exec python3 -m http.server 8080
