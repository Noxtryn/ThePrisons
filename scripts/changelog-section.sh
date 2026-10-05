#!/usr/bin/env bash
# Prints the CHANGELOG.md section of one version (without its "## [x.y.z]" heading line).
# Usage: scripts/changelog-section.sh 1.0.0 [CHANGELOG.md]
set -euo pipefail
version="${1:?usage: changelog-section.sh <version> [file]}"
file="${2:-CHANGELOG.md}"
awk -v v="$version" '
  /^## \[/ { if (on) exit; if (index($0, "## [" v "]") == 1) { on = 1; next } }
  /^\[[^]]+\]: / { if (on) exit }
  on { print }
' "$file" | sed -e :a -e '/^\n*$/{$d;N;ba' -e '}'
