#!/usr/bin/env bash
# Prints the CHANGELOG.md section of one version, for the GitHub Release notes.
#
#   .github/scripts/changelog-notes.sh 0.1.0-beta.1 [CHANGELOG.md]
#
# Fails when the section is missing, empty, or its heading has no release date
# ("## [0.1.0-beta.1] - 2026-12-01"), so a tag cannot be released with unfinished notes.
set -euo pipefail

version="${1:?usage: changelog-notes.sh <version> [changelog]}"
changelog="${2:-CHANGELOG.md}"

heading="$(grep -E "^## \[${version//./\\.}\]" "$changelog" || true)"
if [[ -z "$heading" ]]; then
  echo "::error file=$changelog::No '## [$version]' section in $changelog. Add the release notes before tagging." >&2
  exit 1
fi
if [[ ! "$heading" =~ ^##\ \[[^]]+\]\ -\ [0-9]{4}-[0-9]{2}-[0-9]{2}$ ]]; then
  echo "::error file=$changelog::Heading '$heading' needs a release date: '## [$version] - YYYY-MM-DD'." >&2
  exit 1
fi

# Everything after the heading up to the next version heading or the link references,
# without leading and trailing blank lines.
notes="$(awk -v heading="$heading" '
  $0 == heading { found = 1; next }
  found && (/^## \[/ || /^\[[^]]+\]: /) { exit }
  found && NF == 0 { if (started) blank++; next }
  found { while (blank > 0) { print ""; blank-- } print; started = 1 }
' "$changelog")"

if [[ -z "${notes//[[:space:]]/}" ]]; then
  echo "::error file=$changelog::The '## [$version]' section in $changelog is empty." >&2
  exit 1
fi
printf '%s\n' "$notes"
