# ─────────────────────────────────────────────────────────────────────────────
# CI artifact mirror (ECR Public) — ADR-0366, rollout step 2.
# ─────────────────────────────────────────────────────────────────────────────
# CI tools and Helm charts listed in ci/artifacts.lock are mirrored as OCI
# artifacts into ECR Public on the SAME registry alias as the image mirrors in
# ecr-public-mirror.tf, under the `ci-artifacts/` prefix. ECR Public sits outside
# GitHub's failure domain, and reads are anonymous, so PRs from forks need no
# credential. The lock's sha256 is the only trust anchor: the resolver accepts
# bytes from the mirror only if their digest matches the lock.
#
# OpenTofu owns the repositories and the push role; the bytes are pushed by
# .github/workflows/ci-artifact-mirror.yml (.github/scripts/mirror-ci-artifacts.py).
# ECR Public does not create a repository on push, and the push role is deliberately
# unable to create one, so the repository set is DERIVED from ci/artifacts.lock: one
# repository per entry name, named exactly as the resolver's `mirror_ref` names it
# (an explicit `mirror` field wins; otherwise the lower-cased name with characters
# outside [a-z0-9._/-] replaced by `-`). A new lock entry therefore needs a
# `tofu apply`, and nothing else. `oras` is added here because it bootstraps the
# mirror workflow and is not a lock entry. Until the lock exists on main
# (ADR-0366 step 1), only `oras` is planned.

locals {
  ci_artifact_lock_path = "${path.module}/../../../../ci/artifacts.lock"
  # A malformed lock fails the plan loudly (jsondecode); only an ABSENT lock is tolerated.
  ci_artifact_lock_names = fileexists(local.ci_artifact_lock_path) ? tolist([
    for a in jsondecode(file(local.ci_artifact_lock_path)).artifacts :
    try(a.mirror, null) != null
    ? regex("^(.*):[^:]*$", a.mirror)[0]
    : replace(lower(a.name), "/[^a-z0-9._\\/-]/", "-")
  ]) : tolist([])

  # One repository per lock entry NAME (versions and platforms are tags).
  ci_artifact_mirror_names = toset(concat(["oras"], local.ci_artifact_lock_names))
  ci_artifact_mirror_prefix       = "ci-artifacts"
  ci_artifact_mirror_workflow_ref = "${local.ci_github_repo}/.github/workflows/ci-artifact-mirror.yml@refs/heads/main"
}

resource "aws_ecrpublic_repository" "ci_artifact" {
  provider = aws.us_east_1
  for_each = local.ci_artifact_mirror_names

  repository_name = "${local.ci_artifact_mirror_prefix}/${each.key}"

  catalog_data {
    about_text        = "OpenBank CI mirror of the public open-source artifact ${each.key}. Byte-identical copy of the upstream release, verified against the sha256 in ci/artifacts.lock before push. Not a fork and not modified."
    architectures     = ["ARM 64", "x86-64"]
    operating_systems = ["Linux"]
    usage_text        = "Internal CI use via .github/scripts/fetch-artifact (ADR-0366). Pinned tags only (<version> or <version>-<platform>). Always verify the sha256 from ci/artifacts.lock."
  }

  tags = { Project = "openbank", ManagedBy = "opentofu", Adr = "0366" }
}

output "ecr_public_ci_artifact_repository_uris" {
  description = "public.ecr.aws URIs of the CI artifact mirrors (ADR-0366)."
  value       = { for k, r in aws_ecrpublic_repository.ci_artifact : k => r.repository_uri }
}

# ---------------------------------------------------------------------------
# PUSH role — assumable only from main of this repo AND only by the mirror
# workflow file (job_workflow_ref pin, same shape as the ADR-0060 apply role).
# It can push layers/manifests to the ci-artifacts/* repositories and nothing
# else: no repository creation or deletion, no policy change, no other repo.
# ---------------------------------------------------------------------------
data "aws_iam_policy_document" "ci_artifact_mirror_assume" {
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
      test     = "StringEquals"
      variable = "${local.ci_oidc_host}:sub"
      values   = ["repo:${local.ci_github_repo}:ref:refs/heads/main"]
    }
    # Strict (not IfExists, unlike the apply role): the mirror runs on
    # GitHub-hosted runners, which always carry the claim, so its absence must
    # deny rather than skip the check.
    condition {
      test     = "StringEquals"
      variable = "${local.ci_oidc_host}:job_workflow_ref"
      values   = [local.ci_artifact_mirror_workflow_ref]
    }
  }
}

resource "aws_iam_role" "ci_artifact_mirror" {
  name                 = "openbank-ci-artifact-mirror"
  assume_role_policy   = data.aws_iam_policy_document.ci_artifact_mirror_assume.json
  max_session_duration = 3600
  tags                 = { Project = "openbank", ManagedBy = "opentofu", Adr = "0366" }
}

data "aws_iam_policy_document" "ci_artifact_mirror_push" {
  # Token minting has no resource-level scope in IAM; the push actions below do.
  statement {
    sid       = "AuthToken"
    actions   = ["ecr-public:GetAuthorizationToken", "sts:GetServiceBearerToken"]
    resources = ["*"]
  }
  statement {
    sid = "PushToCiArtifactsOnly"
    actions = [
      "ecr-public:BatchCheckLayerAvailability",
      "ecr-public:InitiateLayerUpload",
      "ecr-public:UploadLayerPart",
      "ecr-public:CompleteLayerUpload",
      "ecr-public:PutImage",
    ]
    resources = [for r in aws_ecrpublic_repository.ci_artifact : r.arn]
  }
}

resource "aws_iam_role_policy" "ci_artifact_mirror_push" {
  name   = "ecr-public-push-ci-artifacts"
  role   = aws_iam_role.ci_artifact_mirror.id
  policy = data.aws_iam_policy_document.ci_artifact_mirror_push.json
}

output "ci_artifact_mirror_role_arn" {
  description = "Role the ci-artifact-mirror workflow assumes; set it as repo variable CI_ARTIFACT_MIRROR_ROLE_ARN."
  value       = aws_iam_role.ci_artifact_mirror.arn
}
