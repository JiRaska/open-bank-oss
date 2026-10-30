# ─────────────────────────────────────────────────────────────────────────────
# ECR lifecycle policies — bound registry storage without breaking a deploy (#14189)
# ─────────────────────────────────────────────────────────────────────────────
# Until this file, no repository in the account had a lifecycle policy, so every
# build ever pushed was kept: ~25.5k `sandbox-*` images across the service repos
# on 2026-10-29, the busiest repository holding ~800. The policies here are shaped
# by what must NEVER be expired, measured against the live registry and cluster
# the same day:
#
#   1. cosign signatures and attestations. cosign v2 stores them as TAGGED
#      artefacts `sha256-<digest>.sig` / `.att` beside the image, and Kyverno
#      verifyImages rejects a pod whose image has neither. No rule below selects
#      them: the count rule matches only the `sandbox-` tag prefix, which a
#      `sha256-` tag cannot match, and the untagged rule only untagged manifests.
#      A count rule over ALL tagged images would have counted ~50k signature
#      artefacts against the limit and evicted the signature of a deployed image.
#      Orphaned `.sig`/`.att` of an expired build stay behind; they are a few
#      hundred KB each and are left alone deliberately rather than risk a rule
#      that can reach a live one.
#   2. every deployed digest — gitops pins, live pods, and every ReplicaSet
#      revision Argo Rollouts keeps for a rollback. Ranked by push time among a
#      repository's `sandbox-*` builds, the oldest such image was the 111th
#      newest; hence 150, not "keep 10". A count rule cannot age out a dormant
#      service's only image (a time rule would: one deployed image dates from
#      2026-06), which is why the tagged rule is a count and not an age.
#   3. digest pins whose tag has moved. keycloak, litellm, the ARC runner image
#      and the pricing pair are pinned `@sha256:` in gitops, and several of those
#      digests are UNTAGGED today — an untagged-age rule would delete images that
#      running workloads reference. Those repositories therefore get no untagged
#      rule; the set is DERIVED from the same gitops pins the repositories are,
#      so a new digest pin is protected by the PR that introduces it.
#      Tag-pinned repositories are safe to clean of untagged manifests because
#      Kyverno runs with `mutateDigest: false`: pods and ReplicaSet templates keep
#      the tag, so a re-pushed `sandbox-<sha>` is resolved afresh on the next pull
#      and the superseded digest is referenced by nothing.
#   4. children of a multi-arch index. They are untagged, but ECR does not
#      expire a manifest an image index still references — measured with the
#      lifecycle preview on openbank-pricing-console: both children of a kept
#      index pushed 2026-08-24 were absent from the expiring set.
#
# Pull-through cache repositories are a cache: an expired entry is fetched from
# upstream again on the next pull. Their entries expire 60 days after caching.
# Note `sinceImagePulled` would be the better selector and is not available for
# the expire action (ECR permits it only with TRANSITION).

locals {
  # Service repositories referenced by digest anywhere in gitops, plus the ARC
  # runner image (also a digest pin). Same pattern as gitops_image_repositories
  # in ecr-service-repositories.tf, narrowed to the `@sha256:` separator.
  digest_pinned_service_repositories = setunion(
    toset(flatten([
      for f in local.gitops_manifest_files : [
        for m in regexall(
          "[0-9]{12}\\.dkr\\.ecr\\.[a-z0-9-]+\\.amazonaws\\.com/(openbank-[a-z0-9-]+)(?::[A-Za-z0-9._-]+)?@sha256:",
          file("${local.gitops_dir}/${f}")
        ) : m[0]
      ]
    ])),
    toset([local.ci_runner_repository]),
  )

  # Deploy-aware protection. .github/scripts/ecr-live-tags.py (ecr-live-tags.yml) tags every
  # image gitops or the ARC runner pin referenced on main within the last 14 days
  # `live-<digest12>`. ECR never lets a lower-priority rule expire an image a higher-priority rule
  # matched, so this rule shields those images from the count and untagged rules below however
  # far down the build ranking they fall (measured with the lifecycle preview on
  # openbank-agent-service: at countNumber 5 the deployed digest expired without this rule and was
  # kept with it). The count is a ceiling that is never reached — the workflow removes live- tags
  # once a digest leaves the window.
  ecr_live_deploy_rule = {
    rulePriority = 1
    description  = "Never expire images tagged live-* (deployed or a rollback target)"
    selection = {
      tagStatus     = "tagged"
      tagPrefixList = ["live-"]
      countType     = "imageCountMoreThan"
      countNumber   = 1000
    }
    action = { type = "expire" }
  }

  ecr_sandbox_build_rule = {
    rulePriority = 10
    description  = "Keep the newest 150 sandbox-* builds; sha256-* signature/attestation tags are never selected"
    selection = {
      tagStatus     = "tagged"
      tagPrefixList = ["sandbox-"]
      countType     = "imageCountMoreThan"
      countNumber   = 150
    }
    action = { type = "expire" }
  }

  ecr_untagged_rule = {
    rulePriority = 20
    description  = "Expire untagged manifests (superseded .att rewrites, re-pushed tags) after 30 days"
    selection = {
      tagStatus   = "untagged"
      countType   = "sinceImagePushed"
      countUnit   = "days"
      countNumber = 30
    }
    action = { type = "expire" }
  }

  ecr_pull_through_lifecycle_policy = jsonencode({
    rules = [{
      rulePriority = 10
      description  = "Expire cache entries 60 days after caching; re-fetched from upstream on next pull"
      selection = {
        tagStatus   = "any"
        countType   = "sinceImagePushed"
        countUnit   = "days"
        countNumber = 60
      }
      action = { type = "expire" }
    }]
  })

  ecr_pull_through_prefixes = toset([
    aws_ecr_pull_through_cache_rule.quay.ecr_repository_prefix,
    aws_ecr_pull_through_cache_rule.docker.ecr_repository_prefix,
    aws_ecr_pull_through_cache_rule.ghcr.ecr_repository_prefix,
    aws_ecr_pull_through_cache_rule.k8s.ecr_repository_prefix,
    aws_ecr_pull_through_cache_rule.ecr_public.ecr_repository_prefix,
  ])

  # Cache repositories already created by a first pull. New ones get the policy
  # from the creation template below; these predate it.
  existing_pull_through_repositories = toset([
    for n in data.aws_ecr_repositories.existing.names : n
    if contains(local.ecr_pull_through_prefixes, split("/", n)[0])
  ])
}

