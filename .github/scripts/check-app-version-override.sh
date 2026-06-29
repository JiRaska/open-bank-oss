#!/usr/bin/env bash
# Guard: no service may set `quarkus.application.version` in application.yaml.
#
# The release SemVer lives in <service>/version.txt (release-please owns it).
# The convention plugin `openbank.quarkus-service` reads version.txt into the
# Gradle project version and the Quarkus Gradle plugin propagates it into
# quarkus.application.version at build time. An explicit value in application.yaml
# SHADOWS that build-stamped version with a stale literal, breaking
# rules.yaml: release_invariant (version.txt == quarkus.application.version ==
# git tag) on every release-please bump. So the key must be ABSENT — the runtime
# version is derived from version.txt, the single source of truth.
#
# Scope: RELEASED components only — a module is a released component IFF it has a
# version.txt (rules.yaml: released_unit_marker). Modules without a version.txt
# (e.g. openbank-analytics-sink, which doesn't apply openbank.quarkus-service and
# has nothing to derive from) legitimately keep an explicit value and are skipped.
#
# stdlib-only (awk); no PyYAML/yamllint dependency. ENFORCED.
# Usage: check-app-version-override.sh [root]   (default root: .)
set -euo pipefail
root="${1:-.}"
fail=0
checked=0
skipped=0
while IFS= read -r f; do
  # Only enforce on released components (sibling version.txt present).
  moddir="${f%/src/main/resources/application.yaml}"
  if [ ! -f "$moddir/version.txt" ]; then
    skipped=$((skipped + 1)); continue
  fi
  checked=$((checked + 1))
  # Detect a 4-space-indented `version:` directly under quarkus: -> application:.
  hit=$(awk '
    /^[^[:space:]#]/        { in_q = ($0 ~ /^quarkus:/); in_app = 0; next }
    in_q && /^  [^[:space:]#]/ { in_app = ($0 ~ /^  application:/); next }
    in_app && /^    version:[[:space:]]/ { print NR": "$0 }
  ' "$f" || true)
  if [ -n "$hit" ]; then
    fail=1
    echo "::error file=$f::quarkus.application.version must NOT be set in application.yaml — remove it so the version derives from version.txt (rules.yaml: release_invariant). Found: $hit"
  fi
done < <(find "$root" -path '*/src/main/resources/application.yaml' -not -path '*/build/*' | sort)
echo "check-app-version-override: $checked released component(s) checked ($skipped non-released skipped), $( [ "$fail" -eq 0 ] && echo "none set quarkus.application.version (all derive from version.txt)." || echo "VIOLATIONS above." )"
exit "$fail"
