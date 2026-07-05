# ===========================================================================
# CI-applies-substrate-tofu (ADR-0060 follow-up, issue #225). Mirrors
# envs/sandbox-platform/ci-tofu-apply.tf so envs/sandbox-substrate (the EKS
# cluster, VPC/network, DNS, audit baseline, FinOps budget) gets the same
# CI-driven `tofu plan`/`tofu apply` pipeline instead of an unpredictable
# laptop `tofu apply`. That gap already caused real drift: an unapplied
# security-hardening flag, EKS add-ons behind, and an inert budget-notification
# config bug that nothing ever ran `plan` against (issue #225).
#
# Deliberately a SEPARATE pair of roles from openbank-ci-tofu-{plan,apply} in
# envs/sandbox-platform/ci-tofu-apply.tf — the issue is explicit that the
# existing platform apply role's trust is pinned to the exact
# `platform-tofu.yml` workflow ref and must not simply be widened to cover a
# second, less-reviewed root. Same two-role design, same OIDC provider
# (imported by ARN, not re-declared — one GitHub OIDC provider per account),
# pinned to `.github/workflows/substrate-tofu.yml` instead.
#
# Trust model (identical shape to the platform pair):
#   * Two roles. A read-only PLAN role assumable from any ref/PR of this repo
#     (the pull_request/push plan preview); an admin APPLY role whose trust is
#     pinned to the EXACT workflow file on main via the `job_workflow_ref`
#     claim — only `.github/workflows/substrate-tofu.yml@refs/heads/main` can
#     assume it.
#   * The apply job is `workflow_dispatch` only (a human presses "Run
#     workflow"), so apply never runs unattended on a push.
#   * No static AWS keys in GitHub. Both roles are assumed via the same GitHub
#     OIDC provider already created for the platform pair.
#
# Permission scoping: substrate provisions VPC/networking (modules/network),
# EKS + node groups + cluster KMS/OIDC (modules/eks), Karpenter controller IAM
# + SQS interruption queue (modules/karpenter-iam), Route53 zone + DNSSEC KMS
# key + external-dns/cert-manager Pod Identity roles (modules/dns), the
# CloudTrail/Config/S3-Object-Lock audit trail (modules/audit-baseline), and
# AWS Budgets/Cost-Anomaly-Detection/SNS (finops-budget.tf) — i.e. it creates
# and manages IAM roles/policies itself (Karpenter controller role, DNS Pod
# Identity roles, audit-baseline roles) alongside EC2/VPC, EKS, KMS, Route53,
# S3, CloudTrail, Config, SNS, Budgets, and Cost Explorer anomaly detection.
# That is the same breadth of foundational, IAM-creating infrastructure as
# sandbox-platform (which also creates IAM for Karpenter/ECR/helm releases),
# so the same reasoning applies: AdministratorAccess, because a trimmed policy
# would silently break applies the moment a new resource type is introduced
# (exactly the failure mode this issue is fixing — nothing ran `plan` against
# a merged change for months). The security boundary is the OIDC
# job_workflow_ref pin + manual dispatch, not a trimmed permission list — this
# mirrors, not exceeds, the platform apply role's permission set.
#
# Unlike the platform apply role, this one does NOT need an EKS access entry:
# sandbox-substrate has no `kubernetes`/`helm` provider (versions.tf declares
# only `aws` + `tls`) — the k8s/helm providers that need cluster RBAC live in
# the platform root, not here.
#
# Bootstrap (one-time, out of band — see ADR-0060): applied manually the first
# time (the apply role can't apply itself before it exists); thereafter CI
# maintains it.
# ===========================================================================

locals {
  # Same repo as the platform pair (single source: JiRaska/open-bank-oss).
  ci_substrate_github_repo = "JiRaska/open-bank-oss"
  ci_substrate_oidc_host   = "token.actions.githubusercontent.com"
  # The exact workflow file (on main) allowed to assume the admin apply role.
  ci_substrate_apply_workflow_ref = "JiRaska/open-bank-oss/.github/workflows/substrate-tofu.yml@refs/heads/main"
}

# Reuse the GitHub Actions OIDC provider already created by the platform root
# (one provider per AWS account/URL — a second `aws_iam_openid_connect_provider`
# for the same URL would collide). Imported by ARN via a data source so this
# root has no resource-level dependency on envs/sandbox-platform's state.
data "aws_iam_openid_connect_provider" "github_actions" {
  url = "https://${local.ci_substrate_oidc_host}"
}

