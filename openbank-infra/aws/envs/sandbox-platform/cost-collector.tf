# ---------------------------------------------------------------------------
# FinOps cost-collector — EKS Pod Identity for read-only AWS Cost Explorer.
#
# Realises ADR-0054 phase 2 (periodic cost audit) for the admin-ui FinOps panel.
# A daily in-cluster CronJob (openbank-infra/gitops/components/admin-ui/
# cost-collector.yaml) snapshots `aws ce get-cost-and-usage` into a ConfigMap the
# admin-ui mounts read-only — so the operator console NEVER holds billing IAM in
# its own pod (read-only-consumer rule). Only this short-lived collector SA gets
# the (read-only) Cost Explorer grant, via Pod Identity (same mechanism as
# external-dns/cert-manager — no IRSA/OIDC annotations).
#
# Least privilege: ce:GetCostAndUsage only — no write, no other billing APIs.
# Cost Explorer is global; the API is reached us-east-1 but the grant is global.
# ---------------------------------------------------------------------------

data "aws_iam_policy_document" "cost_collector_assume" {
  statement {
    actions = ["sts:AssumeRole", "sts:TagSession"]
    principals {
      type        = "Service"
      identifiers = ["pods.eks.amazonaws.com"]
    }
  }
}

resource "aws_iam_role" "cost_collector" {
  name               = "${local.cluster_name}-cost-collector"
  assume_role_policy = data.aws_iam_policy_document.cost_collector_assume.json
  tags               = { Project = "openbank", ManagedBy = "opentofu", Adr = "0054" }
}

data "aws_iam_policy_document" "cost_collector" {
  statement {
    sid       = "ReadCostExplorer"
    actions   = ["ce:GetCostAndUsage"]
    resources = ["*"] # Cost Explorer actions are not resource-scopable
  }
}

resource "aws_iam_role_policy" "cost_collector" {
  name   = "cost-explorer-read"
  role   = aws_iam_role.cost_collector.id
  policy = data.aws_iam_policy_document.cost_collector.json
}

# Bind the role to the (admin-ui, cost-collector) service account. The SA itself
# is declared in the gitops manifests (ArgoCD-owned); Pod Identity matches by
# (cluster, namespace, service_account) name — the SA need not exist yet.
resource "aws_eks_pod_identity_association" "cost_collector" {
  cluster_name    = local.cluster_name
  namespace       = "admin-ui"
  service_account = "cost-collector"
  role_arn        = aws_iam_role.cost_collector.arn
}

output "cost_collector_role_arn" {
  value = aws_iam_role.cost_collector.arn
}
