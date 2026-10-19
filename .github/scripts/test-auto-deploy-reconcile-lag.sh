#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
# See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
#
# Unit test for auto-deploy-reconcile-lag.sh. Builds a throwaway git repo whose history and
# gitops manifest exercise every branch of the lag probe, then asserts the JSON output.
# Pure git + jq, no network, no Gradle — safe to run in CI on every PR that touches either
# file (see scripts-selftest.yml / rules.yaml: deploy_reconcile).
#
# The probe's notion of "same artifact" is pact-version-tree-equivalent.sh, which has its own
# 17-case self-test. This suite asserts the probe WIRES it in, with the case that motivated it
# (#11597) as a known-positive and the cases it must not over-fire on as known-negatives.
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

commit() { git add -A && git commit -qm "$1"; }
short() { git rev-parse --short=8 "$1"; }
sorted() { jq -cS 'sort' <<< "$1"; }
fail() { echo "FAIL: $1"; echo "  want: $2"; echo "  got:  $3"; exit 1; }

# --- history -----------------------------------------------------------------------------
# c0: Gradle services that all depend on a shared library, one non-Gradle (Python) service,
# docs, and the global build directories the equivalence script compares.
for s in current-svc stale-svc placeholder-svc testonly-svc release-svc lib-svc docs-svc other-svc; do
  mkdir -p "openbank-${s}/src/main/kotlin" "openbank-${s}/src/test/kotlin"
  echo "v1" > "openbank-${s}/src/main/kotlin/App.kt"
  printf 'implementation(project(":openbank-libs-domain"))\n' > "openbank-${s}/build.gradle.kts"
done
mkdir -p openbank-libs-domain/src/main/kotlin openbank-py-svc docs build-logic
echo "v1" > openbank-libs-domain/src/main/kotlin/Lib.kt
echo "x" > openbank-libs-domain/build.gradle.kts
echo "x" > build-logic/build.gradle.kts
echo "x" > settings.gradle.kts
echo "v1" > openbank-py-svc/app.py
echo "v1" > docs/README.md
commit "c0: seed services"
C0="$(short HEAD)"

# c1: ONLY stale-svc's main source.
echo "v2" > openbank-stale-svc/src/main/kotlin/App.kt
commit "c1: change stale-svc main"

# c2: ONLY testonly-svc's TEST source. Since #11597 this IS lagging: a provider's @State
# handlers live in src/test, and the broker only learns them when that version is deployed —
# the ledger/treasury deadlock. The service's own tree is compared whole.
echo "v2" > openbank-testonly-svc/src/test/kotlin/App.kt
commit "c2: change testonly-svc test only"

# c3: ONLY release-svc's version.txt — the release-please shape (#8127).
echo "0.2.0" > openbank-release-svc/version.txt
commit "c3: release-please bump of release-svc version.txt"
C3="$(short HEAD)"

# c4: KNOWN-POSITIVE for #11597 — ONLY a shared library's src/main. lib-svc is pinned at c3,
# so nothing under its own directory moved: the pre-fix probe called it up to date forever.
echo "v2" > openbank-libs-domain/src/main/kotlin/Lib.kt
commit "c4: shared lib src/main only"
C4="$(short HEAD)"

# c5: KNOWN-NEGATIVES — only docs, then only ANOTHER service. docs-svc is pinned at c4, so
# its pin..HEAD range holds exactly these two; it must stay up to date.
echo "v2" > docs/README.md
commit "c5a: docs only"
echo "v2" > openbank-other-svc/src/main/kotlin/App.kt
commit "c5b: another service only"
TIP="$(short HEAD)"

