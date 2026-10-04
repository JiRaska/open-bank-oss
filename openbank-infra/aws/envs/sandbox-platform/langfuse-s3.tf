# ---------------------------------------------------------------------------
# Langfuse v3/v4 event store — S3 + SSE-KMS + Pod Identity (ADR-0328 decision 2, wave A).
#
# Langfuse v3+ writes every ingestion event to object storage BEFORE a worker turns it into
# ClickHouse rows, so this bucket holds raw prompts and completions. Hence:
#   - SSE-KMS with a dedicated customer-managed key (ADR-0328 FinOps: one key, $1/month), so read
#     access needs kms:Decrypt on THIS key on top of s3:GetObject;
#   - Block Public Access + a TLS-only bucket policy;
#   - a 30-day lifecycle expiry, equal to the retention window langfuse-retention-cronjob.yaml
#     enforces on the databases. It is a BUCKET property on purpose (same argument as
#     feedback-screenshots.tf): retention of personal data is not left to application code, and
#     Langfuse's own retention policies are Enterprise-only when self-hosted.
#
# Access is EKS Pod Identity on ai-platform/langfuse (the ServiceAccount langfuse-web and
# langfuse-worker run as, gitops/components/ai-platform/langfuse-v3.yaml) — no static keys.
# Credentials are injected at pod ADMISSION: pods created before this association is applied must
# be restarted after it.
# ---------------------------------------------------------------------------

resource "aws_kms_key" "langfuse" {
  description             = "Langfuse event store SSE-KMS (ADR-0328)"
  deletion_window_in_days = 7
  enable_key_rotation     = true
  tags                    = { Project = "openbank", ManagedBy = "opentofu", Adr = "0328" }
}

resource "aws_kms_alias" "langfuse" {
  name          = "alias/${local.cluster_name}-langfuse"
  target_key_id = aws_kms_key.langfuse.key_id
}

resource "aws_s3_bucket" "langfuse" {
  bucket        = "${local.cluster_name}-langfuse"
  force_destroy = true # sandbox only — prod must never set this
  tags          = { Project = "openbank", ManagedBy = "opentofu", Adr = "0328" }
}

resource "aws_s3_bucket_public_access_block" "langfuse" {
  bucket                  = aws_s3_bucket.langfuse.id
  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

resource "aws_s3_bucket_ownership_controls" "langfuse" {
  bucket = aws_s3_bucket.langfuse.id
  rule {
    object_ownership = "BucketOwnerEnforced"
  }
}

resource "aws_s3_bucket_server_side_encryption_configuration" "langfuse" {
  bucket = aws_s3_bucket.langfuse.id
  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm     = "aws:kms"
      kms_master_key_id = aws_kms_key.langfuse.arn
    }
    # S3 Bucket Keys: one data key per bucket-key period instead of one KMS call per PUT, which is
    # what keeps the KMS request line of the FinOps table near zero at up to 200k PUTs/month.
    bucket_key_enabled = true
  }
}

data "aws_iam_policy_document" "langfuse_bucket_policy" {
  statement {
    sid     = "DenyInsecureTransport"
    effect  = "Deny"
    actions = ["s3:*"]
    resources = [
      aws_s3_bucket.langfuse.arn,
      "${aws_s3_bucket.langfuse.arn}/*",
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

resource "aws_s3_bucket_policy" "langfuse" {
  bucket     = aws_s3_bucket.langfuse.id
  policy     = data.aws_iam_policy_document.langfuse_bucket_policy.json
  depends_on = [aws_s3_bucket_public_access_block.langfuse]
}

resource "aws_s3_bucket_lifecycle_configuration" "langfuse" {
  bucket = aws_s3_bucket.langfuse.id
  rule {
    id     = "adr-0328-30-day-retention"
    status = "Enabled"
    filter {}
    # Equal to the retention sweep's window (langfuse-retention-cronjob.yaml, 30 days). Changing one
    # without the other makes the bucket and the databases disagree about what "deleted" means.
    expiration {
      days = 30
    }
    abort_incomplete_multipart_upload {
      days_after_initiation = 3
    }
  }
}

data "aws_iam_policy_document" "langfuse_assume" {
  statement {
    actions = ["sts:AssumeRole", "sts:TagSession"]
    principals {
      type        = "Service"
      identifiers = ["pods.eks.amazonaws.com"]
    }
  }
}

resource "aws_iam_role" "langfuse" {
  name               = "${local.cluster_name}-langfuse"
  assume_role_policy = data.aws_iam_policy_document.langfuse_assume.json
  tags               = { Project = "openbank", ManagedBy = "opentofu", Adr = "0328" }
}

data "aws_iam_policy_document" "langfuse" {
  # The worker reads back what web wrote and Langfuse deletes events on project/trace deletion, so
  # this is read/write/delete on the bucket — but on THIS bucket only.
  statement {
    sid       = "EventObjects"
    actions   = ["s3:PutObject", "s3:GetObject", "s3:DeleteObject"]
    resources = ["${aws_s3_bucket.langfuse.arn}/*"]
  }
  statement {
    sid       = "ListBucket"
    actions   = ["s3:ListBucket"]
    resources = [aws_s3_bucket.langfuse.arn]
  }
  statement {
    sid       = "UseBucketKey"
    actions   = ["kms:GenerateDataKey", "kms:Decrypt"]
    resources = [aws_kms_key.langfuse.arn]
  }
}

resource "aws_iam_role_policy" "langfuse" {
  name   = "s3-langfuse-events"
  role   = aws_iam_role.langfuse.id
  policy = data.aws_iam_policy_document.langfuse.json
}

resource "aws_eks_pod_identity_association" "langfuse" {
  cluster_name    = local.cluster_name
  namespace       = "ai-platform"
  service_account = "langfuse"
  role_arn        = aws_iam_role.langfuse.arn
}

output "langfuse_bucket" {
  value = aws_s3_bucket.langfuse.bucket
}
