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

# docker.io pull-through cache — eliminates NAT for Testcontainers images
# (postgres, redpanda, valkey, apicurio) pulled by every CI job. These are the
# dominant registry-cache NAT spikes (D1 anomaly "registry-cache > 50 GB/day").
# Anonymous Docker Hub pulls are rate-limited per-IP; ECR pull-through pulls
# server-side so our runner IPs are not rate-limited.
# Flow: dind --registry-mirror → ECR pull-through → docker.io upstream (ECR-side)
#       → ecr.dkr VPC endpoint → runner pod. Zero NAT after first cache warm.
# credential_arn omitted: Docker Hub public images work without auth for ECR
# pull-through (ECR fetches anonymously on AWS-side IPs, not runner IPs).
resource "aws_ecr_pull_through_cache_rule" "docker" {
  ecr_repository_prefix = "docker-hub"
  upstream_registry_url = "registry-1.docker.io"
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
# CodeArtifact VPC Interface endpoint — keeps Maven Central proxy traffic
# in-VPC so Gradle dependency downloads never hit the NAT gateway.
#
# Flow: ARC runner pod → codeartifact VPC endpoint → CodeArtifact proxy →
#       S3 Gateway endpoint (free) → S3. Zero NAT for any dep cached in CA.
# Estimated saving: ~$25/day (measured from NAT-Bytes cost before this fix).
#
# The endpoint was deleted 2026-06-15 (was showing 0 bytes because runner
# image lacked aws CLI → token fetch failed → Maven Central fallback over NAT).
# Root cause fixed: runner image digest bumped to ce7b1171 which includes
# aws CLI; CodeArtifact endpoint restored here so both legs work.
# ---------------------------------------------------------------------------
resource "aws_vpc_endpoint" "codeartifact_api" {
  vpc_id              = local.s.vpc_id
  service_name        = "com.amazonaws.${local.region}.codeartifact.api"
  vpc_endpoint_type   = "Interface"
  subnet_ids          = local.s.private_subnet_ids
  security_group_ids  = [local.s.node_security_group_id]
  private_dns_enabled = true

  tags = {
    Name      = "openbank-sandbox-codeartifact-api"
    Project   = "openbank"
    ManagedBy = "tofu"
  }
}

resource "aws_vpc_endpoint" "codeartifact_repositories" {
  vpc_id              = local.s.vpc_id
  service_name        = "com.amazonaws.${local.region}.codeartifact.repositories"
  vpc_endpoint_type   = "Interface"
  subnet_ids          = local.s.private_subnet_ids
  security_group_ids  = [local.s.node_security_group_id]
  private_dns_enabled = true

  tags = {
    Name      = "openbank-sandbox-codeartifact-repositories"
    Project   = "openbank"
    ManagedBy = "tofu"
  }
}

# ---------------------------------------------------------------------------
# EC2 VPC Interface endpoint — eliminates NAT for aws-node (VPC CNI) EC2 API
# calls on every cluster node.
#
# Problem: aws-node (Amazon VPC CNI DaemonSet in kube-system) polls the EC2 API
# continuously for ENI / IP-address management — IPAM attach/detach,
# DescribeNetworkInterfaces, AssignPrivateIpAddresses, etc. Without this endpoint
# every call leaves the VPC over the NAT gateway. With 15-23 nodes each polling
# every few seconds this produces steady background NAT from kube-system that the
# D1 FinOps detector flags as "NAT egress 2× rolling avg — kube-system".
#
# Also used by: Karpenter (DescribeInstances, CreateFleet, TerminateInstances),
# ebs-csi-driver (DescribeVolumes, AttachVolume), EKS node bootstrap.
#
# Fix: once private DNS resolves ec2.eu-north-1.amazonaws.com to an in-VPC IP,
# all these components go entirely over the private endpoint — zero NAT cost.
# ---------------------------------------------------------------------------
resource "aws_vpc_endpoint" "ec2" {
  vpc_id              = local.s.vpc_id
  service_name        = "com.amazonaws.${local.region}.ec2"
  vpc_endpoint_type   = "Interface"
  subnet_ids          = local.s.private_subnet_ids
  security_group_ids  = [local.s.node_security_group_id]
  private_dns_enabled = true

  tags = {
    Name      = "openbank-sandbox-ec2"
    Project   = "openbank"
    ManagedBy = "tofu"
  }
}

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