# --- gitops manifest (untracked: it is not part of the history being compared) ------------
# current-svc  : pinned at TIP                               -> NOT lagging
# stale-svc    : c0, own src/main moved                      -> LAGGING
# placeholder  : non-commit tag                              -> LAGGING
# testonly-svc : c0, own src/test moved                      -> LAGGING (#11597)
# release-svc  : c0, own version.txt moved                   -> LAGGING
# lib-svc      : c3, ONLY a shared lib moved                 -> LAGGING (known-positive)
# docs-svc     : c4, only docs + another service moved       -> NOT lagging (known-negative)
# py-svc       : non-Gradle, c0, its own files never moved   -> NOT lagging
# foreign-svc  : placeholder, absent from the allowlist case -> LAGGING without allowlist
mkdir -p gitops
cat > gitops/deploy.yaml <<EOF
image: repo/openbank-current-svc:sandbox-${TIP}
image: repo/openbank-stale-svc:sandbox-${C0}
image: repo/openbank-placeholder-svc:sandbox-pending
image: repo/openbank-testonly-svc:sandbox-${C0}
image: repo/openbank-release-svc:sandbox-${C0}
image: repo/openbank-lib-svc:sandbox-${C3}
image: repo/openbank-docs-svc:sandbox-${C4}
image: repo/openbank-py-svc:sandbox-${C0}
image: repo/openbank-foreign-svc:sandbox-pending
EOF

# --- run + assert (no allowlist: every manifest service is a candidate) -------------------
WANT='["openbank-foreign-svc","openbank-lib-svc","openbank-placeholder-svc","openbank-release-svc","openbank-stale-svc","openbank-testonly-svc"]'
GOT="$(RECONCILE_SERVICES='' bash "$PROBE" "$WORK/gitops")"
[ "$(sorted "$GOT")" = "$(sorted "$WANT")" ] || fail "reconcile lag probe" "$WANT" "$GOT"

# The known-positive and known-negative restated alone, so a regression names itself.
jq -e 'index("openbank-lib-svc")' <<< "$GOT" >/dev/null \
  || fail "shared-lib-only change must lag (#11597 known-positive)" "contains openbank-lib-svc" "$GOT"
if jq -e 'index("openbank-docs-svc")' <<< "$GOT" >/dev/null; then
  fail "docs/other-service-only change must NOT lag (known-negative)" "no openbank-docs-svc" "$GOT"
fi

# CWD independence: the same answer from a subdirectory with a RELATIVE gitops root...
GOT_SUB="$(cd "$WORK/docs" && RECONCILE_SERVICES='' bash "$PROBE" ../gitops)"
[ "$(sorted "$GOT_SUB")" = "$(sorted "$WANT")" ] || fail "relative root from a subdirectory" "$WANT" "$GOT_SUB"
# ...and the DEFAULT gitops root is anchored at the repo root, not at the CWD.
mkdir -p "$WORK/openbank-infra/gitops/components"
cp gitops/deploy.yaml "$WORK/openbank-infra/gitops/components/"
GOT_DEF="$(cd "$WORK/openbank-libs-domain/src" && RECONCILE_SERVICES='' bash "$PROBE")"
rm -rf "$WORK/openbank-infra"
[ "$(sorted "$GOT_DEF")" = "$(sorted "$WANT")" ] || fail "default root from a subdirectory" "$WANT" "$GOT_DEF"

# A gitops root that does not exist must fail, never print `[]` ("all up to date").
if bash "$PROBE" "$WORK/no-such-dir" >/dev/null 2>&1; then
  echo "FAIL: a missing gitops root exited 0 — a probe that never looked reported clean"; exit 1
fi

# The cap: oldest pin first (placeholders sort as epoch 0), the rest deferred to later ticks.
WANT_CAP='["openbank-foreign-svc","openbank-placeholder-svc"]'
GOT_CAP="$(RECONCILE_TICK=0 RECONCILE_MAX=2 RECONCILE_SERVICES='' bash "$PROBE" "$WORK/gitops" 2>/dev/null)"
[ "$(sorted "$GOT_CAP")" = "$(sorted "$WANT_CAP")" ] || fail "RECONCILE_MAX cap" "$WANT_CAP" "$GOT_CAP"

