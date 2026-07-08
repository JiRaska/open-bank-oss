# ---------------------------------------------------------------------------
# ECR pull-through cache — eliminates NAT gateway charges for external image
# pulls from quay.io, ghcr.io, registry.k8s.io, and public.ecr.aws.
#
# Problem: every new Karpenter node pulls images directly from public registries
# over the NAT gateway ($0.045/GB). With 15–23 nodes and frequent Karpenter
# churn, this is the dominant baseline NAT cost driver (~$4–29/day).
#
# Solution: ECR pull-through cache converts external pulls into in-VPC pulls
# via the ecr.dkr VPC Interface endpoint (already present). First pull for each
# pinned tag: ECR fetches from upstream server-side (not through our NAT).
# All subsequent pulls: from private ECR over the VPC endpoint — zero NAT cost.
#
# Kyverno ClusterPolicy (gitops/components/kyverno/ecr-pull-through-rewrite.yaml)
# rewrites Pod image refs at admission time to inject the ECR prefix. containerd
# hosts.toml cannot add a path prefix, so Kyverno is the correct mechanism.
#   quay.io/foo          → 265175468565.dkr.ecr.eu-north-1.amazonaws.com/quay/foo
#   registry.k8s.io/foo  → 265175468565.dkr.ecr.eu-north-1.amazonaws.com/k8s/foo
#   public.ecr.aws/foo   → 265175468565.dkr.ecr.eu-north-1.amazonaws.com/ecr-public/foo
#
# docker.io is handled separately by the in-cluster registry-cache (ClusterIP
# 172.20.188.54:5000) via EC2NodeClass.userData hosts.toml — transparent proxy,
# no prefix rewrite needed, no Docker Hub credentials required.
# ---------------------------------------------------------------------------

resource "aws_ecr_pull_through_cache_rule" "quay" {
  ecr_repository_prefix = "quay"
  upstream_registry_url = "quay.io"
}

# docker.io pull-through cache — eliminates NAT for docker.io image pulls
# (Testcontainers: postgres, redpanda, valkey, apicurio; k8s workloads: nginx,
# valkey, openpolicyagent/opa, temporalio, etc.).
#
# Docker Hub requires authentication for ECR pull-through even for public images
# (UnsupportedUpstreamRegistryException without credential_arn). PAT stored in
# Secrets Manager (jiraska account, read:packages scope). ECR fetches upstream
# server-side so our runner/node IPs never hit Docker Hub rate limits.
#
# Flow: Kyverno rewrites docker.io/ refs → ECR docker-hub/ prefix at pod
# admission; ECR pull-through serves from cache after first pull; first pull
# fetches from registry-1.docker.io server-side using the PAT.
# Zero NAT after first cache warm per tag.
resource "aws_ecr_pull_through_cache_rule" "docker" {
  ecr_repository_prefix = "docker-hub"
  upstream_registry_url = "registry-1.docker.io"
  credential_arn        = aws_secretsmanager_secret.dockerhub.arn
}

resource "aws_secretsmanager_secret" "dockerhub" {
  name        = "ecr-pullthroughcache/dockerhub"
  description = "Docker Hub PAT for ECR pull-through cache (jiraska, Public Repo Read-only)"
}

# ECR service needs GetSecretValue on the secret to fetch Docker Hub credentials.
# This resource-based policy on the secret grants that — node IAM roles are NOT
# involved; ECR calls Secrets Manager directly on the first pull per tag.
resource "aws_secretsmanager_secret_policy" "dockerhub_ecr" {
  secret_arn = aws_secretsmanager_secret.dockerhub.arn
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Sid       = "AllowECRPullThroughCache"
      Effect    = "Allow"
      Principal = { Service = "ecr.amazonaws.com" }
      Action    = "secretsmanager:GetSecretValue"
      Resource  = "*"
      Condition = {
        StringEquals = { "aws:SourceAccount" = "265175468565" }
        ArnLike      = { "aws:SourceArn" = "arn:aws:ecr:eu-north-1:265175468565:repository/docker-hub/*" }
      }
    }]
  })
}

# ghcr.io pull-through cache — PAT stored in Secrets Manager (classic token, read:packages).
# Images: configmap-reload (Alloy DaemonSet on every node), cloudnative-pg, kyverno,
# keda, flagd, pyrra, ARC controller — 15 images currently pulled via NAT on every
# new Karpenter node.
resource "aws_ecr_pull_through_cache_rule" "ghcr" {
  ecr_repository_prefix = "ghcr"
  upstream_registry_url = "ghcr.io"
  credential_arn        = "arn:aws:secretsmanager:eu-north-1:265175468565:secret:ecr-pullthroughcache/ghcr-0z6lo9"
}

