data "aws_caller_identity" "current" {}

# ---------------------------------------------------------------------------
# Secret envelope-encryption key. EKS encrypts Kubernetes Secrets in etcd with
# this CMK (defense-in-depth beyond AWS-managed etcd encryption).
# ---------------------------------------------------------------------------
resource "aws_kms_key" "secrets" {
  description             = "${var.name} EKS secret encryption"
  enable_key_rotation     = true
  deletion_window_in_days = 7
  tags                    = var.tags
}

resource "aws_kms_alias" "secrets" {
  name          = "alias/${var.name}-eks-secrets"
  target_key_id = aws_kms_key.secrets.key_id
}

# ---------------------------------------------------------------------------
# Control-plane IAM role.
# ---------------------------------------------------------------------------
data "aws_iam_policy_document" "cluster_assume" {
  statement {
    actions = ["sts:AssumeRole"]
    principals {
      type        = "Service"
      identifiers = ["eks.amazonaws.com"]
    }
  }
}

resource "aws_iam_role" "cluster" {
  name               = "${var.name}-cluster"
  assume_role_policy = data.aws_iam_policy_document.cluster_assume.json
  tags               = var.tags
}

resource "aws_iam_role_policy_attachment" "cluster" {
  role       = aws_iam_role.cluster.name
  policy_arn = "arn:aws:iam::aws:policy/AmazonEKSClusterPolicy"
}

resource "aws_cloudwatch_log_group" "cluster" {
  name              = "/aws/eks/${var.name}/cluster"
  retention_in_days = 30
  tags              = var.tags
}

# ---------------------------------------------------------------------------
# Cluster. authentication_mode = API (access entries, not the aws-auth
# configmap). The creating principal gets admin via bootstrap_*; additional
# admins come through access entries below.
# ---------------------------------------------------------------------------
resource "aws_eks_cluster" "this" {
  name     = var.name
  version  = var.kubernetes_version
  role_arn = aws_iam_role.cluster.arn

  vpc_config {
    subnet_ids              = concat(var.private_subnet_ids, var.public_subnet_ids)
    endpoint_private_access = true
    endpoint_public_access  = var.endpoint_public_access
    public_access_cidrs     = var.public_access_cidrs
  }

  access_config {
    authentication_mode                         = "API"
    bootstrap_cluster_creator_admin_permissions = true
  }

  encryption_config {
    provider {
      key_arn = aws_kms_key.secrets.arn
    }
    resources = ["secrets"]
  }

  # FinOps: audit + controllerManager + scheduler are the dominant log volume
  # drivers (audit logs every API call — Karpenter scaling, ArgoCD syncs, ARC
  # runner pod churn = 10+ GB/day = ~$5.80/day in CloudWatch ingest at $0.54/GB).
  # Keep api (API server errors) + authenticator (auth failures) for debugging.
  # Restore the full set if a compliance audit requires it.
  enabled_cluster_log_types = ["api", "authenticator"]

  tags = var.tags

  depends_on = [
    aws_iam_role_policy_attachment.cluster,
    aws_cloudwatch_log_group.cluster,
  ]
}

# ---------------------------------------------------------------------------
# OIDC provider for IRSA. Pod Identity (agent addon below) is preferred for new
# workloads, but several charts (ALB controller, ESO) still expect IRSA, so the
# provider is created up front.
# ---------------------------------------------------------------------------
data "tls_certificate" "oidc" {
  url = aws_eks_cluster.this.identity[0].oidc[0].issuer
}

resource "aws_iam_openid_connect_provider" "this" {
  url             = aws_eks_cluster.this.identity[0].oidc[0].issuer
  client_id_list  = ["sts.amazonaws.com"]
  thumbprint_list = [data.tls_certificate.oidc.certificates[0].sha1_fingerprint]
  tags            = var.tags
}

# ---------------------------------------------------------------------------
# Admin access entries (e.g. the SSO AdministratorAccess role) -> cluster admin.
# ---------------------------------------------------------------------------
resource "aws_eks_access_entry" "admin" {
  for_each      = toset(var.admin_access_principal_arns)
  cluster_name  = aws_eks_cluster.this.name
  principal_arn = each.value
  type          = "STANDARD"
}

