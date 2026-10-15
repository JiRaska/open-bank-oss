#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
# See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
#
# ---------------------------------------------------------------------------------------
# Deploy-PR branch updater (rules.yaml: deploy_pr_updater).
#
# THE FAILURE THIS FIXES (2026-10-15, MGM #12813 deploy)
#   Bot deploy PRs (`chore/admin-ui: deploy …`, `chore(gitops): auto-deploy …`) are
#   created with auto-merge armed, and then left to GitHub. The main-protection ruleset
#   requires the 8 status-check contexts with `strict_required_status_checks_policy: true`
#   — the head MUST contain current main. Plain pull-request auto-merge does NOT update
#   the branch to achieve that: every push to main flips the PR to BEHIND, auto-merge
#   keeps "waiting", and in a repo where main moves several times a day the deploy PR
#   can sit unmergeable for many hours while the deployment it pins never lands. That is
#   exactly what happened to admin-ui deploy PR #12928 on 2026-10-15: green checks, armed
#   auto-merge, BEHIND for 4+ hours, landing page 307 the whole time. A human merged it
#   by hand. Nothing in the repo noticed.
#
# WHAT THIS DOES
#   On every push to main (and on an hourly schedule), update the branch of every OPEN
#   bot deploy PR whose mergeStateStatus is BEHIND or UNKNOWN and whose head commit is
#   older than the throttle window (default 45 min). Updating via a merge commit is what
#   the GitHub "Update branch" button does; it re-triggers the required checks, and the
#   armed auto-merge completes the moment they pass with an up-to-date head.
#
# WHY THE THROTTLE
#   Each update cancels the PR's in-flight checks and re-queues a full congested CI run.
#   Updating on every push would keep the deploy PR's checks permanently red and burn the
#   queue. 45 min bounds the churn while still converging in well under one reconcile
#   window. UNKNOWN is included because a PR whose checks never registered (e.g. the
#   cancelled-run shape behind #12928's "8 of 8 required status checks are expected")
#   also never merges on its own; the update re-registers them.
#
# SCOPE — bot deploy PRs ONLY. Head-ref prefix match (`chore/admin-ui-deploy-`,
# `chore/gitops-auto-deploy-`), never title search: a feature branch can carry any title,
# and this script must never touch a human's PR. Both prefixes are exclusively created by
# the deploy workflows (admin-ui-deploy.yml, auto-deploy.yml).
#
# Env:
#   GITHUB_REPOSITORY  owner/repo (required)
#   GH_BIN             gh executable (default: gh; tests point this at a fixture)
#   MIN_HEAD_AGE_MINUTES  throttle window (default 45)
#   NOW_EPOCH          clock override for tests (default: date -u +%s)
set -euo pipefail

GH_BIN="${GH_BIN:-gh}"
REPO="${GITHUB_REPOSITORY:?GITHUB_REPOSITORY (owner/repo) is required}"
MIN_HEAD_AGE_MINUTES="${MIN_HEAD_AGE_MINUTES:-45}"
NOW_EPOCH="${NOW_EPOCH:-$(date -u +%s)}"
DEPLOY_PR_PREFIXES="${DEPLOY_PR_PREFIXES:-chore/admin-ui-deploy- chore/gitops-auto-deploy-}"

command -v "$GH_BIN" >/dev/null 2>&1 \
  || { echo "::error::gh binary '${GH_BIN}' not found" >&2; exit 2; }

list_json="$("$GH_BIN" pr list --repo "$REPO" --state open --limit 100 \
              --json number,headRefName,mergeStateStatus)"

# Candidate numbers: bot deploy branches in a blocked-by-staleness state.
candidates="$(jq -r --arg prefixes "$DEPLOY_PR_PREFIXES" '
  ($prefixes | split(" ") | map(select(length > 0))) as $p
  | .[]
  | select(.mergeStateStatus == "BEHIND" or .mergeStateStatus == "UNKNOWN")
  | .headRefName as $h
  | select(any($p[]; . as $pre | $h | startswith($pre)))
  | .number' <<< "$list_json")"

updated=0
skipped_fresh=0
for prn in $candidates; do
  # Throttle: skip if the head commit is younger than the window. .[-1] is the newest
  # commit on the branch (the API returns ascending order).
  head_date="$("$GH_BIN" api --repo "$REPO" "repos/${REPO}/pulls/${prn}/commits?per_page=100" \
                --jq '.[-1].commit.committer.date' 2>/dev/null || true)"
  if [ -n "$head_date" ]; then
    head_epoch="$(date -u -j -f '%Y-%m-%dT%H:%M:%SZ' "$head_date" +%s 2>/dev/null || date -u -d "$head_date" +%s 2>/dev/null || echo 0)"
    age_minutes=$(( (NOW_EPOCH - head_epoch) / 60 ))
    if [ "$age_minutes" -lt "$MIN_HEAD_AGE_MINUTES" ]; then
      echo "skip #${prn}: head ${age_minutes}m old < ${MIN_HEAD_AGE_MINUTES}m throttle"
      skipped_fresh=$((skipped_fresh + 1))
      continue
    fi
  fi
  echo "update #${prn}: deploy PR stale (BEHIND/UNKNOWN) — merging current main into its branch"
  "$GH_BIN" api --repo "$REPO" -X PUT "repos/${REPO}/pulls/${prn}/update-branch" >/dev/null
  updated=$((updated + 1))
done

echo "deploy-pr-updater: ${updated} branch update(s), ${skipped_fresh} skipped (fresh head), $(jq length <<< "$list_json") open PR(s) scanned"
