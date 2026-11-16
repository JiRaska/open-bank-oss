# ---------------------------------------------------------------------------
# Standing-critical digest envelope store (issue #6432, ADR-0382).
#
# The daily digest is produced IN-CLUSTER (gitops/components/alert-digest) because CI cannot
# reach Alertmanager, and the weekly RCA review (alert-rca-weekly.yml) runs in CI. This bucket
# is the hand-off between them, chosen so that NO long-lived credential exists on either side:
#
#   writer — EKS Pod Identity for alert-digest/standing-critical-digest; s3:PutObject on
#            observations/* only. No Get, no List, no Delete: it cannot read back or erase
#            history, only add a day.
#   reader — GitHub OIDC, main of this repo, alert-rca-weekly.yml only; List + Get on
#            observations/* only. It cannot write, so a CI job cannot forge an observation.
#
# Retention 120 days: the weekly review reads 7, and the extra margin lets a stalled review be
# replayed later. The ledger it folds into lives in the repo, so expiry loses no history.
# ---------------------------------------------------------------------------

resource "aws_s3_bucket" "alert_digest" {
  # Must equal DIGEST_BUCKET in gitops/components/alert-digest/standing-critical-digest.yaml
  # and in .github/workflows/alert-rca-weekly.yml.
  bucket        = "${local.cluster_name}-alert-digest"
  force_destroy = true # sandbox only — prod must never set this
  tags          = { Project = "openbank", ManagedBy = "opentofu", Adr = "0382", Issue = "6432" }
}

resource "aws_s3_bucket_public_access_block" "alert_digest" {
  bucket                  = aws_s3_bucket.alert_digest.id
  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

resource "aws_s3_bucket_server_side_encryption_configuration" "alert_digest" {
  bucket = aws_s3_bucket.alert_digest.id
  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm = "AES256"
    }
  }
}

data "aws_iam_policy_document" "alert_digest_bucket" {
  statement {
    sid     = "DenyInsecureTransport"
    effect  = "Deny"
    actions = ["s3:*"]
    resources = [
      aws_s3_bucket.alert_digest.arn,
      "${aws_s3_bucket.alert_digest.arn}/*",
    ]
    principals {
      type        = "*"
      identifiers = ["*"]
    }
    condition {
      test     = "Bool"
      variable = "aws:SecureTransport"
      values   = ["false"]
    }
  }
}

resource "aws_s3_bucket_policy" "alert_digest" {
  bucket     = aws_s3_bucket.alert_digest.id
  policy     = data.aws_iam_policy_document.alert_digest_bucket.json
  depends_on = [aws_s3_bucket_public_access_block.alert_digest]
}

resource "aws_s3_bucket_lifecycle_configuration" "alert_digest" {
  bucket = aws_s3_bucket.alert_digest.id
  rule {
    id     = "observation-retention"
    status = "Enabled"
    filter {}
    expiration {
      days = 120
    }
    abort_incomplete_multipart_upload {
      days_after_initiation = 1
    }
  }
}

# ─── writer: the in-cluster CronJob (Pod Identity) ─────────────────────────
data "aws_iam_policy_document" "alert_digest_writer_assume" {
  statement {
    actions = ["sts:AssumeRole", "sts:TagSession"]
    principals {
      type        = "Service"
      identifiers = ["pods.eks.amazonaws.com"]
    }
  }
}

resource "aws_iam_role" "alert_digest_writer" {
  name               = "${local.cluster_name}-alert-digest-writer"
  assume_role_policy = data.aws_iam_policy_document.alert_digest_writer_assume.json
  tags               = { Project = "openbank", ManagedBy = "opentofu", Adr = "0382" }
}

data "aws_iam_policy_document" "alert_digest_writer" {
  statement {
    sid       = "PutObservationOnly"
    actions   = ["s3:PutObject"]
    resources = ["${aws_s3_bucket.alert_digest.arn}/observations/*"]
  }
}

resource "aws_iam_role_policy" "alert_digest_writer" {
  name   = "alert-digest-put-observation"
  role   = aws_iam_role.alert_digest_writer.id
  policy = data.aws_iam_policy_document.alert_digest_writer.json
}

resource "aws_eks_pod_identity_association" "alert_digest_writer" {
  cluster_name    = local.cluster_name
  namespace       = "alert-digest"
  service_account = "standing-critical-digest"
  role_arn        = aws_iam_role.alert_digest_writer.arn
}

# ─── reader: the weekly RCA workflow (GitHub OIDC) ─────────────────────────
data "aws_iam_policy_document" "alert_digest_reader_assume" {
  statement {
    actions = ["sts:AssumeRoleWithWebIdentity"]
    principals {
      type        = "Federated"
      identifiers = [aws_iam_openid_connect_provider.github_actions.arn]
    }
    condition {
      test     = "StringEquals"
      variable = "${local.ci_oidc_host}:aud"
      values   = ["sts.amazonaws.com"]
    }
    condition {
      test     = "StringEquals"
      variable = "${local.ci_oidc_host}:sub"
      values   = ["repo:${local.ci_github_repo}:ref:refs/heads/main"]
    }
    condition {
      test     = "StringEquals"
      variable = "${local.ci_oidc_host}:job_workflow_ref"
      values   = ["${local.ci_github_repo}/.github/workflows/alert-rca-weekly.yml@refs/heads/main"]
    }
  }
}

resource "aws_iam_role" "alert_digest_reader" {
  name                 = "${local.cluster_name}-alert-digest-reader"
  assume_role_policy   = data.aws_iam_policy_document.alert_digest_reader_assume.json
  max_session_duration = 3600
  tags                 = { Project = "openbank", ManagedBy = "opentofu", Adr = "0382" }
}

data "aws_iam_policy_document" "alert_digest_reader" {
  statement {
    sid       = "ListObservations"
    actions   = ["s3:ListBucket"]
    resources = [aws_s3_bucket.alert_digest.arn]
    condition {
      test     = "StringLike"
      variable = "s3:prefix"
      values   = ["observations/*"]
    }
  }
  statement {
    sid       = "ReadObservations"
    actions   = ["s3:GetObject"]
    resources = ["${aws_s3_bucket.alert_digest.arn}/observations/*"]
  }
}

resource "aws_iam_role_policy" "alert_digest_reader" {
  name   = "alert-digest-read-observations"
  role   = aws_iam_role.alert_digest_reader.id
  policy = data.aws_iam_policy_document.alert_digest_reader.json
}

output "alert_digest_reader_role_arn" {
  description = "Set as repository variable ALERT_DIGEST_READER_ROLE_ARN for alert-rca-weekly.yml."
  value       = aws_iam_role.alert_digest_reader.arn
}
