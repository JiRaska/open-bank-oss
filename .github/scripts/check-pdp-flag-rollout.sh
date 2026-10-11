#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
# See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
#
# Is every PEP in the fleet sending the verified `input.principal.service_account` flag?
#
# WHY THIS EXISTS
# The flag ships in two phases. Phase 1 makes openbank-libs-runtime's AuthorizeInterceptor SEND
# `service_account`/`client_id` (policies ignore them). Phase 2 switches the rego to read the flag
# and, failing closed, treats a principal WITHOUT it as a machine, which removes every staff grant.
# A bundle pod-roll reaches every service at once, but a service only sends the flag once its image
# has been rebuilt on the Phase 1 libs. So Phase 2 is safe only after EVERY libs-runtime consumer
# runs an image built from a commit that contains Phase 1. This script answers that question and
# exits non-zero while any service lags. Phase 2's gate runs it, and so does the owner before
# merging.
#
# WHAT "CONTAINS PHASE 1" MEANS
# The marker commit is DERIVED, not hard-coded: the first commit on this history whose
# OpaSidecarPolicyDecisionPoint.kt puts "service_account" into the OPA input. A pinned image
# `openbank-<svc>:sandbox-<sha>[-run<id>]` is current iff that marker is an ancestor of <sha>.
# A pin that cannot be resolved to a commit (a placeholder, or a commit outside this clone) counts
# as LAGGING. "Could not look" must never read as "fine".
#
# SOURCES
#   (default)  the gitops manifests: the DESIRED state, which CI can read.
#   --live     `kubectl get pods -A`: the RUNNING state. The owner runs this before merging
#              Phase 2. A green Argo app can still be running an old ReplicaSet.
#
# Usage:
#   bash .github/scripts/check-pdp-flag-rollout.sh            # gitops pins, exit 1 while any lag
#   bash .github/scripts/check-pdp-flag-rollout.sh --live     # running pods (needs kubectl context)
#   bash .github/scripts/check-pdp-flag-rollout.sh --self-test
#
# History: with full history the ancestry test is `git merge-base --is-ancestor`. In a shallow
# clone (the CI gates shard checks out at fetch-depth: 1) it asks the GitHub compare API instead,
# which needs `gh` and GH_TOKEN; without them a shallow clone is refused, never read as clean.
set -euo pipefail

MARKER_FILE="openbank-libs-runtime/src/main/kotlin/com/openbank/libs/authz/OpaSidecarPolicyDecisionPoint.kt"
MARKER_TEXT='put("service_account"'
GITOPS_ROOT="${GITOPS_ROOT:-openbank-infra/gitops}"

cd "$(git rev-parse --show-toplevel)"

REPO="${GITHUB_REPOSITORY:-JiRaska/open-bank-oss}"
API=0

# First commit (oldest) on HEAD's history that introduced the marker text.
marker_commit() {
  if [ "$API" -eq 0 ]; then
    git log --format=%H --reverse -S"$MARKER_TEXT" HEAD -- "$MARKER_FILE" | head -1
    return
  fi
  # Commits touching the file, newest first; the marker is the oldest whose content has the text.
  local found="" sha
  for sha in $(gh api "repos/${REPO}/commits?path=${MARKER_FILE}&sha=$(git rev-parse HEAD)&per_page=100" --jq '.[].sha'); do
    if gh api -H 'Accept: application/vnd.github.raw' "repos/${REPO}/contents/${MARKER_FILE}?ref=${sha}" \
        | grep -qF "$MARKER_TEXT"; then
      found="$sha"
    else
      break
    fi
  done
  printf '%s\n' "$found"
}

# 0 iff $1 is an ancestor of (or equal to) $2.
is_ancestor() {
  if [ "$API" -eq 0 ]; then
    git merge-base --is-ancestor "$1" "$2"
    return
  fi
  local status
  status="$(gh api "repos/${REPO}/compare/${1}...${2}" --jq .status 2>/dev/null)" || return 1
  [ "$status" = "ahead" ] || [ "$status" = "identical" ]
}

# Services whose image embeds libs-runtime: the same derivation Auto deploy uses to rebuild them.
consumers() {
  local all
  all="$(find . -maxdepth 2 -name build.gradle.kts -path './openbank-*' | cut -d/ -f2 | sort -u | tr '\n' ' ')"
  printf '%s\n' "$MARKER_FILE" | bash .github/scripts/libs-change-dependents.sh "$all" | tr ' ' '\n' | grep -v '^$' | sort -u
}

pins_gitops() {
  grep -rhE '^[[:space:]]*image:[[:space:]]*[^#[:space:]]+' "$GITOPS_ROOT" 2>/dev/null \
    | sed -nE 's/^[[:space:]]*image:[[:space:]]*([^#[:space:]]+).*/\1/p' \
    | grep -oE 'openbank-[a-z0-9-]+:sandbox-[A-Za-z0-9._-]+' | sort -u
}

