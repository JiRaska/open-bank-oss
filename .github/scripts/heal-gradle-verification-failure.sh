#!/usr/bin/env bash
# Reactive, narrow fix for an interrupted-download-corrupted Gradle module
# cache entry: an artifact whose .pom downloaded but whose .jar hash-dir
# never got created (or a sibling daemon on a shared GRADLE_USER_HOME wrote
# a partial one). Gradle reports this as a checksum/verification failure,
# not a "file not found" — the message below is what --write-verification-metadata
# style dependency verification prints when the artifact can't be fetched to
# compare against the pinned checksum:
#
#   2 artifacts failed verification:
#     - foo-1.2.3.jar (com.example:foo:1.2.3) from repository maven
#
# Deliberately narrow: this parses ONLY that exact message and removes ONLY
# the named artifact/version's cache dir (forcing a clean re-resolve of just
# that one dependency). It does NOT scan the cache for other "suspicious"
# entries — an earlier version of this idea did a blanket scan and it turned
# out most artifacts consumed only as a BOM/import or an unused transitive
# dependency legitimately have a .pom with no .jar ever downloaded, so a
# pom-without-jar heuristic alone is not a valid corruption signal and
# produces false positives (do not resurrect that approach).
#
# Usage: heal-gradle-verification-failure.sh <path-to-build-log>
# Exit 0 with HEALED>=1 if it removed at least one artifact's cache dir
# (caller should retry the build). Exit 1 if the log doesn't match this
# specific failure signature (some other problem — do not retry blindly).
set -uo pipefail

LOG="${1:-}"
if [ -z "$LOG" ] || [ ! -f "$LOG" ]; then
  echo "usage: $0 <path-to-build-log>" >&2
  exit 1
fi

GRADLE_HOME="${GRADLE_USER_HOME:-$HOME/.gradle}"
BASE="$GRADLE_HOME/caches/modules-2/files-2.1"

healed=0

while IFS= read -r coord; do
  [ -z "$coord" ] && continue
  group="${coord%%:*}"
  rest="${coord#*:}"
  artifact="${rest%%:*}"
  version="${rest#*:}"
  [ -z "$group" ] || [ -z "$artifact" ] || [ -z "$version" ] && continue

  group_path=$(echo "$group" | tr -d '\r')
  target="$BASE/$group_path/$artifact/$version"
  if [ -d "$target" ]; then
    echo "::warning::healing corrupted Gradle cache entry from verification failure: $group:$artifact:$version ($target)"
    rm -rf "$target"
    healed=$((healed + 1))
  fi
done < <(grep -oE '\([^:()]+:[^:()]+:[^:()]+\) from repository maven' "$LOG" | sed -E 's/^\(([^)]+)\).*/\1/')

if [ "$healed" -eq 0 ]; then
  echo "no Gradle dependency-verification failure found in $LOG — not a cache-corruption pattern, nothing healed"
  exit 1
fi

echo "healed $healed artifact(s), retry the build"
exit 0
