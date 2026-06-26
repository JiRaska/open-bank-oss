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
