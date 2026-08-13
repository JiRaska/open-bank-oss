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

# --- self-test ------------------------------------------------------------------------
# `quarkus.application.version` in application.yaml OVERRIDES the value release-please derives
# from version.txt, so the artifact reports a version nobody bumped (rules.yaml:
# release_invariant). The check is an awk path-walk — exactly the code that looks right and is
# off by one nesting level.
if [ "${1:-}" = "--self-test" ]; then
  set +e
  td=$(mktemp -d); trap 'rm -rf "$td"' EXIT
  fails=0
  svc() { mkdir -p "$td/$1/src/main/resources"; printf '%b' "$3" > "$td/$1/src/main/resources/application.yaml"
          if [ "$2" = released ]; then echo 1.0.0 > "$td/$1/version.txt"; fi; }
  expect() { local label="$1" want="$2" sub="${3:-}" out rc
    out=$(bash "$0" "$td" 2>&1); rc=$?
    if [ "$rc" -ne "$want" ]; then echo "::error::self-test: $label — want rc=$want got $rc: $out" >&2; fails=$((fails+1))
    elif [ -n "$sub" ] && ! printf '%s' "$out" | grep -qF -- "$sub"; then
      echo "::error::self-test: $label — rc right, reason wrong (no '$sub'): $out" >&2; fails=$((fails+1)); fi; }
  reset() { rm -rf "$td"/openbank-x; }

  reset; svc openbank-x released 'quarkus:\n  application:\n    version: 9.9.9\n'
  expect "quarkus.application.version is FLAGGED" 1 "must NOT be set"
  reset; svc openbank-x released 'quarkus:\n  application:\n    name: x\n'
  expect "no version key is clean" 0 "none set quarkus.application.version"
  # NESTING: the same leaf under a different parent is a different property entirely, and
  # flagging it would push authors to delete a legitimate container-image version.
  reset; svc openbank-x released 'quarkus:\n  container-image:\n    version: 1.2.3\n'
  expect "version under another quarkus child is not this defect" 0 "none set"
  reset; svc openbank-x released 'other:\n  application:\n    version: 1.2.3\n'
  expect "application.version outside quarkus is not this defect" 0 "none set"
  # SCOPE: a module with no version.txt is not a released component, so the invariant is moot.
  reset; svc openbank-x unreleased 'quarkus:\n  application:\n    version: 9.9.9\n'
  expect "a non-released module is skipped" 0 "1 non-released skipped"
  # Every fix for this carries a comment naming the key it removed.
  reset; svc openbank-x released 'quarkus:\n  application:\n    # version: never set this here\n    name: x\n'
  expect "the key in a comment is not a hit" 0 "none set"

  if [ "$fails" -gt 0 ]; then echo "self-test FAILED ($fails case(s))" >&2; exit 1; fi
  echo "self-test ok: quarkus.application.version override guard is falsifiable (6 cases)"
  exit 0
fi
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
