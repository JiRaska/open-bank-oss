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
# STALE = there is a build-relevant main commit AFTER the pinned commit. This is stable: a
# service re-driven to sandbox-<tip> reports no lag next tick (nothing is newer than tip),
# so it does not loop. A pin that is not a real commit (placeholder such as sandbox-pending
# / sandbox-init) always counts as stale — it has never been deployed for real.
#
# Usage: auto-deploy-reconcile-lag.sh [gitops-root]
#   gitops-root defaults to openbank-infra/gitops/components
#   RECONCILE_SERVICES (env): space-separated buildable allowlist (auto-deploy's
#     ALL_SERVICES). When set, only these services are considered. When empty, no
#     allowlist filter is applied.
# Must run inside a full-history checkout (fetch-depth: 0) with main checked out.
set -euo pipefail

GITOPS_ROOT="${1:-openbank-infra/gitops/components}"
# Newline-delimited allowlist for O(1) membership tests; empty => no filter.
ALLOWLIST="$(printf '%s\n' ${RECONCILE_SERVICES:-} | sort -u)"
# Cap how many stranded services one tick re-drives, oldest-deployed first, so the first
# reconcile after this lands does not fan out a whole backlog (23 services today) into a
# single build fan-out + one giant gitops PR. The remainder drain on later ticks and are
# logged, never silently dropped. 0 => unlimited.
RECONCILE_MAX="${RECONCILE_MAX:-12}"

# Build-relevant paths per service — mirrors the auto-deploy.yml push-trigger globs
# (openbank-*/src/main/**, plus the module build file). Shared-lib changes are deliberately
# NOT considered here: they already fan out to the whole fleet on the push path, and folding
# them in would make every lib commit re-drive every service through reconcile too.
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

  # A pin that is not a resolvable commit is a placeholder (never really deployed) -> stale,
  # sortkey 0 so placeholders re-drive before any real-but-old pin.
  if [ -z "$commit" ] || ! git rev-parse -q --verify "${commit}^{commit}" >/dev/null 2>&1; then
    lagging+=("0	$svc")
    continue
  fi

  # Any build-relevant commit on this checkout's HEAD since the pinned image was built?
  if [ -n "$(git log --format=%H "${commit}..HEAD" -- \
              "${svc}/src/main" "${svc}/build.gradle.kts" 2>/dev/null)" ]; then
    epoch="$(git log -1 --format=%ct "${commit}^{commit}" 2>/dev/null || echo 0)"
    lagging+=("${epoch}	$svc")
  fi
done < <(
  grep -rhoE 'openbank-[a-z0-9-]+:sandbox-[A-Za-z0-9._-]+' "$GITOPS_ROOT" 2>/dev/null \
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
