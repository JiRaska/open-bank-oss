#!/usr/bin/env bash
# Mirror the shared CI base images that aren't already on ECR Public into our own
# ECR Public repo, so the self-hosted runner pool stops hitting Docker Hub's
# unauthenticated pull rate limit. See:
#   - openbank-infra/aws/envs/sandbox-platform/ecr-public-mirror.tf (owns the repo)
#   - .github/workflows/_service-ci.yml (consumes KAFKA_IMAGE)
#
# Idempotent: re-running with the same SRC/DST is a no-op push. Run after bumping a
# pinned tag, or after `tofu apply` first creates the repo.
#
# Prereqs: docker (with buildx), awscli v2, and an AWS profile with ecr-public:* on
# account 265175468565. postgres/valkey are pulled straight from AWS's own public
# mirrors (public.ecr.aws/docker/library, public.ecr.aws/valkey) and need NO mirror.
set -euo pipefail

: "${AWS_PROFILE:=openbank}"
export AWS_PROFILE
REGION="us-east-1"                       # ECR Public control plane is us-east-1 only
DST_REGISTRY="public.ecr.aws/d7v4f3x6"   # our ECR Public registry alias

# Pinned (source on Docker Hub) -> (destination on our ECR Public mirror).
# Keep the tags in lockstep with openbank-infra/docker-compose.yml.
SRC_KAFKA="apache/kafka:3.7.0"
DST_KAFKA="${DST_REGISTRY}/kafka:3.7.0"

echo ">> Logging in to ECR Public (${REGION})..."
aws ecr-public get-login-password --region "${REGION}" \
  | docker login --username AWS --password-stdin public.ecr.aws

echo ">> Mirroring ${SRC_KAFKA} -> ${DST_KAFKA} (multi-arch, registry-to-registry)..."
docker buildx imagetools create --tag "${DST_KAFKA}" "${SRC_KAFKA}"

echo ">> Verifying destination manifest platforms..."
docker buildx imagetools inspect "${DST_KAFKA}" | grep -E "Platform:\s+linux/(amd64|arm64)" \
  || { echo "!! expected linux/amd64 and linux/arm64 in the mirrored manifest"; exit 1; }

echo ">> Done. CI consumes this via KAFKA_IMAGE=${DST_KAFKA}"
