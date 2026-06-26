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
# NOTE on matrix checks: a job with a matrix produces one check PER cell named
# "Job (cell)" — e.g. CodeQL becomes "CodeQL (java-kotlin)" and
# "CodeQL (javascript-typescript)". Add those explicit names if you want CodeQL
# to gate merges; the bare "CodeQL" context will never match.
REQUIRED_CHECKS=(
  "all-green"            # Services CI — aggregates the per-service build matrix
  "Validate manifests"   # CI — yamllint + shellcheck
  "Gitleaks"             # Secret scan
  "issue-hygiene"        # CI — link-in-PR lint (ADR-0052; rules.yaml: issues = block)
)
# NOTE: "Admin UI" (CI) is deliberately NOT gated yet. The committed
# openbank-admin-ui currently fails type-check (real WIP TypeScript errors +
# missing committed eslint config), so requiring it would deadlock every merge.
# Re-add it here once the admin-ui build is green:
#   "Admin UI"           # CI — Next.js lint + type-check + build

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
if [ -n "$existing_id" ]; then
  # If the ruleset exists we MUST read its bypass actors successfully. A failed
  # fetch must ABORT, never fall back to empty — coercing a transient API error
  # to `[]` would silently strip the actors, reintroducing the very bug this
  # guards against. A legitimately empty list serialises as "[]" (valid JSON),
  # which is distinct from the empty string produced on gh/jq failure.
  bypass_json=$(gh api "repos/$REPO/rulesets/$existing_id" \
    --jq '[.bypass_actors[] | {actor_id, actor_type, bypass_mode}]' 2>/dev/null || true)
  if ! echo "$bypass_json" | jq -e 'type == "array"' >/dev/null 2>&1; then
    echo "ERROR: ruleset #$existing_id exists but its bypass_actors could not be read." >&2
    echo "       Refusing to proceed: a PUT now would WIPE existing bypass actors." >&2
    exit 1
  fi
  echo "Preserving $(echo "$bypass_json" | jq 'length') bypass actor(s) from ruleset #$existing_id."
fi

# Build the required_status_checks array as JSON from REQUIRED_CHECKS.
checks_json=$(printf '%s\n' "${REQUIRED_CHECKS[@]}" \
  | jq -R '{context: .}' | jq -cs .)

payload=$(jq -n \
  --arg name "$RULESET_NAME" \
  --argjson approvals "$REQUIRED_APPROVALS" \
  --argjson checks "$checks_json" \
  --argjson bypass "$bypass_json" \
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
          strict_required_status_checks_policy: true,
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
