#!/usr/bin/env bash
#
# apply-branch-protection.sh — enforce server-side protection on the default
# branch via a GitHub *ruleset* (the modern successor to classic branch
# protection; supports required_signatures, which classic does not).
#
# WHY A RULESET, NOT CLASSIC PROTECTION:
#   - rulesets can require signed commits as a first-class rule
#   - rulesets are versioned, named, and can be exported/audited as JSON
#   - one ruleset covers deletion / force-push / linear history / PR / checks
#
# PREREQUISITES:
#   - GitHub Pro (or a public repo). On GitHub Free + private repo BOTH classic
#     protection AND rulesets return HTTP 403 ("Upgrade to GitHub Pro ...").
#   - `gh auth status` logged in with repo admin scope.
#   - Local commit signing configured (GPG / SSH / Sigstore), otherwise the
#     required_signatures rule will block your OWN pushes. Verify with:
#         git config --get commit.gpgsign      # -> true
#         git config --get gpg.format          # ssh | openpgp
#
# USAGE:
#   openbank-infra/scripts/apply-branch-protection.sh [-n] [-r owner/repo]
#     -n   dry-run: print the ruleset payload, do not call the API
#     -r   target repository (default: derived from the current gh repo)
#
# IDEMPOTENT: if a ruleset named "$RULESET_NAME" already exists it is updated
# in place (PUT), otherwise it is created (POST).
set -euo pipefail

RULESET_NAME="main-protection"

# Required status checks (exact check-run names). The aggregator "all-green"
# stands in for the whole per-service matrix (see services-ci.yml), so we do
# not have to enumerate all 29 services here.
#
# CI GATE MIGRATION — PHASE 1 OF 2:
# `Validate manifests` used to be the required context for the three gate
# shards. That adds one serial hosted-runner allocation after all substantive
# work is complete; in #9986 the no-op aggregator waited almost seven minutes
# and then ran for seconds. Require those shards directly while retaining the
# aggregator during the transition. `Admin UI` remains a separate follow-up:
# this script cannot itself prove its PR-only skipped-build path. A later change
# may add it after reviewing that evidence. Another follow-up may remove
# `Validate manifests` from both this list and ci.yml after the new contexts
# are observed live; the overlap intentionally preserves shard coverage.
#
# NOTE on matrix checks: a job with a matrix produces one check PER cell named
# "Job (cell)" — e.g. CodeQL becomes "CodeQL (java-kotlin)" and
# "CodeQL (javascript-typescript)". Add those explicit names if you want CodeQL
# to gate merges; the bare "CodeQL" context will never match.
REQUIRED_CHECKS=(
  "all-green"                                # Services CI — aggregates the per-service build matrix
  "Validate manifests"                       # CI — transitional aggregator; remove only in phase 2
  "gates (gitops-api)"                       # CI — direct governance shard
  "gates (lint-supplychain-security)"        # CI — direct governance shard
  "gates (registry-kotlin-data)"             # CI — direct governance shard
  "Gitleaks"                                 # Secret scan
  "issue-hygiene"                            # CI — link-in-PR lint (ADR-0052; rules.yaml: issues = block)
)

# Checks whose health this ruleset update relies on. Before the update, the
# exact current default-branch commit must already have emitted every one with
# a successful conclusion. This proves that the names match GitHub's real check
# names and that the jobs are healthy on that commit. `Admin UI` is deliberately
# excluded: a single default-branch push cannot prove its PR-only skipped-build
# path, so adding that required context needs its own reviewed follow-up.
# A renamed shard, missing job, queued run or real build failure therefore stops
# this script before it can deadlock main.
PREFLIGHT_CHECKS=(
  "gates (gitops-api)"
  "gates (lint-supplychain-security)"
  "gates (registry-kotlin-data)"
)

# Solo-maintainer pragmatism: GitHub forbids approving your own PR, so requiring
# >=1 approval would deadlock a single-maintainer repo. Set to 1+ once there is
# a second maintainer.
REQUIRED_APPROVALS=0

DRY_RUN=0
REPO=""
while getopts ":nr:" opt; do
  case "$opt" in
    n) DRY_RUN=1 ;;
    r) REPO="$OPTARG" ;;
    *) echo "usage: $0 [-n] [-r owner/repo]" >&2; exit 2 ;;
  esac
done

if [ -z "$REPO" ]; then
  REPO=$(gh repo view --json nameWithOwner --jq .nameWithOwner)
fi
echo "Target repository: $REPO"

# A ruleset PUT is all-or-nothing and takes effect immediately. Validate new
# contexts against one immutable SHA rather than a branch name that can move
# between API calls. `--paginate --slurp` also handles repositories whose HEAD
# emits more than GitHub's default page of check runs.
default_branch=$(gh repo view "$REPO" --json defaultBranchRef --jq '.defaultBranchRef.name')
default_sha=$(gh api "repos/$REPO/commits/$default_branch" --jq '.sha')
check_runs=$(gh api "repos/$REPO/commits/$default_sha/check-runs?per_page=100" \
  --paginate --slurp | jq '[.[].check_runs[]]')

