#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
# See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
#
# Unit test for auto-deploy-reconcile-lag.sh. Builds a throwaway git repo whose history and
# gitops manifest exercise every branch of the lag probe, then asserts the JSON output.
# Pure git + jq, no network, no Gradle — safe to run in CI on every PR that touches either
# file (see scripts-selftest.yml / rules.yaml: deploy_reconcile).
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROBE="${SCRIPT_DIR}/auto-deploy-reconcile-lag.sh"

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
cd "$WORK"

git init -q
git config user.email test@example.com
git config user.name test
git config commit.gpgsign false
git config gpg.format openpgp

mkdir -p gitops
commit() { git add -A && git commit -qm "$1"; }
short() { git rev-parse --short=8 "$1"; }

# --- history -----------------------------------------------------------------------------
# c0: fresh source for four services.
for s in current-svc stale-svc placeholder-svc testonly-svc; do
  mkdir -p "openbank-${s}/src/main/kotlin" "openbank-${s}/src/test/kotlin"
  echo "v1" > "openbank-${s}/src/main/kotlin/App.kt"
done
commit "c0: seed services"
C0="$(short HEAD)"

# c1: bump ONLY stale-svc's main source (this becomes newer than its pinned image).
echo "v2" > openbank-stale-svc/src/main/kotlin/App.kt
commit "c1: change stale-svc main"

# c2: bump ONLY testonly-svc's TEST source — must NOT count as build-relevant.
echo "v2" > openbank-testonly-svc/src/test/kotlin/App.kt
commit "c2: change testonly-svc test only"
TIP="$(short HEAD)"

# --- gitops manifest ---------------------------------------------------------------------
# current-svc  : pinned at TIP                -> up to date        -> NOT lagging
# stale-svc    : pinned at c0 (before c1)     -> main changed since -> LAGGING
# placeholder  : pinned at a non-commit tag   -> never deployed     -> LAGGING
# testonly-svc : pinned at c0, only test moved -> not build-relevant -> NOT lagging
# foreign-svc  : stale pin BUT built by another pipeline (not in allowlist) -> NOT lagging
cat > gitops/deploy.yaml <<EOF
image: repo/openbank-current-svc:sandbox-${TIP}
image: repo/openbank-stale-svc:sandbox-${C0}
image: repo/openbank-placeholder-svc:sandbox-pending
image: repo/openbank-testonly-svc:sandbox-${C0}
image: repo/openbank-foreign-svc:sandbox-pending
EOF

# --- run + assert (no allowlist: every manifest service is a candidate) -------------------
GOT="$(RECONCILE_SERVICES='' bash "$PROBE" "$WORK/gitops")"
WANT='["openbank-foreign-svc","openbank-placeholder-svc","openbank-stale-svc"]'

# Normalise ordering (probe already sorts via jq unique, but compare defensively).
GOT_N="$(echo "$GOT"  | jq -S .)"
WANT_N="$(echo "$WANT" | jq -S .)"

if [ "$GOT_N" != "$WANT_N" ]; then
  echo "FAIL: reconcile lag probe"
  echo "  want: $WANT"
  echo "  got:  $GOT"
  exit 1
fi

# Second assertion: an allowlist (auto-deploy's ALL_SERVICES) drops manifest services the
# build path cannot build — foreign-svc is stale but absent from the list.
GOT_AL="$(RECONCILE_SERVICES='openbank-stale-svc openbank-placeholder-svc openbank-current-svc' \
            bash "$PROBE" "$WORK/gitops")"
WANT_AL='["openbank-placeholder-svc","openbank-stale-svc"]'
if [ "$(echo "$GOT_AL" | jq -S .)" != "$(echo "$WANT_AL" | jq -S .)" ]; then
  echo "FAIL: allowlist filter"
  echo "  want: $WANT_AL"
  echo "  got:  $GOT_AL"
  exit 1
fi

# Third assertion: with every pin at TIP, nothing lags (loop-stability guarantee).
cat > gitops/deploy.yaml <<EOF
image: repo/openbank-current-svc:sandbox-${TIP}-run32826611610
image: repo/openbank-stale-svc:sandbox-${TIP}
EOF
GOT2="$(bash "$PROBE" "$WORK/gitops")"
if [ "$(echo "$GOT2" | jq -c .)" != "[]" ]; then
  echo "FAIL: current pins, including a manual-refresh tag, must not lag (would loop). got: $GOT2"
  exit 1
fi

echo "PASS: auto-deploy-reconcile-lag.sh"