resource "aws_eks_access_policy_association" "admin" {
  for_each      = toset(var.admin_access_principal_arns)
  cluster_name  = aws_eks_cluster.this.name
  principal_arn = each.value
  policy_arn    = "arn:aws:iam::aws:policy/AmazonEKSClusterAdminPolicy"

  access_scope {
    type = "cluster"
  }

  depends_on = [aws_eks_access_entry.admin]
}

# ---------------------------------------------------------------------------
# Bootstrap managed node group. Hosts system pods + Karpenter controller +
# ArgoCD; Karpenter then provisions all other capacity (Spot/Graviton). Kept
# small and on-demand so the cluster always has a stable place to schedule
# controllers even if Spot is reclaimed.
# ---------------------------------------------------------------------------
data "aws_iam_policy_document" "node_assume" {
  statement {
    actions = ["sts:AssumeRole"]
    principals {
      type        = "Service"
      identifiers = ["ec2.amazonaws.com"]
    }
  }
}

resource "aws_iam_role" "node" {
  name               = "${var.name}-node"
  assume_role_policy = data.aws_iam_policy_document.node_assume.json
  tags               = var.tags
}

resource "aws_iam_role_policy_attachment" "node" {
  for_each = toset([
    "arn:aws:iam::aws:policy/AmazonEKSWorkerNodePolicy",
    "arn:aws:iam::aws:policy/AmazonEKS_CNI_Policy",
    "arn:aws:iam::aws:policy/AmazonEC2ContainerRegistryReadOnly",
    "arn:aws:iam::aws:policy/AmazonSSMManagedInstanceCore",
  ])
  role       = aws_iam_role.node.name
  policy_arn = each.value
}

resource "aws_eks_node_group" "bootstrap" {
  cluster_name    = aws_eks_cluster.this.name
  node_group_name = "${var.name}-bootstrap"
  node_role_arn   = aws_iam_role.node.arn
  subnet_ids      = var.private_subnet_ids
  ami_type        = "AL2023_ARM_64_STANDARD"
  capacity_type   = "ON_DEMAND"
  instance_types  = var.node_instance_types

  scaling_config {
    desired_size = var.node_desired_size
    min_size     = var.node_min_size
    max_size     = var.node_max_size
  }

  update_config {
    max_unavailable = 1
  }

  # EKS Node Auto Repair: replaces nodes that boot but never join the cluster
  # (or go NotReady) — the ASG's EC2 health check alone can't detect this class
  # of guest-level hang (silent zombie instance, fully billed, never Ready).
  node_repair_config {
    enabled = true
  }

  labels = {
    "openbank.io/pool" = "bootstrap"
  }

  tags = var.tags

  depends_on = [
    aws_iam_role_policy_attachment.node,
    aws_eks_addon.vpc_cni,
  ]

  lifecycle {
    # min/max size are handed off to aws_autoscaling_schedule (sandbox-substrate
    # bootstrap-schedule.tf) for the overnight scale-down. Ignoring them here,
    # same as desired_size, stops `tofu apply` from fighting the scheduled
    # action and reverting it back to the Terraform-declared values mid-window.
    ignore_changes = [
      scaling_config[0].desired_size,
      scaling_config[0].min_size,
      scaling_config[0].max_size,
    ]
  }
}

# ---------------------------------------------------------------------------
# Core addons. vpc-cni and kube-proxy install before nodes (before_compute);
# coredns and the pod-identity agent need running nodes, so they depend on the
# node group.
# ---------------------------------------------------------------------------
data "aws_eks_addon_version" "this" {
  for_each           = toset(["vpc-cni", "kube-proxy", "coredns", "eks-pod-identity-agent", "aws-ebs-csi-driver"])
  addon_name         = each.value
  kubernetes_version = aws_eks_cluster.this.version
}