resource "aws_ecr_lifecycle_policy" "service" {
  for_each = aws_ecr_repository.service

  repository = each.value.name
  policy = jsonencode({
    rules = concat(
      [local.ecr_live_deploy_rule, local.ecr_sandbox_build_rule],
      contains(local.digest_pinned_service_repositories, each.key) ? [] : [local.ecr_untagged_rule],
    )
  })
}

resource "aws_ecr_lifecycle_policy" "pull_through" {
  for_each = local.existing_pull_through_repositories

  repository = each.key
  policy     = local.ecr_pull_through_lifecycle_policy
}

# Applies the same policy to every cache repository a future first pull creates.
# The other settings restate ECR's defaults for a pull-through-created repository.
resource "aws_ecr_repository_creation_template" "pull_through" {
  for_each = local.ecr_pull_through_prefixes

  prefix               = each.key
  description          = "Pull-through cache repositories under ${each.key}/ (lifecycle: #14189)"
  applied_for          = ["PULL_THROUGH_CACHE"]
  image_tag_mutability = "MUTABLE"
  lifecycle_policy     = local.ecr_pull_through_lifecycle_policy

  encryption_configuration {
    encryption_type = "AES256"
  }
}

# ─────────────────────────────────────────────────────────────────────────────
# Writer role for the live- tags — GitHub OIDC, main of this repo, ecr-live-tags.yml only.
# ─────────────────────────────────────────────────────────────────────────────
# A dedicated role rather than more grants on openbank-arc-build-runner: removing a tag is
# BatchDeleteImage, which deletes an image whose last tag it removes, and no build job needs it.
data "aws_iam_policy_document" "ecr_live_tagger_assume" {
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
    condition {
      test     = "StringEqualsIfExists"
      variable = "${local.ci_oidc_host}:job_workflow_ref"
      values   = ["${local.ci_github_repo}/.github/workflows/ecr-live-tags.yml@refs/heads/main"]
    }
  }
}

resource "aws_iam_role" "ecr_live_tagger" {
  name                 = "${local.cluster_name}-ecr-live-tagger"
  assume_role_policy   = data.aws_iam_policy_document.ecr_live_tagger_assume.json
  max_session_duration = 3600
  tags                 = { Project = "openbank", ManagedBy = "opentofu", Issue = "14189" }
}

data "aws_iam_policy_document" "ecr_live_tagger" {
  statement {
    sid       = "ListRepositories"
    actions   = ["ecr:DescribeRepositories"]
    resources = ["*"]
  }
  statement {
    sid = "TagServiceImages"
    actions = [
      "ecr:DescribeImages",
      "ecr:BatchGetImage",
      "ecr:PutImage",
      "ecr:BatchDeleteImage",
    ]
    resources = ["arn:aws:ecr:${local.region}:${data.aws_caller_identity.current.account_id}:repository/openbank-*"]
  }
}

resource "aws_iam_role_policy" "ecr_live_tagger" {
  name   = "ecr-live-tags"
  role   = aws_iam_role.ecr_live_tagger.id
  policy = data.aws_iam_policy_document.ecr_live_tagger.json
}
