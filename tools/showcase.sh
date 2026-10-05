#!/usr/bin/env sh
# Shows everything the mod offers a player (GUI, dashboard, design, HUD, items, armour, storage, players) - not the
# Ore Macro - in a real client: every scene with a caption, then a screenshot in build/run/clientGameTest/screenshots.
#   tools/showcase.sh        4 seconds per scene
#   tools/showcase.sh 8      8 seconds per scene (slower, to watch)
cd "$(dirname "$0")/.." || exit 1
exec ./gradlew runClientGameTest -Pshowcase -PshowcaseSeconds="${1:-4}"
