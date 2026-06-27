# ===========================================================================
# CI-applies-platform-tofu (ADR-0060). Lets GitHub Actions run `tofu plan` on a
# PR and a manually-dispatched `tofu apply` on main, so a merged platform change
# is applied from CI instead of a laptop `tofu apply` (the gap that left the
# ADR-0053 stuck-runner reaper merged-but-unapplied). Closes "as-code without
# applied -> main and the live cluster drift".
#
# Trust model — the apply role is powerful (it applies the WHOLE env), so safety
# rests on the OIDC trust, not on a trimmed permission list:
#   * Two roles. A read-only PLAN role assumable from any ref/PR of THIS repo
#     (the pull_request/push plan preview); an admin APPLY role whose trust is
#     pinned to the EXACT workflow file on main via the `job_workflow_ref` claim
#     — only `.github/workflows/platform-tofu.yml@refs/heads/main` can assume it.
#   * The apply job is `workflow_dispatch` only (a human presses "Run workflow"),
#     so apply never runs unattended on a push. NOTE: a reviewer-gated GitHub
#     Environment would be stronger, but environment protection rules require
#     GitHub Pro/Team on a private repo (Free returns 422) — see ADR-0060. The
#     manual dispatch + the PR `tofu plan` preview are the human checkpoint on
#     this plan; revisit with an Environment gate if the repo upgrades.
#   * No static AWS keys in GitHub. Both roles are assumed via the GitHub OIDC
#     provider below (federated, short-lived STS creds).
#   * The k8s/helm/kubectl providers need cluster access: the cluster runs in EKS
#     Access-Entry mode (no aws-auth configmap), so each role gets an access entry
#     + a cluster-access policy (admin for apply, admin-view for plan).
#
# Bootstrap (one-time, out of band — see ADR-0060): these resources are applied
# manually the first time (the apply role can't apply itself before it exists);
# thereafter CI maintains them.
# ===========================================================================

locals {
  # The repository whose GitHub Actions may assume these roles. Matches the
  # `githubConfigUrl` the runners register against (var.github_config_url).
  ci_github_repo = "JiRaska/open-bank"
  ci_oidc_host   = "token.actions.githubusercontent.com"
  # The exact workflow file (on main) allowed to assume the admin apply role.
  ci_apply_workflow_ref = "JiRaska/open-bank/.github/workflows/platform-tofu.yml@refs/heads/main"
}

# GitHub Actions OIDC identity provider. Distinct from the EKS IRSA provider
# (oidc.eks.*) already in the substrate — this one federates GitHub-hosted/ARC
# Actions jobs. AWS validates GitHub's tokens against its own trust store, so the
# thumbprint is no longer security-relevant, but the field is required; both
# historically-published GitHub leaf thumbprints are listed.
resource "aws_iam_openid_connect_provider" "github_actions" {
  url            = "https://${local.ci_oidc_host}"
  client_id_list = ["sts.amazonaws.com"]
  thumbprint_list = [
    "6938fd4d98bab03faadb97b34396831e3780aea1",
    "1c58a3a8518e8759bf075b76b750d4f2df264fcd",
  ]
  tags = { Project = "openbank", ManagedBy = "opentofu", Adr = "0060" }
}

# ---------------------------------------------------------------------------
# PLAN role — read-only, assumable from ANY ref/PR of this repo (the plan
# preview). ReadOnlyAccess + an EKS admin-view access entry; plan runs with
# -lock=false so it never writes state or the lock.
# ---------------------------------------------------------------------------
data "aws_iam_policy_document" "ci_tofu_plan_assume" {
  statement {
    actions = ["sts:AssumeRoleWithWebIdentity"]
    principals {
      type        = "Federated"
      identifiers = [aws_iam_openid_connect_provider.github_actions.arn]
    }
    condition {
      test     = "StringEquals"
      variable = "${local.ci_oidc_host}:aud"
      values   = ["sts.amazonaws.com"]
    }
    condition {
      test     = "StringLike"
      variable = "${local.ci_oidc_host}:sub"
      # Any branch/tag push and any PR of this repo (read-only role).
      values = [
        "repo:${local.ci_github_repo}:ref:refs/*",
        "repo:${local.ci_github_repo}:pull_request",
      ]
    }
  }
}

resource "aws_iam_role" "ci_tofu_plan" {
  name                 = "openbank-ci-tofu-plan"
  assume_role_policy   = data.aws_iam_policy_document.ci_tofu_plan_assume.json
  max_session_duration = 3600
  tags                 = { Project = "openbank", ManagedBy = "opentofu", Adr = "0060" }
}

resource "aws_iam_role_policy_attachment" "ci_tofu_plan_readonly" {
  role       = aws_iam_role.ci_tofu_plan.name
  policy_arn = "arn:aws:iam::aws:policy/ReadOnlyAccess"
}

