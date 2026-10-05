#!/usr/bin/env bash
# Processes your real recordings: docs/raw/ -> docs/media/
#   images (png/jpg/webp) -> cropped to 16:9, max 1600 px wide, WebP (and a PNG copy for the README)
#   clips  (mp4/mkv/webm/mov) -> GIF (12 fps, 640 px wide, optimised palette)
# Needs: ffmpeg, python3 + Pillow. Re-running only redoes files that changed.
#
#   scripts/media.sh            process everything in docs/raw/
#   scripts/media.sh --check    only list what is processed / still missing (see docs/raw/BENÖTIGT.md)
set -euo pipefail
cd "$(dirname "$0")/.."
raw=docs/raw
out=docs/media
mkdir -p "$out"

if [ "${1:-}" = "--check" ]; then
  echo "Raw files in $raw:"; find "$raw" -maxdepth 1 -type f ! -name '*.md' -printf '  %f\n' | sort || true
  echo "Media in $out:";     find "$out" -maxdepth 1 -type f -printf '  %f\n' | sort || true
  exit 0
fi

command -v ffmpeg >/dev/null || { echo "ffmpeg is missing" >&2; exit 1; }
python3 -c 'import PIL' 2>/dev/null || { echo "Pillow is missing (pip install pillow)" >&2; exit 1; }

shopt -s nullglob nocaseglob
count=0
for f in "$raw"/*.png "$raw"/*.jpg "$raw"/*.jpeg "$raw"/*.webp; do
  base="$(basename "${f%.*}")"
  for target in "$out/$base.webp" "$out/$base.png"; do
    [ "$target" -nt "$f" ] && continue
    python3 - "$f" "$out/$base" <<'PY'
import sys
from PIL import Image
src, dst = sys.argv[1], sys.argv[2]
im = Image.open(src).convert("RGB")
w, h = im.size
target = 16 / 9
if w / h > target:                      # too wide: cut the sides
    nw = int(h * target); im = im.crop(((w - nw) // 2, 0, (w - nw) // 2 + nw, h))
else:                                   # too tall: cut top and bottom
    nh = int(w / target); im = im.crop((0, (h - nh) // 2, w, (h - nh) // 2 + nh))
if im.width > 1600:
    im = im.resize((1600, int(1600 * 9 / 16)), Image.LANCZOS)
im.save(dst + ".webp", "WEBP", quality=86, method=6)
im.save(dst + ".png", optimize=True)
PY
    break
  done
  echo "image  $base"; count=$((count + 1))
done

for f in "$raw"/*.mp4 "$raw"/*.mkv "$raw"/*.webm "$raw"/*.mov; do
  base="$(basename "${f%.*}")"
  target="$out/$base.gif"
  if [ -f "$target" ] && [ "$target" -nt "$f" ]; then echo "clip   $base (up to date)"; continue; fi
  pal="$(mktemp --suffix=.png)"
  vf="fps=12,scale=640:-1:flags=lanczos"
  ffmpeg -v error -y -i "$f" -vf "$vf,palettegen=max_colors=128:stats_mode=diff" "$pal"
  ffmpeg -v error -y -i "$f" -i "$pal" -lavfi "$vf [x]; [x][1:v] paletteuse=dither=bayer:bayer_scale=4" "$target"
  rm -f "$pal"
  echo "clip   $base -> $(du -k "$target" | cut -f1) KB"; count=$((count + 1))
done
echo "done: $count file(s) processed"