# ---------------------------------------------------------------------------
# PLAN role — read-only, assumable from ANY ref/PR of this repo (the plan
# preview). ReadOnlyAccess; plan runs with -lock=false so it never writes
# state or the lock.
# ---------------------------------------------------------------------------
data "aws_iam_policy_document" "ci_tofu_plan_substrate_assume" {
  statement {
    actions = ["sts:AssumeRoleWithWebIdentity"]
    principals {
      type        = "Federated"
      identifiers = [data.aws_iam_openid_connect_provider.github_actions.arn]
    }
    condition {
      test     = "StringEquals"
      variable = "${local.ci_substrate_oidc_host}:aud"
      values   = ["sts.amazonaws.com"]
    }
    condition {
      test     = "StringLike"
      variable = "${local.ci_substrate_oidc_host}:sub"
      # Any branch/tag push and any PR of this repo (read-only role).
      values = [
        "repo:${local.ci_substrate_github_repo}:ref:refs/*",
        "repo:${local.ci_substrate_github_repo}:pull_request",
      ]
    }
  }
}

resource "aws_iam_role" "ci_tofu_plan_substrate" {
  name                 = "openbank-ci-tofu-plan-substrate"
  assume_role_policy   = data.aws_iam_policy_document.ci_tofu_plan_substrate_assume.json
  max_session_duration = 3600
  tags                 = { Project = "openbank", ManagedBy = "opentofu", Adr = "0060" }
}

resource "aws_iam_role_policy_attachment" "ci_tofu_plan_substrate_readonly" {
  role       = aws_iam_role.ci_tofu_plan_substrate.name
  policy_arn = "arn:aws:iam::aws:policy/ReadOnlyAccess"
}

# ---------------------------------------------------------------------------
# APPLY role — AdministratorAccess, assumable ONLY by the substrate-tofu
# workflow file on main (job_workflow_ref pin). See the file header for why
# this mirrors (not exceeds) the platform apply role's permission set.
# ---------------------------------------------------------------------------
data "aws_iam_policy_document" "ci_tofu_apply_substrate_assume" {
  statement {
    actions = ["sts:AssumeRoleWithWebIdentity"]
    principals {
      type        = "Federated"
      identifiers = [data.aws_iam_openid_connect_provider.github_actions.arn]
    }
    condition {
      test     = "StringEquals"
      variable = "${local.ci_substrate_oidc_host}:aud"
      values   = ["sts.amazonaws.com"]
    }
    # Primary gate: pin to main of this repo. `sub` is always present in the
    # OIDC token for all runner types (GitHub-hosted and ARC self-hosted).
    # Two legitimate sub forms for the apply, both accepted (StringEquals on a
    # list is an OR):
    #   - no environment  -> sub = "repo:OWNER/REPO:ref:refs/heads/main"
    #   - environment gate -> sub = "repo:OWNER/REPO:environment:substrate-apply"
    # GitHub rewrites the sub to the environment form whenever the job
    # declares `environment:` (same audit-trail pattern as platform's
    # `platform-apply`, issue #282). Accepting both lets the apply job opt
    # into the environment without breaking AssumeRole, while the
    # job_workflow_ref pin below remains the actual security boundary.
    condition {
      test     = "StringEquals"
      variable = "${local.ci_substrate_oidc_host}:sub"
      values = [
        "repo:${local.ci_substrate_github_repo}:ref:refs/heads/main",
        "repo:${local.ci_substrate_github_repo}:environment:substrate-apply",
      ]
    }
    # Belt-and-suspenders: further restrict to the exact workflow file when
    # the job_workflow_ref claim is present. ARC self-hosted runners omit this
    # claim on older runner versions, so we use StringEqualsIfExists (absent =
    # skip check).
    condition {
      test     = "StringEqualsIfExists"
      variable = "${local.ci_substrate_oidc_host}:job_workflow_ref"
      values   = [local.ci_substrate_apply_workflow_ref]
    }
  }
}

resource "aws_iam_role" "ci_tofu_apply_substrate" {
  name                 = "openbank-ci-tofu-apply-substrate"
  assume_role_policy   = data.aws_iam_policy_document.ci_tofu_apply_substrate_assume.json
  max_session_duration = 3600
  tags                 = { Project = "openbank", ManagedBy = "opentofu", Adr = "0060" }
}

resource "aws_iam_role_policy_attachment" "ci_tofu_apply_substrate_admin" {
  role       = aws_iam_role.ci_tofu_apply_substrate.name
  policy_arn = "arn:aws:iam::aws:policy/AdministratorAccess"
}

output "ci_tofu_plan_substrate_role_arn" {
  description = "Role the substrate-tofu PR plan job assumes (read-only)."
  value       = aws_iam_role.ci_tofu_plan_substrate.arn
}

output "ci_tofu_apply_substrate_role_arn" {
  description = "Role the substrate-tofu apply job assumes (workflow-ref-pinned, manual dispatch)."
  value       = aws_iam_role.ci_tofu_apply_substrate.arn
}