# FAIRNESS known-positive (2026-10-02 starvation): 15 lagging services, cap 12, and the 12
# oldest are never deployed (the gate keeps blocking them, so they stay lagging with the same
# pins). A fixed oldest-first top-12 selects them every tick and s13..s15 starve forever; the
# rotating window must reach all three within ceil(15/12) = 2 consecutive ticks.
mkdir -p "$WORK/fair"
: > "$WORK/fair/deploy.yaml"
for i in 01 02 03 04 05 06 07 08 09 10 11 12 13 14 15; do
  echo "image: repo/openbank-s${i}:sandbox-pending" >> "$WORK/fair/deploy.yaml"
done
T0="$(RECONCILE_TICK=41 RECONCILE_MAX=12 RECONCILE_SERVICES='' bash "$PROBE" "$WORK/fair" 2>/dev/null)"
T1="$(RECONCILE_TICK=42 RECONCILE_MAX=12 RECONCILE_SERVICES='' bash "$PROBE" "$WORK/fair" 2>/dev/null)"
for want in openbank-s13 openbank-s14 openbank-s15; do
  jq -se --arg w "$want" 'add | index($w)' <<< "$T0 $T1" >/dev/null \
    || fail "fair cap: ${want} must be selected within 2 ticks" "contains ${want}" "tick41=$T0 tick42=$T1"
done
[ "$(jq length <<< "$T0")" = 12 ] && [ "$(jq length <<< "$T1")" = 12 ] \
  || fail "fair cap keeps the cap" "12 per tick" "tick41=$T0 tick42=$T1"

# PRIORITY known-positive (2026-10-15 MGM strand): a service whose OWN source moved since
# its pin outranks a marker-only lagger of the SAME age when the cap forces a choice.
# release-svc and stale-svc are both pinned at c0, so pure oldest-first falls to the
# alphabetical tiebreak and would pick release-svc — but release-svc lags only through
# version.txt (class 1, marker drift) while stale-svc lags through src/main (class 0,
# own-source drift). Code that is running nowhere must not wait behind a version string.
mkdir -p "$WORK/prio"
cat > "$WORK/prio/deploy.yaml" <<EOF
image: repo/openbank-release-svc:sandbox-${C0}
image: repo/openbank-stale-svc:sandbox-${C0}
image: repo/openbank-lib-svc:sandbox-${C3}
EOF
GOT_PRIO="$(RECONCILE_TICK=0 RECONCILE_MAX=1 RECONCILE_SERVICES='' bash "$PROBE" "$WORK/prio" 2>/dev/null)"
if [ "$(echo "$GOT_PRIO" | jq -c .)" != '["openbank-stale-svc"]' ]; then
  echo "FAIL: own-source drift must outrank older marker drift under the cap (MGM strand)"
  echo "  want: [\"openbank-stale-svc\"]"; echo "  got:  $GOT_PRIO"; exit 1
fi

# CLASS-SPLIT FAIRNESS: with class 0 below the cap, the remaining slots rotate across the
# marker class — marker lag still drains every tick and can never crowd out code drift.
# cap 2, one code lagger (stale-svc), two markers (release-svc@C0 oldest, lib-svc@C3): class 0
# takes one slot every tick; the marker window covers both markers across two consecutive ticks.
PT0="$(RECONCILE_TICK=0 RECONCILE_MAX=2 RECONCILE_SERVICES='' bash "$PROBE" "$WORK/prio" 2>/dev/null)"
PT1="$(RECONCILE_TICK=1 RECONCILE_MAX=2 RECONCILE_SERVICES='' bash "$PROBE" "$WORK/prio" 2>/dev/null)"
for want in openbank-stale-svc openbank-release-svc openbank-lib-svc; do
  jq -se --arg w "$want" 'add | index($w)' <<< "$PT0 $PT1" >/dev/null \
    || fail "class-split cap: ${want} must be selected within 2 ticks" "contains ${want}" "tick0=$PT0 tick1=$PT1"
