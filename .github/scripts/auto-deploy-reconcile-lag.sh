#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
# See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
#
# ---------------------------------------------------------------------------------------
# Reconcile probe for the Auto-deploy pipeline (rules.yaml: deploy_reconcile).
#
# Prints a JSON array of deployable services whose *currently-deployed* gitops image tag
# is STALE relative to main — i.e. build-relevant source has landed on main since the
# commit that image was built from. The scheduled `changes` branch in auto-deploy.yml
# feeds this list back through the normal build-push -> can-i-deploy -> gitops-pr path.
#
# THE FAILURE THIS CATCHES
# The per-push auto-deploy run is ONE-SHOT. A service can be correctly detected, built and
# pushed, then blocked at the can-i-deploy contract gate by a *transient* verification lag
# — its own build wave republished its consumer pact, and the counterpart provider version
# then running in sandbox had not verified that pact yet (the provider's verification lands
# minutes later, on its own auto-deploy). The gate's "no verified pact between the latest
# main consumer and the version currently in sandbox" verdict is CORRECT at that instant,
# but nothing ever re-drives the deploy once the provider catches up: the service stays
# pinned to its stale image until a human hand-bumps gitops. That is exactly how #1990's
# notification-service change (V11 notification_preferences + /api/v1/preferences) built
# image sandbox-6de0d5e9 yet ran the pre-#1990 image until PR #2017 hand-bumped it — the
# broker now reports the pair verified/deployable, proving the block was a timing race, not
# a real contract break (issue #2020).
#
# WHY THIS IS NOT "weaken the gate"
# The gate is untouched: a service that is still genuinely blocked stays blocked (and keeps
# the #1420 left-behind escalation) on every reconcile tick. This only re-OFFERS a stranded
# service to the same gate, so it deploys the moment — and only the moment — the gate clears.
#
# DEPLOYABILITY IS DERIVED, NOT LISTED
# The candidate set is enumerated from the gitops manifests themselves (every `openbank-*
# :sandbox-*` image pin), intersected with the buildable fleet passed in RECONCILE_SERVICES.
# The list is NOT re-declared here: auto-deploy.yml's ALL_SERVICES already drifts
# (check-deploy-coverage.sh, #1205), so a second copy would add a second thing to drift —
# the workflow passes its own ALL_SERVICES straight through. The intersection matters: a
# service can carry a sandbox pin yet be built by a DIFFERENT pipeline and be absent from
# ALL_SERVICES (analytics-sink, developer-portal today); re-driving it through this build
# path would try to fast-jar a module the path cannot build. When RECONCILE_SERVICES is
# empty (standalone/test use) no allowlist is applied — every manifest service is a
# candidate, which is what the unit test exercises.
#
# STALE = the pinned commit and HEAD are NOT the same artifact for that service. "Same
# artifact" has exactly ONE definition in this repo, pact-version-tree-equivalent.sh (#3432):
# the service's own directory, the compile inputs of every openbank-libs* module and every
# `project(":…")` dependency, build-logic/, gradle/, root build config and Dockerfile.deploy,
# compared as git tree objects, with an unrecognised path failing CLOSED (= lagging). This
# probe calls it instead of keeping a second path list, because the second list is what broke
# (#11597): it named only <svc>/src/main + a few build files, so a change that reached the
# image ONLY through a shared library, build-logic or the version catalog was invisible here.
# The push path does fan those out (libs-change-dependents.sh), but that run is one-shot: on
# 2026-09-29 both the #9145 (libs-domain src/main) and #10276 (build-logic) fan-out runs died
# in `Build + push` (attestation step timeout; fleet attestation gate) before can-i-deploy,
# and no reconcile tick could ever re-offer ledger, pinned at sandbox-a5fb1b4f, again. The
# service's own src/test is in scope too (its tree is compared whole): a provider's @State
# handlers live there, and the broker only learns them when that version is deployed.
#
# WAVE BEHAVIOUR. A single shared-library commit makes most of the fleet non-equivalent at
# once. RECONCILE_MAX (below) is what spreads that: oldest pin first, at most N per tick, the
# rest logged as deferred and picked up on the next tick (every 3h). Only app images move —
# the probe reads `openbank-*:sandbox-*` image fields, which exist solely in Deployment and
# Rollout manifests; no CNPG Cluster, and so no money-path database pod, is ever rewritten.
#
# Modules that are not Gradle modules (no build.gradle.kts at HEAD — the Python renderer)
# cannot be classified by the equivalence script, which would refuse them forever and
# re-drive them every tick. For those alone the probe keeps the push trigger's own globs.
#
# This is stable: a service re-driven to sandbox-<tip> is equivalent to tip by construction
# (identical commit), and stays equivalent while main moves through paths that cannot reach
# its image, so it does not loop. A pin that is not a real commit (placeholder such as sandbox-pending
# / sandbox-init) always counts as stale — it has never been deployed for real.
#
# Usage: auto-deploy-reconcile-lag.sh [gitops-root]
#   gitops-root defaults to openbank-infra/gitops/components
#   RECONCILE_SERVICES (env): space-separated buildable allowlist (auto-deploy's
#     ALL_SERVICES). When set, only these services are considered. When empty, no
#     allowlist filter is applied.
#   RECONCILE_HOLDS (env): space-separated services held by live main governance.
#     Excluded before the oldest-first cap, so a held service cannot consume a slot.
# Must run inside a full-history checkout (fetch-depth: 0) with main checked out. Any CWD
# inside that checkout works: the probe anchors itself at the repository root (a relative
# gitops-root argument is resolved against the caller's CWD first).
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
EQUIV="${SCRIPT_DIR}/pact-version-tree-equivalent.sh"
[ -f "$EQUIV" ] || { echo "::error::missing ${EQUIV} — cannot decide artifact equivalence" >&2; exit 2; }

