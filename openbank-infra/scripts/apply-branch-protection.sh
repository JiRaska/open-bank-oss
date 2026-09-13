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
# `Validate manifests` used to be the only required context for the three gate
# shards and the Admin UI gate. That adds one serial hosted-runner allocation
# after all substantive work is complete; in #9986 the no-op aggregator waited
# almost seven minutes and then ran for seconds. Require the four direct
# contexts now, while retaining the aggregator during the transition. Only
# after this desired state has been applied and observed live may a follow-up
# remove `Validate manifests` from both this list and ci.yml. The overlap is
# intentional: ruleset and workflow changes must never create a protection gap.
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
  "Admin UI"                                 # CI — path-aware build + Playwright gate
  "Gitleaks"                                 # Secret scan
  "issue-hygiene"                            # CI — link-in-PR lint (ADR-0052; rules.yaml: issues = block)
  "OPA policy gate"                          # Existing live requirement — preserve, not part of this migration
)

# Contexts introduced by the phase-1 migration. Before the ruleset can require
# them, the exact current default-branch commit must already have emitted every
# one with a successful conclusion. This proves both claims the migration relies on: the matrix
# display names match GitHub's real check names, and the path-aware Admin UI
# aggregator is healthy. A renamed shard, a missing job, a queued run or a real
# build failure therefore stops this script before it can deadlock main.
MIGRATION_CHECKS=(
  "gates (gitops-api)"
  "gates (lint-supplychain-security)"
  "gates (registry-kotlin-data)"
  "Admin UI"
  "OPA policy gate"
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

for context in "${MIGRATION_CHECKS[@]}"; do
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

# Look up an existing ruleset of this name UP FRONT — we need its id both for the
# idempotent upsert below AND to carry over its bypass_actors.
existing_id=$(gh api "repos/$REPO/rulesets" --jq \
  ".[] | select(.name == \"$RULESET_NAME\") | .id" 2>/dev/null || true)

# PRESERVE bypass_actors. A ruleset PUT replaces the WHOLE resource, so a
# hardcoded `bypass_actors: []` would silently WIPE any configured bypass (e.g.
# the admin/automation RepositoryRole that lets the second instance admin-merge).
# Read whatever is live and carry it over verbatim; only fall back to empty when
# there is no existing ruleset (first-time create). The list endpoint omits
# bypass_actors, so fetch the individual ruleset.
bypass_json='[]'
strict_json='false'
if [ -n "$existing_id" ]; then
  # If the ruleset exists we MUST read its bypass actors successfully. A failed
  # fetch must ABORT, never fall back to empty — coercing a transient API error
  # to `[]` would silently strip the actors, reintroducing the very bug this
  # guards against. A legitimately empty list serialises as "[]" (valid JSON),
  # which is distinct from the empty string produced on gh/jq failure.
  existing_ruleset=$(gh api "repos/$REPO/rulesets/$existing_id" 2>/dev/null || true)
  bypass_json=$(echo "$existing_ruleset" \
    | jq '[.bypass_actors[] | {actor_id, actor_type, bypass_mode}]' 2>/dev/null || true)
  if ! echo "$bypass_json" | jq -e 'type == "array"' >/dev/null 2>&1; then
    echo "ERROR: ruleset #$existing_id exists but its bypass_actors could not be read." >&2
    echo "       Refusing to proceed: a PUT now would WIPE existing bypass actors." >&2
    exit 1
  fi
  echo "Preserving $(echo "$bypass_json" | jq 'length') bypass actor(s) from ruleset #$existing_id."

  strict_json=$(echo "$existing_ruleset" | jq -r '
    [.rules[] | select(.type == "required_status_checks")
      | .parameters.strict_required_status_checks_policy] | first')
  if [ "$strict_json" != "true" ] && [ "$strict_json" != "false" ]; then
    echo "ERROR: ruleset #$existing_id has no readable strict-status-checks policy." >&2
    echo "       Refusing to guess a value during a context-only migration." >&2
    exit 1
  fi
  echo "Preserving strict-required-status-checks policy: $strict_json."

  # A PUT replaces the complete required-check list too. Refuse an accidental
  # removal caused by desired-state drift; deleting a live gate must be an
  # explicit, separately reviewed migration rather than a side effect here.
  live_checks=$(echo "$existing_ruleset" | jq -r '
    [.rules[] | select(.type == "required_status_checks")
      | .parameters.required_status_checks[].context] | unique[]')
  while IFS= read -r context; do
    [ -z "$context" ] && continue
    if ! printf '%s\n' "${REQUIRED_CHECKS[@]}" | grep -Fqx -- "$context"; then
      echo "ERROR: desired ruleset would remove live required check '$context'." >&2
      echo "       Add it to REQUIRED_CHECKS or migrate it explicitly in a separate PR." >&2
      exit 1
    fi
  done <<< "$live_checks"
fi

# Build the required_status_checks array as JSON from REQUIRED_CHECKS.
checks_json=$(printf '%s\n' "${REQUIRED_CHECKS[@]}" \
  | jq -R '{context: .}' | jq -cs .)

payload=$(jq -n \
  --arg name "$RULESET_NAME" \
  --argjson approvals "$REQUIRED_APPROVALS" \
  --argjson checks "$checks_json" \
  --argjson bypass "$bypass_json" \
  --argjson strict "$strict_json" \
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
          strict_required_status_checks_policy: $strict,
          required_status_checks: $checks
        } }
    ],
    bypass_actors: $bypass
  }')

if [ "$DRY_RUN" -eq 1 ]; then
  echo "--- dry-run: ruleset payload ---"
  echo "$payload" | jq .
  exit 0
fi

# Idempotent upsert: $existing_id was resolved up front (see bypass preservation).
if [ -n "$existing_id" ]; then
  echo "Updating existing ruleset #$existing_id ..."
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