done
jq -e 'index("openbank-stale-svc")' <<< "$PT0" >/dev/null \
  && jq -e 'index("openbank-stale-svc")' <<< "$PT1" >/dev/null \
  || fail "class 0 fills its slot every tick" "stale-svc in both ticks" "tick0=$PT0 tick1=$PT1"
[ "$(jq length <<< "$PT0")" = 2 ] && [ "$(jq length <<< "$PT1")" = 2 ] \
  || fail "class-split cap keeps the cap" "2 per tick" "tick0=$PT0 tick1=$PT1"

# An allowlist (auto-deploy's ALL_SERVICES) drops manifest services the build path cannot
# build — foreign-svc is stale but absent from the list.
WANT_AL='["openbank-placeholder-svc","openbank-stale-svc"]'
GOT_AL="$(RECONCILE_SERVICES='openbank-stale-svc openbank-placeholder-svc openbank-current-svc' \
            bash "$PROBE" "$WORK/gitops")"
[ "$(sorted "$GOT_AL")" = "$(sorted "$WANT_AL")" ] || fail "allowlist filter" "$WANT_AL" "$GOT_AL"

# Explicit targets narrow the stale candidate set BEFORE ranking/capping. A targeted manual
# reconcile can therefore re-offer one stranded image without building the other laggers.
GOT_TARGET="$(RECONCILE_SERVICES='openbank-stale-svc openbank-lib-svc openbank-current-svc' \
  RECONCILE_TARGETS='openbank-lib-svc' RECONCILE_MAX=1 \
  bash "$PROBE" "$WORK/gitops" 2>/dev/null)"
[ "$(jq -c . <<< "$GOT_TARGET")" = '["openbank-lib-svc"]' ] \
  || fail "target filter runs before cap and returns only requested stale service" \
    '["openbank-lib-svc"]' "$GOT_TARGET"
GOT_TARGET_SET="$(RECONCILE_SERVICES='openbank-stale-svc openbank-lib-svc openbank-current-svc' \
  RECONCILE_TARGETS='openbank-lib-svc openbank-stale-svc' \
  bash "$PROBE" "$WORK/gitops" 2>/dev/null)"
[ "$(sorted "$GOT_TARGET_SET")" = '["openbank-lib-svc","openbank-stale-svc"]' ] \
  || fail "multiple explicitly targeted stale services are selected together" \
    '["openbank-lib-svc","openbank-stale-svc"]' "$GOT_TARGET_SET"
GOT_TARGET_CURRENT="$(RECONCILE_SERVICES='openbank-current-svc' \
  RECONCILE_TARGETS='openbank-current-svc' bash "$PROBE" "$WORK/gitops" 2>/dev/null)"
[ "$(jq -c . <<< "$GOT_TARGET_CURRENT")" = '[]' ] \
  || fail "requested up-to-date service is not rebuilt" '[]' "$GOT_TARGET_CURRENT"

expect_target_failure() {
  local label="$1" allowlist="$2" targets="$3"
  if RECONCILE_SERVICES="$allowlist" RECONCILE_TARGETS="$targets" \
       bash "$PROBE" "$WORK/gitops" >/dev/null 2>&1; then
    echo "FAIL: targeted reconcile accepted ${label}"; exit 1
  fi
}
expect_target_failure "an unbuildable/unknown service" \
  'openbank-stale-svc' 'openbank-foreign-svc'
expect_target_failure "a service with no GitOps image pin" \
  'openbank-no-manifest-svc' 'openbank-no-manifest-svc'
expect_target_failure "duplicate service names" \
  'openbank-stale-svc' 'openbank-stale-svc openbank-stale-svc'
expect_target_failure "more than three services" \
  'openbank-stale-svc openbank-lib-svc openbank-placeholder-svc openbank-current-svc' \
  'openbank-stale-svc openbank-lib-svc openbank-placeholder-svc openbank-current-svc'
expect_target_failure "a selector without the buildable allowlist" \
  '' 'openbank-stale-svc'