REPO_ROOT="$(git rev-parse --show-toplevel 2>/dev/null)" \
  || { echo "::error::not inside a git checkout — run from the repository" >&2; exit 2; }
if [ -n "${1:-}" ]; then
  case "$1" in
    /*) GITOPS_ROOT="$1" ;;
    *)  GITOPS_ROOT="$(cd "$1" 2>/dev/null && pwd)" \
          || { echo "::error::gitops root '$1' does not exist" >&2; exit 2; } ;;
  esac
else
  GITOPS_ROOT="${REPO_ROOT}/openbank-infra/gitops/components"
fi
# A missing gitops root would make the grep below find nothing and print `[]` — "everything up
# to date" from a probe that never looked. Refuse instead.
[ -d "$GITOPS_ROOT" ] || { echo "::error::gitops root ${GITOPS_ROOT} is not a directory" >&2; exit 2; }
cd "$REPO_ROOT"
HEAD_SHA="$(git rev-parse HEAD)"
# Newline-delimited allowlist for O(1) membership tests; empty => no filter.
ALLOWLIST="$(tr -s ' \t' '\n' <<< "${RECONCILE_SERVICES:-}" | sed '/^$/d' | sort -u)"
# Cap how many stranded services one tick re-drives, oldest-deployed first, so the first
# reconcile after this lands does not fan out a whole backlog (23 services today) into a
# single build fan-out + one giant gitops PR. The remainder drain on later ticks and are
# logged, never silently dropped. 0 => unlimited.
RECONCILE_MAX="${RECONCILE_MAX:-12}"

# Each entry: "<sortkey>\t<svc>" — sortkey is the pinned commit's epoch (0 for a placeholder
# pin), so the oldest-deployed / never-deployed strands are re-driven first under the cap.
lagging=()

# Collect unique `openbank-<svc>:sandbox-<tag>` pins across every manifest.
while IFS= read -r pin; do
  [ -n "$pin" ] || continue
  svc="${pin%%:sandbox-*}"
  tag="${pin##*:sandbox-}"
  [ -n "$svc" ] && [ -n "$tag" ] || continue

  # Manual evidence refreshes rebuild an already-tested commit under ECR's immutable
  # tag policy, carrying a provenance-only `-run<GitHub run id>` suffix. Resolve the
  # commit part only; an arbitrary suffix remains a placeholder and is re-driven.
  if [[ "$tag" =~ ^([0-9a-f]{8,40})(-run[1-9][0-9]*)?$ ]]; then
    commit="${BASH_REMATCH[1]}"
  else
    commit=""
  fi

  # Restrict to the buildable fleet when an allowlist was supplied.
  if [ -n "$ALLOWLIST" ] && ! grep -qxF "$svc" <<< "$ALLOWLIST"; then
    continue
  fi
  case " ${RECONCILE_HOLDS:-} " in
    *" ${svc} "*)
      echo "::notice::reconcile held service ${svc} — excluded before cap; will re-offer after hold removal" >&2
      continue ;;
  esac

  # A pin that is not a resolvable commit is a placeholder (never really deployed) -> stale,
  # sortkey 0 so placeholders re-drive before any real-but-old pin.
  if [ -z "$commit" ] || ! git rev-parse -q --verify "${commit}^{commit}" >/dev/null 2>&1; then
    lagging+=("0	$svc")
    continue
  fi

  # Same artifact as HEAD? Exit 0 from the equivalence script means EQUIVALENT and nothing
  # else does, so a crash or an unclassifiable path lands on "lagging" — re-offering a service
  # to the unchanged gate is the cheap direction; silently never re-offering it is #11597.
  if git cat-file -e "${HEAD_SHA}:${svc}/build.gradle.kts" 2>/dev/null; then
    if bash "$EQUIV" "$svc" "$commit" "$HEAD_SHA" >/dev/null 2>&1; then
      continue
    fi
  else
    # Non-Gradle module: the push trigger's globs, including version.txt (#8127) and the
    # Python build files. Nothing else can reach that image.
    [ -n "$(git log --format=%H "${commit}..${HEAD_SHA}" -- \
              "${svc}/src/main" "${svc}/version.txt" "${svc}/Dockerfile" \
              "${svc}/app.py" "${svc}/requirements.txt" 2>/dev/null)" ] || continue
  fi
  epoch="$(git log -1 --format=%ct "${commit}^{commit}" 2>/dev/null || echo 0)"
  lagging+=("${epoch}	$svc")
done < <(
  # Only YAML image fields are deployed pins. Searching arbitrary text also picks up
  # historical tags in policy comments and re-drives that service every tick (#8690).
  grep -rhE '^[[:space:]]*image:[[:space:]]*[^#[:space:]]+' \
    "$GITOPS_ROOT" 2>/dev/null \
    | sed -nE 's/^[[:space:]]*image:[[:space:]]*([^#[:space:]]+).*/\1/p' \
    | grep -oE 'openbank-[a-z0-9-]+:sandbox-[A-Za-z0-9._-]+' \
    | sort -u
)

# Oldest-deployed first, de-duplicated by service, then apply the per-tick cap.
ranked="$(printf '%s\n' "${lagging[@]:-}" \
  | awk -F'\t' 'NF==2 && !seen[$2]++' \
  | sort -t'	' -k1,1n -k2,2)"
[ -n "$ranked" ] || { echo '[]'; exit 0; }

selected="$ranked"
if [ "$RECONCILE_MAX" -gt 0 ]; then
  total="$(printf '%s\n' "$ranked" | grep -c . || true)"
  if [ "$total" -gt "$RECONCILE_MAX" ]; then
    selected="$(printf '%s\n' "$ranked" | head -n "$RECONCILE_MAX")"
    # Log the deferred remainder to stderr — visible in the job log, never silent.
    printf '%s\n' "$ranked" | tail -n +"$((RECONCILE_MAX + 1))" | cut -f2 \
      | while IFS= read -r d; do echo "::notice::reconcile deferred (cap ${RECONCILE_MAX}) — will re-drive next tick: ${d}" >&2; done
  fi
fi

printf '%s\n' "$selected" | cut -f2 | jq -R . | jq -sc 'map(select(length > 0))'
