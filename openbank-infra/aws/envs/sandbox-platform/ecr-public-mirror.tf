# ─────────────────────────────────────────────────────────────────────────────
# CI base-image mirror (ECR Public)
# ─────────────────────────────────────────────────────────────────────────────
# Docker Hub's unauthenticated pull rate limit was failing the self-hosted runner
# pool fleet-wide ("You have reached your unauthenticated pull rate limit") when
# every service-CI job booted the shared docker-compose infra (postgres/kafka/valkey).
#
# Fix (lowest attack surface, no Docker Hub token, no IAM change): pull the shared
# infra images from ECR Public, which AWS serves anonymously with a generous limit.
#   - postgres  → public.ecr.aws/docker/library/postgres   (AWS's Docker Hub mirror)
#   - valkey    → public.ecr.aws/valkey/valkey              (vendor's official mirror)
#   - kafka     → NOT on ECR Public, so we host a one-time mirror in this repo below.
#
# The image bytes are pushed by scripts/mirror-ci-base-images.sh (registry→registry
# `docker buildx imagetools create`, preserving the multi-arch manifest for both the
# arm64 and x86_64 runners). OpenTofu owns only the *repository* — the mirror script
# is idempotent and re-runnable when the pinned tag changes.
#
# CI consumes these via POSTGRES_IMAGE / KAFKA_IMAGE / VALKEY_IMAGE in
# .github/workflows/_service-ci.yml; openbank-infra/docker-compose.yml keeps Docker
# Hub defaults so local development is unchanged.

resource "aws_ecrpublic_repository" "kafka" {
  provider        = aws.us_east_1
  repository_name = "kafka"

  catalog_data {
    about_text        = "OpenBank CI mirror of apache/kafka (KRaft). Sourced from Docker Hub via scripts/mirror-ci-base-images.sh. Not a fork — byte-identical mirror to dodge Docker Hub's anonymous pull rate limit on the CI runner pool."
    architectures     = ["ARM 64", "x86-64"]
    operating_systems = ["Linux"]
    usage_text        = "Internal CI use. Pinned tags only (e.g. 3.7.0). Do not depend on :latest."
  }
}

output "ecr_public_kafka_repository_uri" {
  description = "public.ecr.aws URI of the CI kafka mirror (feeds KAFKA_IMAGE in _service-ci.yml)."
  value       = aws_ecrpublic_repository.kafka.repository_uri
}

# ─────────────────────────────────────────────────────────────────────────────
# Platform-image mirror (ECR Public) — runtime, not CI.
# ─────────────────────────────────────────────────────────────────────────────
# alloy / grafana / falco / falcoctl are NOT Docker Official Images, so AWS's free
# public.ecr.aws/docker/library mirror does not carry them. They were reaching the
# cluster via the anonymous `docker-hub/` ECR pull-through, whose shared anonymous
# Docker Hub budget is exhausted under Karpenter node churn — ECR then returns
# NotFound for these tags on fresh nodes (2026-06-27 incident: stuck alloy + falco
# DaemonSets and the Grafana rollout). Mirroring them here (same mechanism as kafka)
# removes Docker Hub from the runtime image path entirely — no Docker Hub credential.
#
# The gitops apps reference public.ecr.aws/d7v4f3x6/<name>; the Kyverno
# `rewrite-ecr-public` rule rewrites that to the authenticated, unthrottled
# `ecr-public/` ECR pull-through. Repo bytes are pushed by mirror-ci-base-images.sh.
resource "aws_ecrpublic_repository" "platform_mirror" {
  provider = aws.us_east_1
  for_each = toset(["alloy", "grafana", "falco", "falcoctl"])

  repository_name = each.key

  catalog_data {
    about_text        = "OpenBank mirror of ${each.key} (sourced from Docker Hub via scripts/mirror-ci-base-images.sh). Byte-identical mirror to take Docker Hub off the runtime image path; consumed by the in-cluster platform workloads via the ecr-public pull-through."
    architectures     = ["ARM 64", "x86-64"]
    operating_systems = ["Linux"]
    usage_text        = "Internal platform use. Pinned tags only, kept in lockstep with the Helm chart values in gitops/apps. Do not depend on :latest."
  }
}

output "ecr_public_platform_mirror_repository_uris" {
  description = "public.ecr.aws URIs of the platform-image mirrors (alloy/grafana/falco/falcoctl)."
  value       = { for k, r in aws_ecrpublic_repository.platform_mirror : k => r.repository_uri }
}
