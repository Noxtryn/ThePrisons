#!/usr/bin/env bash
# Builds the Discord release embed (with jq, so nothing breaks while escaping) and posts it.
#
# Environment:
#   DISCORD_WEBHOOK  the webhook URL (a GitHub secret; never printed, never written to a file)
#   VERSION          x.y.z          TAG  vX.Y.Z          REPO  owner/name
#   NOTES            path of the changelog section (default: notes.md)
#   NOTES_DE         path of the German section (default: notes-de.md; optional)
#   MENTIONS         user mentions for the message, e.g. "<@123> <@456>" (optional)
#   DRY_RUN=1        only print the JSON (no webhook needed)
#
# A Discord problem never fails the release: this script always exits 0 when posting.
set -uo pipefail
version="${VERSION:?VERSION is required}"
tag="${TAG:-v$version}"
repo="${REPO:-Noxtryn/ThePrisons}"
notes="${NOTES:-notes.md}"
notes_de="${NOTES_DE:-notes-de.md}"      # the German section (CHANGELOG.de.md); no file = English embed only
mentions="${MENTIONS:-}"                 # e.g. "<@123> <@456>" (Discord user IDs; a GitHub variable, not a secret)
codename="${CODENAME:-}"
mc="${MC:-1.21.11}"
jar_name="${JAR_NAME:-theprisons-$version.jar}"
repo_url="https://github.com/$repo"
site_url="https://$(printf %s "${repo%%/*}" | tr A-Z a-z).github.io/${repo##*/}/"
release_url="https://github.com/$repo/releases/tag/$tag"
jar_url="https://github.com/$repo/releases/download/$tag/$jar_name"
card_url="https://raw.githubusercontent.com/$repo/$tag/docs/media/changelog-$version.gif"
changelog_url="https://github.com/$repo/blob/$tag/CHANGELOG.md"
stamp="$(date -u +%Y-%m-%dT%H:%M:%S.000Z)"

if [ -s "$notes_de" ]; then de_file="$notes_de"; else de_file="/dev/null"; fi

payload="$(jq -n \
  --rawfile notes "$notes" --rawfile notes_de "$de_file" \
  --arg title "ThePrisons v$version" --arg mentions "$mentions" \
  --arg repo "$repo_url" --arg site "$site_url" --arg release "$release_url" --arg jar "$jar_url" --arg card "$card_url" --arg changelog "$changelog_url" \
  --arg stamp "$stamp" --arg version "$version" --arg codename "$codename" --arg mc "$mc" '
  # bullets of every "### Group" of a changelog section
  def groups($text): ($text | split("\n")
    | reduce .[] as $l ({g: null, o: {}};
        if ($l | test("^### ")) then .g = ($l | sub("^### "; ""))
        elif (.g != null and ($l | test("^\\s*[-*] "))) then .o[.g] += [($l | sub("^\\s*[-*] "; "- "))]
        else . end) | .o);
  # the "> note" lines at the top of a section (e.g. work in progress)
  def banner($text): [$text | split("\n")[] | select(test("^> ")) | sub("^> "; "")] | join(" ");
  # as many bullets as fit into a field (limit 1024 characters)
  def fit($max; $more): . as $all
    | reduce range(0; length) as $i ({text: "", n: 0};
        if (.text | length) + ($all[$i] | length) + 1 <= $max then .text += (if .text == "" then "" else "\n" end) + $all[$i] | .n += 1 else . end)
    | if .n < ($all | length) then .text + "\n… +" + (($all | length) - .n | tostring) + " " + $more else .text end;
  def field($g; $name; $key; $more): ($g[$key] // []) as $b
    | if ($b | length) == 0 then empty else {name: $name, value: ($b | map(.[0:200]) | fit(980; $more)), inline: false} end;
  def links($labels): "[GitHub](" + $repo + ") · [" + $labels[0] + "](" + $site + ") · [Release](" + $release + ") · [" + $labels[1] + "](" + $jar + ") · [Changelog](" + $changelog + ")";
  def head($en): (if $codename != "" then "**" + $codename + "** · " else "" end) + "**Minecraft " + $mc + " · Fabric**\n" + links(if $en then ["Website", "Download the jar"] else ["Webseite", "Jar herunterladen"] end);
  (groups($notes)) as $en
  | (groups($notes_de)) as $de
  | (banner($notes)) as $ben
  | (banner($notes_de)) as $bde
  | {
    username: "ThePrisons",
    content: ((if $mentions != "" then $mentions + "\n" else "" end)
      + "# 🚀 ThePrisons v" + $version + " is out! · ist da!"
      + (if $ben != "" then "\n⚠️ **Bandit Macro is a work in progress (~2 %)** · **Das Bandit-Makro ist in Arbeit (~2 %)**" else "" end)),
    allowed_mentions: {parse: ["users"]},
    embeds: ([{
      title: ("🇬🇧 " + $title),
      url: $release,
      description: ((if $ben != "" then "⚠️ " + $ben + "\n\n" else "" end) + head(true)),
      color: 8077311,
      fields: [field($en; "✨ New"; "Added"; "more in the release"), field($en; "🔧 Improved"; "Changed"; "more in the release"), field($en; "🐛 Fixed"; "Fixed"; "more in the release")],
      image: {url: $card},
      footer: {text: "ThePrisons · All rights reserved"},
      timestamp: $stamp
    }] + (if ($de | length) > 0 then [{
      title: ("🇩🇪 " + $title),
      url: $release,
      description: ((if $bde != "" then "⚠️ " + $bde + "\n\n" else "" end) + head(false)),
      color: 16741274,
      fields: [field($de; "✨ Neu"; "Hinzugefügt"; "mehr im Release"), field($de; "🔧 Verbessert"; "Geändert"; "mehr im Release"), field($de; "🐛 Behoben"; "Behoben"; "mehr im Release")],
      footer: {text: "ThePrisons · Alle Rechte vorbehalten"},
      timestamp: $stamp
    }] else [] end))
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
