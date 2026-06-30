# ===========================================================================
# GitHub repo governance as code (ADR-0059, issue #282).
#
# Two resources close the two unenforced layers identified in #282:
#   1. Branch protection on `main` — blocks direct pushes so that the admin
#      APPLY role's trust chain always goes through a PR (not a direct push).
#   2. `platform-apply` GitHub Environment — tracks the deployment environment
#      used by the platform-tofu apply job. Required reviewers would gate the
#      manual apply dispatch, but that feature is unavailable on GitHub Free
#      private repos (returns 422). The environment is still created as code so
#      drift is detectable and reviewers can be added by uncommenting the block
#      once the repo upgrades to GitHub Pro/Team.
#
# Token: `var.governance_gh_pat` (TF_VAR_governance_gh_pat in CI) — fine-grained
# PAT with Administration:write + Environments:write on JiRaska/open-bank-oss.
# Never stored in state.
# ===========================================================================

# ---------------------------------------------------------------------------
# 1. Branch protection on `main`
#
# Goal: a direct push to `main` is rejected — the admin APPLY role can then
# only be reached via the platform-tofu workflow, not from an arbitrary push.
#
# Required status checks: "Gitleaks" runs on every PR regardless of path. Path-
# scoped checks (fleet lint, admin-ui build, etc.) are deliberately excluded —
# requiring them on unrelated PRs would block merges when the check is skipped.
#
# Required reviews: 0 — the Sonnet pre-merge review (ship-auto) is the de-facto
# approval gate; formal GitHub reviews would block the current merge workflow.
# Raise to 1 once ship-auto posts a formal GitHub review approval.
# ---------------------------------------------------------------------------
resource "github_branch_protection" "main" {
  repository_id = "open-bank"
  pattern       = "main"

  # Conversation threads must be resolved before merge — avoids silently
  # shipping a PR with open review comments.
  require_conversation_resolution = true

  required_status_checks {
    # Don't require the branch to be up-to-date before merging; that forces
    # a rebase loop on every queued PR in a busy fleet and buys nothing here.
    strict   = false
    contexts = ["Gitleaks"]
  }

  required_pull_request_reviews {
    required_approving_review_count = 0
    dismiss_stale_reviews           = true
    # Do not restrict who can dismiss stale reviews — the merge actor (Claude /
    # repo admin) needs to re-push a fix without a human re-approval loop.
    restrict_dismissals = false
  }

  # No force-push or deletion on the main release branch.
  allows_force_pushes = false
  allows_deletions    = false
}

# ---------------------------------------------------------------------------
# 2. `platform-apply` GitHub Environment
#
# The apply job in platform-tofu.yml references this environment so that:
#   - Every apply run is logged in the GitHub Deployments API (audit trail).
#   - Required reviewers can be added here once the repo upgrades to Pro/Team.
#
# To add a required reviewer (Pro/Team only), uncomment the `reviewers` block
# and set `wait_timer` to the desired delay (seconds) before auto-approval.
# ---------------------------------------------------------------------------
resource "github_repository_environment" "platform_apply" {
  repository  = "open-bank"
  environment = "platform-apply"

  # reviewers {
  #   # Requires GitHub Pro or Team on private repos. Uncomment after upgrading.
  #   # users = [<github_user_id>]
  # }
}
