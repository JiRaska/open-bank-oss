#!/usr/bin/env bash
# Mirror Docker Hub images we depend on into our own ECR Public repo, so neither
# the CI runner pool (Testcontainers) NOR the in-cluster platform DaemonSets hit
# Docker Hub's anonymous pull rate limit. See:
#   - openbank-infra/aws/envs/sandbox-platform/ecr-public-mirror.tf (owns the repos)
#   - .github/workflows/_service-ci.yml (consumes KAFKA_IMAGE)
#   - openbank-infra/gitops/apps/{alloy,falco,kube-prometheus-stack}.yaml (image refs)
#
# Why these platform images (alloy/grafana/falco/falcoctl): they are NOT Docker
# Official Images, so AWS's free public.ecr.aws/docker/library mirror does not carry
# them. They were reaching the cluster via the anonymous `docker-hub/` ECR pull-
# through, whose shared anonymous budget is exhausted under Karpenter node churn —
# ECR then returns NotFound on fresh nodes (2026-06-27: stuck alloy/falco DaemonSets
# + Grafana rollout). Mirroring them here (like kafka) takes Docker Hub out of the
# runtime path entirely — no Docker Hub credential needed. The workloads reference
# public.ecr.aws/d7v4f3x6/<name>, which Kyverno rewrites to the (authenticated,
# unthrottled) `ecr-public/` pull-through.
#
# Idempotent: re-running with the same SRC/DST is a no-op push. Run after bumping a
# pinned tag (keep tags in lockstep with the Helm chart targetRevision / values),
# or after `tofu apply` first creates the repo.
#
# Prereqs: docker (with buildx), awscli v2, and an AWS profile with ecr-public:* on
# account 265175468565. postgres/valkey are pulled straight from AWS's own public
# mirrors (public.ecr.aws/docker/library, public.ecr.aws/valkey) and need NO mirror.
set -euo pipefail

: "${AWS_PROFILE:=openbank}"
export AWS_PROFILE
REGION="us-east-1"                       # ECR Public control plane is us-east-1 only
DST_REGISTRY="public.ecr.aws/d7v4f3x6"   # our ECR Public registry alias

# Pinned (source on Docker Hub) -> (destination repo on our ECR Public mirror).
# Keep tags in lockstep with: docker-compose.yml (kafka) and the gitops Helm
# chart values (alloy/grafana/falco/falcoctl tags).
#   SRC on Docker Hub                       DST repo:tag on our mirror
MIRRORS=(
  "apache/kafka:3.7.0                       kafka:3.7.0"
  "grafana/alloy:v1.5.1                     alloy:v1.5.1"
  "grafana/grafana:13.0.2                   grafana:13.0.2"
  "falcosecurity/falco:0.44.1               falco:0.44.1"
  "falcosecurity/falcoctl:0.13.0            falcoctl:0.13.0"
)

echo ">> Logging in to ECR Public (${REGION})..."
aws ecr-public get-login-password --region "${REGION}" \
  | docker login --username AWS --password-stdin public.ecr.aws

for pair in "${MIRRORS[@]}"; do
  read -r src dst <<<"${pair}"
  src_ref="${src}"
  dst_ref="${DST_REGISTRY}/${dst}"
  echo ">> Mirroring ${src_ref} -> ${dst_ref} (multi-arch, registry-to-registry)..."
  docker buildx imagetools create --tag "${dst_ref}" "${src_ref}"

  echo ">> Verifying destination manifest platforms..."
  docker buildx imagetools inspect "${dst_ref}" | grep -E "Platform:\s+linux/(amd64|arm64)" \
    || { echo "!! expected linux/amd64 and linux/arm64 in ${dst_ref}"; exit 1; }
done

echo ">> Done. CI consumes kafka via KAFKA_IMAGE; the cluster pulls the platform"
echo ">> images via public.ecr.aws/d7v4f3x6/<name> (Kyverno -> ecr-public pull-through)."