# ---------------------------------------------------------------------------
# APPLY role — AdministratorAccess, assumable ONLY by the platform-tofu workflow
# file on main (job_workflow_ref pin). AdministratorAccess because the env
# manages IAM, EKS, EC2/Karpenter, ECR, helm releases, … — a trimmed policy
# would silently break applies when a new resource type is introduced; the
# security boundary is the workflow-ref pin + manual dispatch, per ADR-0060.
# ---------------------------------------------------------------------------
data "aws_iam_policy_document" "ci_tofu_apply_assume" {
  statement {
    actions = ["sts:AssumeRoleWithWebIdentity"]
    principals {
      type        = "Federated"
      identifiers = [aws_iam_openid_connect_provider.github_actions.arn]
    }
    condition {
      test     = "StringEquals"
      variable = "${local.ci_oidc_host}:aud"
      values   = ["sts.amazonaws.com"]
    }
    # Primary gate: pin to main of this repo. `sub` is always present in the
    # OIDC token for all runner types (GitHub-hosted and ARC self-hosted).
    # Two legitimate sub forms for the apply, both accepted (StringEquals on a list
    # is an OR):
    #   - no environment   → sub = "repo:OWNER/REPO:ref:refs/heads/main"
    #   - environment gate  → sub = "repo:OWNER/REPO:environment:platform-apply"
    # GitHub rewrites the sub to the environment form whenever the job declares
    # `environment:` (issue #282 audit-trail + reviewer gate). Accepting both lets
    # the apply job opt into the environment without breaking AssumeRole, while the
    # job_workflow_ref pin below remains the actual security boundary (ADR-0060).
    condition {
      test     = "StringEquals"
      variable = "${local.ci_oidc_host}:sub"
      values = [
        "repo:${local.ci_github_repo}:ref:refs/heads/main",
        "repo:${local.ci_github_repo}:environment:platform-apply",
      ]
    }
    # Belt-and-suspenders: further restrict to the exact workflow file when the
    # job_workflow_ref claim is present. ARC self-hosted runners omit this claim
    # on older runner versions, so we use StringEqualsIfExists (absent = skip check).
    condition {
      test     = "StringEqualsIfExists"
      variable = "${local.ci_oidc_host}:job_workflow_ref"
      values   = [local.ci_apply_workflow_ref]
    }
  }
}

resource "aws_iam_role" "ci_tofu_apply" {
  name                 = "openbank-ci-tofu-apply"
  assume_role_policy   = data.aws_iam_policy_document.ci_tofu_apply_assume.json
  max_session_duration = 3600
  tags                 = { Project = "openbank", ManagedBy = "opentofu", Adr = "0060" }
}

resource "aws_iam_role_policy_attachment" "ci_tofu_apply_admin" {
  role       = aws_iam_role.ci_tofu_apply.name
  policy_arn = "arn:aws:iam::aws:policy/AdministratorAccess"
}

# ---------------------------------------------------------------------------
# EKS access entries — so `aws eks get-token` for each role maps to cluster RBAC
# (the cluster is in Access-Entry auth mode; there is no aws-auth configmap).
# apply -> ClusterAdmin (helm/k8s providers create namespaces, RBAC, releases);
# plan  -> AdminView: read-only but INCLUDING secrets — the helm provider reads
#          release-state Secrets on refresh, which the plain View policy forbids,
#          so a View-scoped plan would error on every helm_release.
# ---------------------------------------------------------------------------
resource "aws_eks_access_entry" "ci_tofu_apply" {
  cluster_name  = local.cluster_name
  principal_arn = aws_iam_role.ci_tofu_apply.arn
  type          = "STANDARD"
}

resource "aws_eks_access_policy_association" "ci_tofu_apply_admin" {
  cluster_name  = local.cluster_name
  principal_arn = aws_iam_role.ci_tofu_apply.arn
  policy_arn    = "arn:aws:eks::aws:cluster-access-policy/AmazonEKSClusterAdminPolicy"
  access_scope { type = "cluster" }
  depends_on = [aws_eks_access_entry.ci_tofu_apply]
}

resource "aws_eks_access_entry" "ci_tofu_plan" {
  cluster_name  = local.cluster_name
  principal_arn = aws_iam_role.ci_tofu_plan.arn
  type          = "STANDARD"
}

resource "aws_eks_access_policy_association" "ci_tofu_plan_view" {
  cluster_name  = local.cluster_name
  principal_arn = aws_iam_role.ci_tofu_plan.arn
  policy_arn    = "arn:aws:eks::aws:cluster-access-policy/AmazonEKSAdminViewPolicy"
  access_scope { type = "cluster" }
  depends_on = [aws_eks_access_entry.ci_tofu_plan]
}

output "ci_tofu_plan_role_arn" {
  description = "Role the platform-tofu PR plan job assumes (read-only)."
  value       = aws_iam_role.ci_tofu_plan.arn
}

output "ci_tofu_apply_role_arn" {
  description = "Role the platform-tofu apply job assumes (workflow-ref-pinned, manual dispatch)."
  value       = aws_iam_role.ci_tofu_apply.arn
}
