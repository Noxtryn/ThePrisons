#!/usr/bin/env bash
# Builds the Discord release embed (with jq, so nothing breaks while escaping) and posts it.
#
# Environment:
#   DISCORD_WEBHOOK  the webhook URL (a GitHub secret; never printed, never written to a file)
#   VERSION          x.y.z          TAG  vX.Y.Z          REPO  owner/name
#   NOTES            path of the changelog section (default: notes.md)
#   DRY_RUN=1        only print the JSON (no webhook needed)
#
# A Discord problem never fails the release: this script always exits 0 when posting.
set -uo pipefail
version="${VERSION:?VERSION is required}"
tag="${TAG:-v$version}"
repo="${REPO:-olb-freelocs/ThePrisons}"
notes="${NOTES:-notes.md}"
release_url="https://github.com/$repo/releases/tag/$tag"
jar_url="https://github.com/$repo/releases/download/$tag/theprisons-$version.jar"
card_url="https://raw.githubusercontent.com/$repo/$tag/docs/media/changelog-$version.gif"
changelog_url="https://github.com/$repo/blob/$tag/CHANGELOG.md"
stamp="$(date -u +%Y-%m-%dT%H:%M:%S.000Z)"

payload="$(jq -n \
  --rawfile notes "$notes" \
  --arg title "ThePrisons v$version" \
  --arg release "$release_url" --arg jar "$jar_url" --arg card "$card_url" --arg changelog "$changelog_url" \
  --arg stamp "$stamp" --arg version "$version" '
  # bullets of every "### Group" of the changelog section
  def groups: ($notes | split("\n")
    | reduce .[] as $l ({g: null, o: {}};
        if ($l | test("^### ")) then .g = ($l | sub("^### "; ""))
        elif (.g != null and ($l | test("^\\s*[-*] "))) then .o[.g] += [($l | sub("^\\s*[-*] "; "- "))]
        else . end) | .o);
  # as many bullets as fit into a field (limit 1024 characters)
  def fit($max): . as $all
    | reduce range(0; length) as $i ({text: "", n: 0};
        if (.text | length) + ($all[$i] | length) + 1 <= $max then .text += (if .text == "" then "" else "\n" end) + $all[$i] | .n += 1 else . end)
    | if .n < ($all | length) then .text + "\n… +" + (($all | length) - .n | tostring) + " more in the release" else .text end;
  def field($name; $key): (groups[$key] // []) as $b
    | if ($b | length) == 0 then empty else {name: $name, value: ($b | map(.[0:200]) | fit(980)), inline: false} end;
  {
    username: "ThePrisons",
    embeds: [{
      title: $title,
      url: $release,
      description: ("**Minecraft 1.21.11 · Fabric**\n[Release](" + $release + ") · [Download the jar](" + $jar + ") · [Changelog](" + $changelog + ")"),
      color: 8077311,
      fields: [field("✨ Neu"; "Added"), field("🔧 Verbessert"; "Changed"), field("🐛 Behoben"; "Fixed")],
      image: {url: $card},
      footer: {text: "ThePrisons · All rights reserved"},
      timestamp: $stamp
    }]
  }')" || { echo "embed: jq failed" >&2; exit 0; }

if [ "${DRY_RUN:-}" = "1" ]; then
  printf '%s\n' "$payload"
  exit 0
fi
if [ -z "${DISCORD_WEBHOOK:-}" ]; then
  echo "embed: DISCORD_WEBHOOK is not set - nothing posted" >&2
  exit 0
fi
set +x
code="$(printf '%s' "$payload" | curl -sS --max-time 20 -o /dev/null -w '%{http_code}' \
  -H 'Content-Type: application/json' -X POST --data-binary @- "$DISCORD_WEBHOOK" 2>/dev/null || true)"
code="${code:-000}"
case "$code" in
  2??) echo "embed: posted to Discord ($code)" ;;
  *)   echo "embed: Discord not reachable or refused the post (HTTP $code) - the release is not affected" >&2 ;;
esac
exit 0