pins_live() {
  kubectl get pods -A -o jsonpath='{range .items[*]}{range .spec.containers[*]}{.image}{"\n"}{end}{end}' \
    | grep -oE 'openbank-[a-z0-9-]+:sandbox-[A-Za-z0-9._-]+' | sort -u
}

# 0 = the pinned tag is built from a commit containing $marker; 1 = it is not, or cannot be resolved.
pin_is_current() {
  local marker="$1" tag="$2" commit
  [[ "$tag" =~ ^([0-9a-f]{8,40})(-run[1-9][0-9]*)?$ ]] || return 1
  commit="${BASH_REMATCH[1]}"
  if [ "$API" -eq 0 ]; then
    git rev-parse -q --verify "${commit}^{commit}" >/dev/null 2>&1 || return 1
  fi
  is_ancestor "$marker" "$commit"
}

run() {
  local source="$1" marker svc tag lag=0 ok=0 missing=0
  [ "${PDP_ROLLOUT_FORCE_API:-0}" = 1 ] && API=1
  if [ "$(git rev-parse --is-shallow-repository)" = "true" ]; then
    if command -v gh >/dev/null 2>&1 && [ -n "${GH_TOKEN:-}${GITHUB_TOKEN:-}" ]; then
      API=1
      echo "shallow clone: ancestry via the GitHub compare API (${REPO})"
    else
      echo "::error::shallow clone and no gh/GH_TOKEN: the ancestry test cannot run. Use fetch-depth: 0." >&2
      return 2
    fi
  fi
  marker="$(marker_commit)"
  if [ -z "$marker" ]; then
    echo "::error::Phase 1 (libs-runtime sending service_account) is not on this history: no commit adds '${MARKER_TEXT}' to ${MARKER_FILE}." >&2
    return 1
  fi
  echo "marker commit (Phase 1): $marker"
  local pins
  if [ "$source" = live ]; then pins="$(pins_live)"; else pins="$(pins_gitops)"; fi
  while IFS= read -r svc; do
    [ -n "$svc" ] || continue
    tag="$(grep -E "^${svc}:sandbox-" <<< "$pins" | sed -E 's/^[^:]+:sandbox-//' || true)"
    if [ -z "$tag" ]; then
      # A consumer with no sandbox pin is not deployed by this pipeline; nothing to roll.
      missing=$((missing + 1))
      continue
    fi
    # Every distinct pin must be current (a rollout can leave two tags live at once).
    while IFS= read -r t; do
      if pin_is_current "$marker" "$t"; then
        ok=$((ok + 1))
      else
        lag=$((lag + 1))
        echo "LAGGING ${svc}:sandbox-${t}  (built before ${marker:0:10}, or unresolvable)"
      fi
    done <<< "$tag"
  done < <(consumers)
  echo "check-pdp-flag-rollout (${source}): current=${ok} lagging=${lag} consumers-without-a-pin=${missing}"
  if [ "$ok" -eq 0 ] && [ "$lag" -eq 0 ]; then
    echo "::error::no libs-runtime consumer pin found at all. The derivation is broken, not the fleet." >&2
    return 1
  fi
  [ "$lag" -eq 0 ]
}

selftest() {
  local fail=0 marker parent
  marker="$(git rev-parse HEAD)"
  parent="$(git rev-parse HEAD~1)"
  pin_is_current "$marker" "${marker:0:12}" || { echo "selftest FAIL: HEAD pin not current vs HEAD marker" >&2; fail=1; }
  pin_is_current "$marker" "${marker:0:12}-run42" || { echo "selftest FAIL: -run suffix not accepted" >&2; fail=1; }
  if pin_is_current "$marker" "${parent:0:12}"; then echo "selftest FAIL: an OLDER pin read as current" >&2; fail=1; fi
  if pin_is_current "$marker" "000000000"; then echo "selftest FAIL: a placeholder read as current" >&2; fail=1; fi
  if pin_is_current "$marker" "deadbeefdeadbeef"; then echo "selftest FAIL: an unresolvable pin read as current" >&2; fail=1; fi
  consumers | grep -qx openbank-tax-reporting-service || { echo "selftest FAIL: tax-reporting not derived as a libs-runtime consumer" >&2; fail=1; }
  [ "$fail" -eq 0 ] && echo "check-pdp-flag-rollout --self-test: PASS"
  return "$fail"
}

case "${1:-}" in
  --self-test) selftest ;;
  --live) run live ;;
  "") run gitops ;;
  *) echo "usage: $0 [--live|--self-test]" >&2; exit 2 ;;
esac