expect_target_failure "a malformed service name" \
  'openbank-stale-svc' '../openbank-stale-svc'

# Keep the workflow input wired only through the reconcile path and anchored to current main.
WORKFLOW="${SCRIPT_DIR}/../workflows/auto-deploy.yml"
grep -Fq 'reconcile_services:' "$WORKFLOW" \
  || { echo 'FAIL: workflow_dispatch has no reconcile_services input'; exit 1; }
grep -Fq 'INPUT_RECONCILE_SERVICES: ${{ github.event.inputs.reconcile_services }}' "$WORKFLOW" \
  || { echo 'FAIL: workflow does not pass reconcile_services to the selector'; exit 1; }
grep -Fq 'reconcile_services requires reconcile=true' "$WORKFLOW" \
  || { echo 'FAIL: workflow accepts reconcile_services outside reconciliation'; exit 1; }
grep -Fq 'targeted reconcile requires this run'"'"'s source SHA to be current main' "$WORKFLOW" \
  || { echo 'FAIL: targeted reconciliation is not pinned to current main'; exit 1; }
grep -Fq 'targeted reconcile cannot use co-deploy or Pact replay inputs' "$WORKFLOW" \
  || { echo 'FAIL: targeted reconciliation does not reject co-deploy/Pact replay'; exit 1; }
grep -Fq 'targeted reconcile cannot be combined with bootstrap_image_only' "$WORKFLOW" \
  || { echo 'FAIL: targeted reconciliation does not reject image-only bootstrap'; exit 1; }

# A held placeholder would rank first. It must be removed BEFORE the one-service
# cap so the healthy stale service gets this tick. Once the hold is removed, the
# still-stale placeholder is offered again without any special recovery action.
# Pin the rotation tick: with two candidates and cap 1, wall-clock parity otherwise
# alternates the winner and makes this assertion fail every other three-hour bucket.
GOT_HELD="$(RECONCILE_SERVICES='openbank-placeholder-svc openbank-stale-svc' \
  RECONCILE_HOLDS='openbank-placeholder-svc' RECONCILE_MAX=1 RECONCILE_TICK=0 bash "$PROBE" "$WORK/gitops")"
if [ "$(echo "$GOT_HELD" | jq -c .)" != '["openbank-stale-svc"]' ]; then
  echo "FAIL: held service consumed the reconcile cap: $GOT_HELD"
  exit 1
fi
GOT_RELEASED="$(RECONCILE_SERVICES='openbank-placeholder-svc openbank-stale-svc' \
  RECONCILE_HOLDS='' RECONCILE_MAX=1 RECONCILE_TICK=0 bash "$PROBE" "$WORK/gitops")"
if [ "$(echo "$GOT_RELEASED" | jq -c .)" != '["openbank-placeholder-svc"]' ]; then
  echo "FAIL: released service was not re-offered: $GOT_RELEASED"
  exit 1
fi

# With every pin at TIP, nothing lags (loop-stability guarantee).
cat > gitops/deploy.yaml <<EOF
image: repo/openbank-current-svc:sandbox-${TIP}-run32826611610
image: repo/openbank-stale-svc:sandbox-${TIP} # formerly openbank-stale-svc:sandbox-${C0}
image: repo/openbank-lib-svc:sandbox-${TIP}
# Incident notes may mention a former image tag. They are not deployed images and must
# never re-drive a service on every schedule tick.
# formerly openbank-stale-svc:sandbox-${C0}
EOF
GOT2="$(bash "$PROBE" "$WORK/gitops")"
if [ "$(jq -c . <<< "$GOT2")" != "[]" ]; then
  echo "FAIL: current pins, including a manual-refresh tag, must not lag (would loop). got: $GOT2"
  exit 1
fi

echo "PASS: auto-deploy-reconcile-lag.sh — shared-lib known-positive, docs/other-service known-negative, own tests, release marker, non-Gradle, CWD, missing root, cap, fair rotation, allowlist, target-only stale selection/validation, loop-stability"
