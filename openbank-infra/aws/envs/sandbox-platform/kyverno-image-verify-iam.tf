# ---------------------------------------------------------------------------
# Kyverno image-signature verification — ECR read via EKS Pod Identity
# (ADR-0029/0030 D4).
#
# The `verify-openbank-image-signatures` ClusterPolicy (gitops/components/
# kyverno/) verifies every `openbank-*` image against the Cosign KMS public key
# at admission. To fetch the image manifest + the `.sig` artifact, Kyverno's
# admission controller must authenticate to the private ECR registry — without a
# durable credential it gets `401 Unauthorized` on the signature pull, so the
# policy can never move past Audit.
#
# We grant ECR read with EKS Pod Identity (principal pods.eks.amazonaws.com),
# the same mechanism as cost-collector/external-dns/cert-manager — no IRSA/OIDC
# SA annotation, so the Kyverno Helm values stay untouched and Pod Identity
# matches purely on (cluster, namespace, service_account).
#
# Scope: only the ADMISSION controller verifies these images (the policy is
# admission:true / background:false / skipBackgroundRequests:true), so only its
# SA is associated. Least privilege: ECR read on the openbank-* repos only.
#
# data.aws_caller_identity.current / data.aws_region.current are declared at
# module scope in arc-runners.tf.
# ---------------------------------------------------------------------------

data "aws_iam_policy_document" "kyverno_image_verify_assume" {
  statement {
    actions = ["sts:AssumeRole", "sts:TagSession"]
    principals {
      type        = "Service"
      identifiers = ["pods.eks.amazonaws.com"]
    }
  }
}

resource "aws_iam_role" "kyverno_image_verify" {
  name               = "${local.cluster_name}-kyverno-image-verify"
  assume_role_policy = data.aws_iam_policy_document.kyverno_image_verify_assume.json
  tags               = { Project = "openbank", ManagedBy = "opentofu", Adr = "0029" }
}

data "aws_iam_policy_document" "kyverno_image_verify" {
  statement {
    sid       = "EcrAuth"
    actions   = ["ecr:GetAuthorizationToken"]
    resources = ["*"] # GetAuthorizationToken is account-wide, not resource-scopable
  }
  statement {
    sid = "EcrPull"
    actions = [
      "ecr:BatchGetImage",
      "ecr:GetDownloadUrlForLayer",
    ]
    resources = [
      "arn:aws:ecr:${data.aws_region.current.name}:${data.aws_caller_identity.current.account_id}:repository/openbank-*"
    ]
  }
}

resource "aws_iam_role_policy" "kyverno_image_verify" {
  name   = "ecr-read"
  role   = aws_iam_role.kyverno_image_verify.id
  policy = data.aws_iam_policy_document.kyverno_image_verify.json
}

# Bind the role to the (kyverno, kyverno-admission-controller) service account.
# The SA is created by the Kyverno Helm chart (ArgoCD-owned); Pod Identity
# matches by name, so the SA need not exist when this is applied.
resource "aws_eks_pod_identity_association" "kyverno_admission" {
  cluster_name    = local.cluster_name
  namespace       = "kyverno"
  service_account = "kyverno-admission-controller"
  role_arn        = aws_iam_role.kyverno_image_verify.arn
}

output "kyverno_image_verify_role_arn" {
  value = aws_iam_role.kyverno_image_verify.arn
}
