#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
# See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
#
# Guard: openbank-admin-ui/version.txt MUST equal package.json:version (ADR-0029 release_invariant).
#
# Why this exists: admin-ui is release-type `simple`, so release-please bumps version.txt as the
# primary version file, and package.json is an `extra-files` json updater. That updater is
# replace-based — after a one-off historical desync (package.json left behind), release-please can
# no longer find the previous version string to replace, so package.json silently STAYS behind on
# every subsequent release. The drift then only surfaces at DEPLOY time: build-push-admin-ui.sh
# enforces the same invariant and the build fails — after merge, in the deploy pipeline, far from
# the change that caused it. This guard moves that check to PR time so drift fails fast and visible.
#
# Fix when it trips: set openbank-admin-ui/package.json `version` equal to version.txt (they move
# together on a real release; a feature PR touches neither — release-please owns both).
#
# Usage: check-admin-ui-version-sync.sh [repo-root]   (default: .)
set -euo pipefail

# --- self-test ------------------------------------------------------------------------
# release-please bumps version.txt, but its package.json extra-file updater is REPLACE-based
# and silently desyncs after a one-off drift. Otherwise the mismatch surfaces at DEPLOY time,
# where build-push-admin-ui.sh fails post-merge; this gate moves it to PR time.
if [ "${1:-}" = "--self-test" ]; then
  set +e
  td=$(mktemp -d); trap 'rm -rf "$td"' EXIT
  fails=0
  mk() { mkdir -p "$1/openbank-admin-ui"; printf '%s\n' "$2" > "$1/openbank-admin-ui/version.txt"
         printf '{"version": "%s"}\n' "$3" > "$1/openbank-admin-ui/package.json"; }
  expect() { local label="$1" root="$2" want="$3" sub="${4:-}" out rc
    out=$(bash "$0" "$root" 2>&1); rc=$?
    if [ "$rc" -ne "$want" ]; then echo "::error::self-test: $label — want rc=$want got $rc: $out" >&2; fails=$((fails+1))
    elif [ -n "$sub" ] && ! printf '%s' "$out" | grep -qF -- "$sub"; then
      echo "::error::self-test: $label — rc right, reason wrong (no '$sub'): $out" >&2; fails=$((fails+1)); fi; }

  a="$td/drift"; mk "$a" 0.91.4 0.91.3
  expect "a version mismatch is FLAGGED" "$a" 1 "version drift"
  b="$td/same"; mk "$b" 0.91.4 0.91.4
  expect "matching versions are clean" "$b" 0 "OK (0.91.4)"
  # A trailing newline in version.txt is the normal shape and must not read as a mismatch.
  c="$td/ws"; mkdir -p "$c/openbank-admin-ui"; printf '  0.91.4 \n' > "$c/openbank-admin-ui/version.txt"
  printf '{"version": "0.91.4"}\n' > "$c/openbank-admin-ui/package.json"
  expect "surrounding whitespace is not a mismatch" "$c" 0 "OK"
  # ABSENCE: the script skips, which is right in a partial checkout — but the skip must be
  # VISIBLE, or a moved path reads exactly like a passing gate.
  d="$td/none"; mkdir -p "$d"
  expect "an absent admin-ui says so rather than passing silently" "$d" 0 "absent — skip"

  if [ "$fails" -gt 0 ]; then echo "self-test FAILED ($fails case(s))" >&2; exit 1; fi
  echo "self-test ok: admin-ui version-sync guard is falsifiable (4 cases)"
  exit 0
fi

ROOT="${1:-.}"
VT="${ROOT}/openbank-admin-ui/version.txt"
PJ="${ROOT}/openbank-admin-ui/package.json"

# Not an admin-ui checkout (e.g. a path-scoped runner) — nothing to check.
[ -f "$VT" ] && [ -f "$PJ" ] || { echo "check-admin-ui-version-sync: admin-ui version files absent — skip"; exit 0; }

VER_TXT="$(tr -d '[:space:]' < "$VT")"
VER_PKG="$(python3 -c "import json,sys; print(json.load(open(sys.argv[1]))['version'])" "$PJ" 2>/dev/null \
  || node -p "require('./${PJ#./}').version")"

if [ "$VER_TXT" != "$VER_PKG" ]; then
  echo "::error::admin-ui version drift: version.txt=${VER_TXT} != package.json=${VER_PKG}"
  echo "  release-please bumps version.txt but its package.json extra-file updater is replace-based and"
  echo "  silently desyncs. Set openbank-admin-ui/package.json version to ${VER_TXT} so both match."
  exit 1
fi

echo "check-admin-ui-version-sync: OK (${VER_TXT})"
