# ---------------------------------------------------------------------------
# Audit-anchor signing — dedicated asymmetric KMS key + EKS Pod Identity.
#
# ADR-0031 D5 requires an anchor that survives a rewrite of the audit database.
# This key is deliberately NOT the release/cosign key: compromise of the audit workload
# must not grant the ability to sign deployable artefacts. The workload gets only Sign,
# Verify and GetPublicKey for this ECC key; the private key never leaves KMS. A key rotation must
# add the replacement ARN and retain this ARN in the policy until every anchor signed by it has
# passed its retention period. Do not replace this explicit list with a wildcard or tag selector.
# ---------------------------------------------------------------------------

resource "aws_kms_key" "audit_anchor" {
  description              = "OpenBank audit hash-chain anchor signing (ADR-0031 D5)"
  customer_master_key_spec = "ECC_NIST_P256"
  key_usage                = "SIGN_VERIFY"
  deletion_window_in_days  = 30
  enable_key_rotation      = false # asymmetric KMS keys do not support automatic rotation

  tags = { Project = "openbank", ManagedBy = "opentofu", Adr = "0031" }
}

resource "aws_kms_alias" "audit_anchor" {
  name          = "alias/openbank-audit-anchor"
  target_key_id = aws_kms_key.audit_anchor.key_id
}

data "aws_iam_policy_document" "audit_anchor_assume" {
  statement {
    actions = ["sts:AssumeRole", "sts:TagSession"]
    principals {
      type        = "Service"
      identifiers = ["pods.eks.amazonaws.com"]
    }
  }
}

resource "aws_iam_role" "audit_anchor" {
  name               = "${local.cluster_name}-audit-anchor"
  assume_role_policy = data.aws_iam_policy_document.audit_anchor_assume.json
  tags               = { Project = "openbank", ManagedBy = "opentofu", Adr = "0031" }
}

data "aws_iam_policy_document" "audit_anchor" {
  statement {
    sid = "SignAndVerifyAuditAnchors"
    actions = [
      "kms:Sign",
      "kms:Verify",
    ]
    resources = [aws_kms_key.audit_anchor.arn]
    condition {
      test     = "StringEquals"
      variable = "kms:SigningAlgorithm"
      values   = ["ECDSA_SHA_256"]
    }
  }
  # GetPublicKey carries no kms:SigningAlgorithm request context. Keep it separate from the
  # constrained Sign/Verify statement or IAM would deny the public verification endpoint.
  statement {
    sid       = "ReadAuditAnchorPublicKey"
    actions   = ["kms:GetPublicKey"]
    resources = [aws_kms_key.audit_anchor.arn]
  }
}

resource "aws_iam_role_policy" "audit_anchor" {
  name   = "audit-anchor-sign-verify"
  role   = aws_iam_role.audit_anchor.id
  policy = data.aws_iam_policy_document.audit_anchor.json
}

# The service account is ArgoCD-owned in gitops/components/audit/audit-service.yaml.
# Pod Identity matches namespace/name, so this can be applied before its Deployment rolls out.
resource "aws_eks_pod_identity_association" "audit_anchor" {
  cluster_name    = local.cluster_name
  namespace       = "audit"
  service_account = "audit-service"
  role_arn        = aws_iam_role.audit_anchor.arn
}

output "audit_anchor_kms_key_arn" {
  value = aws_kms_key.audit_anchor.arn
}
