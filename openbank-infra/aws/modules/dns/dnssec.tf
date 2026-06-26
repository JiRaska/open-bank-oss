# ---------------------------------------------------------------------------
# DNSSEC signing for the public zone.
#
# Route53 signs the zone with a Key-Signing Key (KSK) backed by an asymmetric
# KMS CMK. The CMK MUST live in us-east-1 and be ECC_NIST_P256 / SIGN_VERIFY —
# that is a hard Route53 requirement, hence the aliased provider.
#
# Signing the zone is SAFE on its own: validating resolvers only treat the zone
# as "secure" once a matching DS record exists in the parent (.tech) zone at the
# REGISTRAR. open-bank.tech is registered at WEDOS (external), so publishing the
# DS is a one-time manual step — copy `dnssec_ds_record` (outputs.tf) into the
# WEDOS domain admin AFTER this applies and the zone reports INTERNAL_FAILURE-free
# signing. Never publish the DS before the zone is signed, or you SERVFAIL the
# whole domain.
# ---------------------------------------------------------------------------

data "aws_caller_identity" "current" {}

# --- KMS CMK for the KSK (us-east-1, asymmetric ECDSA P-256) ---------------
resource "aws_kms_key" "dnssec" {
  provider                 = aws.us_east_1
  description              = "Route53 DNSSEC key-signing key for ${var.domain}"
  customer_master_key_spec = "ECC_NIST_P256"
  key_usage                = "SIGN_VERIFY"
  deletion_window_in_days  = 7
  policy                   = data.aws_iam_policy_document.dnssec_kms.json
  tags                     = var.tags
}

resource "aws_kms_alias" "dnssec" {
  provider      = aws.us_east_1
  name          = "alias/route53-dnssec-${replace(var.domain, ".", "-")}"
  target_key_id = aws_kms_key.dnssec.key_id
}

# Required key policy: let the Route53 DNSSEC service use the key, and keep the
# account root in control so the key never becomes unmanageable. This mirrors the
# policy AWS documents for Route53 DNSSEC; the SourceAccount conditions scope the
# service principal to this account (confused-deputy hardening).
data "aws_iam_policy_document" "dnssec_kms" {
  statement {
    sid    = "AllowRoute53DnssecService"
    effect = "Allow"
    principals {
      type        = "Service"
      identifiers = ["dnssec-route53.amazonaws.com"]
    }
    actions   = ["kms:DescribeKey", "kms:GetPublicKey", "kms:Sign"]
    resources = ["*"]
    condition {
      test     = "StringEquals"
      variable = "aws:SourceAccount"
      values   = [data.aws_caller_identity.current.account_id]
    }
  }

  statement {
    sid    = "AllowRoute53DnssecCreateGrant"
    effect = "Allow"
    principals {
      type        = "Service"
      identifiers = ["dnssec-route53.amazonaws.com"]
    }
    actions   = ["kms:CreateGrant"]
    resources = ["*"]
    condition {
      test     = "Bool"
      variable = "kms:GrantIsForAWSResource"
      values   = ["true"]
    }
  }

  statement {
    sid    = "AllowAccountAdmin"
    effect = "Allow"
    principals {
      type        = "AWS"
      identifiers = ["arn:aws:iam::${data.aws_caller_identity.current.account_id}:root"]
    }
    actions   = ["kms:*"]
    resources = ["*"]
  }
}

# --- Key-Signing Key + enable signing --------------------------------------
resource "aws_route53_key_signing_key" "this" {
  hosted_zone_id             = aws_route53_zone.this.zone_id
  key_management_service_arn = aws_kms_key.dnssec.arn
  name                       = "${replace(var.domain, ".", "_")}_ksk"
}

resource "aws_route53_hosted_zone_dnssec" "this" {
  hosted_zone_id = aws_route53_key_signing_key.this.hosted_zone_id
  signing_status = "SIGNING"

  # The KSK must be ACTIVE before signing is switched on, and signing must be off
  # before the KSK/CMK can be destroyed. Explicit dependency keeps both orderings
  # correct on create and destroy.
  depends_on = [aws_route53_key_signing_key.this]
}
