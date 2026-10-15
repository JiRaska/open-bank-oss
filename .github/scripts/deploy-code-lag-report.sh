#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
# See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
#
# ---------------------------------------------------------------------------------------
# Deployed-code-lag report for the code-lag watch (deploy-code-lag-watch.yml).
#
# Prints a JSON report of services whose gitops pin is behind a commit that changed the
# service's OWN source (src/, app.py, requirements.txt) OLDER than the threshold — i.e.
# code that is on main but has not been running in sandbox for hours. Marker-only drift
# (version.txt release bumps, Dockerfile, build files, shared libs) is deliberately NOT
# reported here: it has no behaviour attached, drains through the auto-deploy reconcile
# rotation, and is already watched by deploy-drift-watch on a multi-day version threshold.
#
# WHY THIS EXISTS (2026-10-15 MGM postmortem)
#   customer-edge's referralLink passthrough (#12813) built and was blocked one tick by a
#   transient can-i-deploy PENDING_BUILD, then waited ~17h behind ~40 marker-lagging
#   services in the reconcile rotation. Nothing in the repo surfaced "main has behaviour
#   the sandbox does not" on a hours scale — deploy-drift-watch compares versions on a
#   multi-day threshold, and the auto-deploy probes only print job-log notices. This
#   report is the input for the issue-escalating watch, which is the loud half.
#
# The lagging set is derived by the SAME probe the auto-deploy reconcile uses
# (auto-deploy-reconcile-lag.sh, RECONCILE_MAX=0 → no cap), so the watch can never
# disagree with the pipeline about what is stale, then each service is aged by its oldest
# own-source commit in pin..HEAD.
#
# Usage: deploy-code-lag-report.sh [threshold-hours]
#   default threshold: 6 hours
# Env: NOW_EPOCH (clock override, tests), GH-agnostic — pure git + jq.
set -euo pipefail

THRESHOLD_HOURS="${1:-${LAG_HOURS:-6}}"
NOW_EPOCH="${NOW_EPOCH:-$(date -u +%s)}"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROBE="${SCRIPT_DIR}/auto-deploy-reconcile-lag.sh"
REPO_ROOT="$(git rev-parse --show-toplevel 2>/dev/null)" \
  || { echo "::error::not inside a git checkout" >&2; exit 2; }
cd "$REPO_ROOT"
GITOPS_ROOT="openbank-infra/gitops/components"
[ -d "$GITOPS_ROOT" ] || { echo "::error::${GITOPS_ROOT} is not a directory" >&2; exit 2; }

threshold_seconds=$(( THRESHOLD_HOURS * 3600 ))

# Same lagging set the reconcile would re-drive, uncapped, unfiltered.
lagging_json="$(RECONCILE_MAX=0 RECONCILE_SERVICES='' bash "$PROBE" "$GITOPS_ROOT" 2>/dev/null)"

entries="$(jq -r '.[]' <<< "$lagging_json" | while IFS= read -r svc; do
  # Resolve the deployed pin for this service from the manifest image fields (same
  # extraction rule as the probe).
  tag="$(grep -rhE '^[[:space:]]*image:[[:space:]]*[^#[:space:]]+' "$GITOPS_ROOT" 2>/dev/null \
          | sed -nE 's/^[[:space:]]*image:[[:space:]]*([^#[:space:]]+).*/\1/p' \
          | grep -oE "${svc}:sandbox-[A-Za-z0-9._-]+" | head -1 | sed "s|^${svc}:sandbox-||")"
  [ -n "$tag" ] || continue
  if [[ "$tag" =~ ^([0-9a-f]{8,40})(-run[1-9][0-9]*)?$ ]]; then
    commit="${BASH_REMATCH[1]}"
  else
    # Placeholder pin (never deployed): every commit is "not running" — age from the
    # oldest own-source commit on main.
    commit=""
  fi

  if git cat-file -e "HEAD:${svc}/build.gradle.kts" 2>/dev/null; then
    own_paths=("${svc}/src")
  else
    own_paths=("${svc}/src" "${svc}/app.py" "${svc}/requirements.txt")
  fi

  if [ -n "$commit" ] && git rev-parse -q --verify "${commit}^{commit}" >/dev/null 2>&1; then
    range="${commit}..HEAD"
  else
    range="HEAD"
  fi
  # Oldest own-source commit in the lag window.
  read -r oldest_sha oldest_ct <<< "$(git log --reverse --format='%H %ct' "$range" -- \
      "${own_paths[@]}" 2>/dev/null | head -1 || true)"
  [ -n "${oldest_sha:-}" ] || continue   # lagging only through markers; not this watch lane

  age_seconds=$(( NOW_EPOCH - oldest_ct ))
  [ "$age_seconds" -ge "$threshold_seconds" ] || continue
  jq -nc --arg svc "$svc" --arg tag "$tag" --arg pin "${commit:-placeholder}" \
        --arg sha "$oldest_sha" --argjson age_h $(( age_seconds / 3600 )) \
        '{service:$svc, deployed_tag:$tag, pinned_commit:$pin,
          oldest_src_commit:$sha, oldest_src_age_hours:$age_h}'
done)"

jq -sc --argjson threshold "$THRESHOLD_HOURS" \
   '{threshold_hours:$threshold, entries:.}' <<< "${entries:-}" | jq -c .