resource "aws_eks_addon" "vpc_cni" {
  cluster_name                = aws_eks_cluster.this.name
  addon_name                  = "vpc-cni"
  addon_version               = data.aws_eks_addon_version.this["vpc-cni"].version
  resolve_conflicts_on_update = "OVERWRITE"
  tags                        = var.tags

  # NetworkPolicy enforcement (ADR-0081): deploys the aws-network-policy-agent
  # alongside the CNI so the declared GitOps NetworkPolicies (generated by
  # scripts/gen-network-policies.py) actually take effect. Until this is
  # applied (manual platform-tofu dispatch, ADR-0060) the policies are inert.
  # Kubelet health probes keep working under enforcement — the agent always
  # admits traffic from the node's own IP.
  #
  # nodeAgent.enablePolicyEventLogs (issue #2691 stage 1, runbook 0010): make the
  # node agent write an ACCEPT/DENY verdict per flow to its node-local log. This is
  # OBSERVATION, not enforcement — it changes no verdict, only whether the verdict
  # is written down. It is here because the agent has no audit mode: its two modes
  # are `standard` and `strict` and neither is log-only, so "default-deny in audit
  # mode on one namespace" cannot be expressed on this CNI and the flow log is the
  # only way to learn what a default-deny baseline would drop before applying one.
  #
  # enableCloudWatchLogs stays FALSE deliberately: the logs remain a file on the
  # node ($0). Shipping every cross-pod flow verdict on this fleet to CloudWatch is
  # the same ingest bill the cluster log groups above were already trimmed for.
  #
  # Harvest and the comparison procedure:
  #   docs/runbooks/0010-networkpolicy-default-deny-measurement.md
  configuration_values = jsonencode({
    enableNetworkPolicy = "true"
    nodeAgent = {
      enablePolicyEventLogs = "true"
      enableCloudWatchLogs  = "false"
    }
  })
}

resource "aws_eks_addon" "kube_proxy" {
  cluster_name                = aws_eks_cluster.this.name
  addon_name                  = "kube-proxy"
  addon_version               = data.aws_eks_addon_version.this["kube-proxy"].version
  resolve_conflicts_on_update = "OVERWRITE"
  tags                        = var.tags
}

resource "aws_eks_addon" "coredns" {
  cluster_name                = aws_eks_cluster.this.name
  addon_name                  = "coredns"
  addon_version               = data.aws_eks_addon_version.this["coredns"].version
  resolve_conflicts_on_update = "OVERWRITE"
  tags                        = var.tags
  depends_on                  = [aws_eks_node_group.bootstrap]
}

resource "aws_eks_addon" "pod_identity" {
  cluster_name                = aws_eks_cluster.this.name
  addon_name                  = "eks-pod-identity-agent"
  addon_version               = data.aws_eks_addon_version.this["eks-pod-identity-agent"].version
  resolve_conflicts_on_update = "OVERWRITE"
  tags                        = var.tags
  depends_on                  = [aws_eks_node_group.bootstrap]
}

# ---------------------------------------------------------------------------
# EBS CSI driver — the in-tree kubernetes.io/aws-ebs provisioner is removed in
# EKS 1.31, so persistent volumes (CNPG Postgres, etc.) need this addon. Auth is
# EKS Pod Identity (preferred over IRSA): the addon's controller SA assumes a
# role carrying the AWS-managed AmazonEBSCSIDriverPolicy. The default gp3
# StorageClass that consumes it is created in the platform root.
# ---------------------------------------------------------------------------
data "aws_iam_policy_document" "ebs_csi_assume" {
  statement {
    actions = ["sts:AssumeRole", "sts:TagSession"]
    principals {
      type        = "Service"
      identifiers = ["pods.eks.amazonaws.com"]
    }
  }
}

resource "aws_iam_role" "ebs_csi" {
  name               = "${var.name}-ebs-csi"
  assume_role_policy = data.aws_iam_policy_document.ebs_csi_assume.json
  tags               = var.tags
}

resource "aws_iam_role_policy_attachment" "ebs_csi" {
  role       = aws_iam_role.ebs_csi.name
  policy_arn = "arn:aws:iam::aws:policy/service-role/AmazonEBSCSIDriverPolicy"
}

resource "aws_eks_addon" "ebs_csi" {
  cluster_name                = aws_eks_cluster.this.name
  addon_name                  = "aws-ebs-csi-driver"
  addon_version               = data.aws_eks_addon_version.this["aws-ebs-csi-driver"].version
  resolve_conflicts_on_update = "OVERWRITE"
  tags                        = var.tags

  pod_identity_association {
    role_arn        = aws_iam_role.ebs_csi.arn
    service_account = "ebs-csi-controller-sa"
  }

  depends_on = [aws_eks_node_group.bootstrap, aws_eks_addon.pod_identity]
}