for context in "${PREFLIGHT_CHECKS[@]}"; do
  conclusion=$(echo "$check_runs" | jq -r --arg context "$context" '
    map(select(.name == $context)) | sort_by(.id) | last | .conclusion // "missing"')
  case "$conclusion" in
    success)
      echo "Preflight: $context = $conclusion on $default_sha"
      ;;
    *)
      echo "ERROR: refusing to require '$context': latest result on default-branch" >&2
      echo "       commit $default_sha is '$conclusion'." >&2
      echo "       It may be absent, still running, skipped or failed; wait for SUCCESS" >&2
      echo "       on that exact commit, or fix its workflow first." >&2
      exit 1
      ;;
  esac
done

# A failed list read is not evidence that the ruleset is absent. Resolve one
# unambiguous resource or abort before constructing any write.
rulesets=$(gh api "repos/$REPO/rulesets" --paginate --slurp)
existing_id=$(echo "$rulesets" | jq -er --arg name "$RULESET_NAME" '
  if type != "array" or any(.[]; type != "array") then error("invalid ruleset listing")
  else [ .[][] | select(.name == $name) | .id ] as $ids
    | if ($ids | length) > 1 then error("ambiguous ruleset name")
      elif ($ids | length) == 1 then $ids[0] | tostring else "" end
  end')

live_json=''
if [ -n "$existing_id" ]; then
  live_json=$(gh api "repos/$REPO/rulesets/$existing_id")
  echo "$live_json" | jq -e --arg name "$RULESET_NAME" '
    .name == $name and .target == "branch" and
    (.rules | type == "array") and (.conditions | type == "object") and
    (.bypass_actors | type == "array") and
    ([.rules[] | select(.type == "required_status_checks")] | length == 1) and
    all(.rules[] | select(.type == "required_status_checks");
      (.parameters.required_status_checks | type == "array") and
      all(.parameters.required_status_checks[]; (.context | type == "string")))
  ' >/dev/null || {
    echo "ERROR: incomplete or ambiguous live ruleset; refusing update." >&2
    exit 1
  }
fi

# Build the required_status_checks array as JSON from REQUIRED_CHECKS.
checks_json=$(printf '%s\n' "${REQUIRED_CHECKS[@]}" \
  | jq -R '{context: .}' | jq -cs .)

payload=$(jq -n \
  --arg name "$RULESET_NAME" \
  --argjson approvals "$REQUIRED_APPROVALS" \
  --argjson checks "$checks_json" \
  '{
    name: $name,
    target: "branch",
    enforcement: "active",
    conditions: { ref_name: { include: ["~DEFAULT_BRANCH"], exclude: [] } },
    rules: [
      { type: "deletion" },
      { type: "non_fast_forward" },
      { type: "required_linear_history" },
      { type: "required_signatures" },
      { type: "pull_request",
        parameters: {
          required_approving_review_count: $approvals,
          dismiss_stale_reviews_on_push: true,
          require_code_owner_review: false,
          require_last_push_approval: false,
          required_review_thread_resolution: false
        } },
      { type: "required_status_checks",
        parameters: {
          # Conservative default for a brand-new ruleset. Existing rulesets do
          # not use this value: their complete live rule is preserved below.
          strict_required_status_checks_policy: true,
          required_status_checks: $checks
        } }
    ],
    bypass_actors: []
  }')

# Phase 1 is additive only. Preserve every live field and integration binding,
# changing solely the required-check array by appending missing contexts.
if [ -n "$existing_id" ]; then
  payload=$(echo "$live_json" | jq --argjson checks "$checks_json" '
    {name, target, enforcement, conditions, rules, bypass_actors}
    | .rules |= map(if .type == "required_status_checks" then
        .parameters.required_status_checks as $existing
        | .parameters.required_status_checks += [
            $checks[] | select(.context as $context |
              all($existing[]; .context != $context))]
      else . end)')
fi

if [ "$DRY_RUN" -eq 1 ]; then
  echo "--- dry-run: ruleset payload ---"
  echo "$payload" | jq .
  exit 0
fi

# Idempotent upsert: $existing_id was resolved up front (see bypass preservation).
if [ -n "$existing_id" ]; then
  # Refuse an observed concurrent edit instead of overwriting another operator.
  latest_json=$(gh api "repos/$REPO/rulesets/$existing_id")
  if [ "$(echo "$latest_json" | jq -cS .)" != "$(echo "$live_json" | jq -cS .)" ]; then
    echo "ERROR: ruleset changed during preparation; read and review it again." >&2
    exit 1
  fi
  echo "Adding missing checks to existing ruleset #$existing_id ..."
  echo "$payload" | gh api -X PUT "repos/$REPO/rulesets/$existing_id" \
    --input - >/dev/null
  echo "Ruleset #$existing_id updated."
else
  echo "Creating new ruleset ..."
  new_id=$(echo "$payload" | gh api -X POST "repos/$REPO/rulesets" \
    --input - --jq .id)
  echo "Ruleset #$new_id created."
fi

echo "Done. Verify in: Settings -> Rules -> Rulesets, or:"
echo "  gh api repos/$REPO/rulesets --jq '.[].name'"