resource "aws_ecr_pull_through_cache_rule" "k8s" {
  ecr_repository_prefix = "k8s"
  upstream_registry_url = "registry.k8s.io"
}

resource "aws_ecr_pull_through_cache_rule" "ecr_public" {
  ecr_repository_prefix = "ecr-public"
  upstream_registry_url = "public.ecr.aws"
}

# ---------------------------------------------------------------------------
# CodeArtifact VPC Interface endpoints — REMOVED (FinOps #444, 2026-07-08).
#
# Originally added to keep Maven Central proxy traffic in-VPC for in-cluster
# ARC runners doing Gradle builds (flow: ARC runner pod → codeartifact VPC
# endpoint → CodeArtifact proxy → S3 Gateway endpoint (free) → S3), avoiding
# an estimated ~$25/day of NAT-Bytes cost.
#
# Post-#198 hosted-runner migration audit: that in-cluster Gradle-build flow
# has moved to GitHub-hosted runners, so the endpoints' own justification is
# largely gone. CloudWatch AWS/PrivateLinkEndpoints BytesProcessed over the
# trailing 14 days confirmed it: codeartifact.api ~0.06 GB (~$0.006/mo
# NAT-equivalent), codeartifact.repositories showed ZERO bytes / no metric
# data at all. Fixed cost was ~$23/mo EACH (3 AZs × $0.0105/h × 730h) — both
# fail the keep test by a wide margin. Any residual CodeArtifact calls (e.g.
# the arc-runners.tf codeartifact:GetAuthorizationToken IAM policy) now route
# over NAT; that path is a small, infrequent auth-token call, not bulk dep
# downloads. Watch openbank-nat-egress-daily; if this regresses, restore both
# resources verbatim (see open-bank-oss#444 / git history of this file).
# ---------------------------------------------------------------------------

# ---------------------------------------------------------------------------
# EC2 VPC Interface endpoint — REMOVED (FinOps #444, 2026-07-08).
#
# Originally added to eliminate NAT for aws-node (VPC CNI) EC2 API calls
# (IPAM attach/detach, DescribeNetworkInterfaces, AssignPrivateIpAddresses)
# plus Karpenter (DescribeInstances/CreateFleet/TerminateInstances) and
# ebs-csi-driver (DescribeVolumes/AttachVolume) traffic — a steady background
# poll from kube-system that the D1 FinOps detector watches for as "NAT
# egress 2× rolling avg — kube-system".
#
# Post-#198 hosted-runner migration audit: CloudWatch AWS/PrivateLinkEndpoints
# BytesProcessed showed a steady ~100-190 MiB/day (~4 GB/month) — NAT-equivalent
# cost ~$0.19/month vs the ~$23/month fixed cost of the endpoint (3 AZs ×
# $0.0105/h × 730h). The traffic is real and continuous (this is NOT an idle
# endpoint) but far too small in absolute bytes to justify the fixed cost —
# ~150 MiB/day of added NAT baseline is well below the 20 GB/hour /
# 2×-rolling-avg alarm thresholds. Watch openbank-nat-egress-daily and
# openbank-nat-egress-20gb-per-hour for a "kube-system" anomaly signature; if
# it regresses, restore this resource verbatim (see open-bank-oss#444 / git
# history of this file).
# ---------------------------------------------------------------------------
# IAM: allow Karpenter nodes to create ECR repos on first pull.
# Pull-through cache auto-creates a private repo on first use; the node role
# needs ecr:CreateRepository + ecr:BatchImportUpstreamImage for this.
# The existing AmazonEC2ContainerRegistryReadOnly managed policy covers pulls
# once the repo exists, but NOT the initial creation.
# ---------------------------------------------------------------------------
data "aws_iam_role" "karpenter_node" {
  name = local.karpenter_node_role_name
}

data "aws_iam_policy_document" "ecr_pull_through" {
  statement {
    sid = "PullThroughCacheCreateAndImport"
    actions = [
      "ecr:CreateRepository",
      "ecr:BatchImportUpstreamImage",
      "ecr:TagResource",
    ]
    resources = ["arn:aws:ecr:eu-north-1:265175468565:repository/*"]
  }
}

resource "aws_iam_role_policy" "ecr_pull_through" {
  name   = "ecr-pull-through-cache"
  role   = data.aws_iam_role.karpenter_node.name
  policy = data.aws_iam_policy_document.ecr_pull_through.json
}
