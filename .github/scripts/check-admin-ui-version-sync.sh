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
