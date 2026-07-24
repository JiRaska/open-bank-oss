# ---------------------------------------------------------------------------
# In-app screen-feedback screenshots — S3 + Pod Identity for customer-edge (ADR-0192).
#
# The app's feedback rail captures a screenshot the user explicitly previews and
# confirms; customer-edge writes it here and puts only the object KEY on
# `openbank.feedback.events`, so the image never enters Kafka or the ClickHouse
# bronze layer (which is retained ≥10 years). One lifecycle-managed bucket is the
# whole footprint of that decision.
#
# Retention is 90 days and it is a BUCKET property on purpose: ADR-0192 commits to
# storage limitation for personal data, and application code must not be the thing
# that decides when a screenshot disappears. Erasure-by-party stays possible for the
# window: the ClickHouse `gold_screen_feedback` view maps party_id -> screenshot_key.
#
# Mechanism: EKS Pod Identity (like cost-collector / db-backups — no IRSA
# annotations); the edge runs as the dedicated `customer-edge` ServiceAccount and
# the AWS SDK default credentials chain picks it up. Deliberately NOT the namespace's
# `default` SA: Redis and the OPA sidecar share that namespace and have no business
# holding S3 credentials.
#
# Grant is write-only (no s3:GetObject, no s3:DeleteObject). The edge only ever puts;
# reading a customer's screenshot is a support/analytics act that must go through a
# separate, audited identity rather than ride on the internet-facing service's role.
# ---------------------------------------------------------------------------

resource "aws_s3_bucket" "feedback_screenshots" {
  bucket        = "${local.cluster_name}-feedback"
  force_destroy = true # sandbox only — prod must never set this
  tags          = { Project = "openbank", ManagedBy = "opentofu", Adr = "0192" }
}

resource "aws_s3_bucket_public_access_block" "feedback_screenshots" {
  bucket                  = aws_s3_bucket.feedback_screenshots.id
  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

resource "aws_s3_bucket_server_side_encryption_configuration" "feedback_screenshots" {
  bucket = aws_s3_bucket.feedback_screenshots.id
  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm = "AES256"
    }
  }
}

data "aws_iam_policy_document" "feedback_screenshots_policy" {
  statement {
    sid     = "DenyInsecureTransport"
    effect  = "Deny"
    actions = ["s3:*"]
    resources = [
      aws_s3_bucket.feedback_screenshots.arn,
      "${aws_s3_bucket.feedback_screenshots.arn}/*",
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

resource "aws_s3_bucket_policy" "feedback_screenshots" {
  bucket     = aws_s3_bucket.feedback_screenshots.id
  policy     = data.aws_iam_policy_document.feedback_screenshots_policy.json
  depends_on = [aws_s3_bucket_public_access_block.feedback_screenshots]
}

resource "aws_s3_bucket_lifecycle_configuration" "feedback_screenshots" {
  bucket = aws_s3_bucket.feedback_screenshots.id
  rule {
    id     = "adr-0192-90-day-retention"
    status = "Enabled"
    filter {}
    # THE storage-limitation control of ADR-0192. Not a cost tweak — do not extend
    # it without revisiting the GDPR terms the app shows the user.
    expiration {
      days = 90
    }
    abort_incomplete_multipart_upload {
      days_after_initiation = 3
    }
  }
}

data "aws_iam_policy_document" "feedback_screenshots_assume" {
  statement {
    actions = ["sts:AssumeRole", "sts:TagSession"]
    principals {
      type        = "Service"
      identifiers = ["pods.eks.amazonaws.com"]
    }
  }
}

resource "aws_iam_role" "feedback_screenshots" {
  name               = "${local.cluster_name}-feedback-screenshots"
  assume_role_policy = data.aws_iam_policy_document.feedback_screenshots_assume.json
  tags               = { Project = "openbank", ManagedBy = "opentofu", Adr = "0192" }
}

data "aws_iam_policy_document" "feedback_screenshots" {
  statement {
    sid = "PutScreenshotsOnly"
    # Write-only, and scoped to the key prefix FeedbackScreenshotStore actually mints.
    # No ListBucket either: the edge addresses objects by a key it just generated, so
    # enumerating other customers' screenshots is not a capability it needs.
    actions   = ["s3:PutObject"]
    resources = ["${aws_s3_bucket.feedback_screenshots.arn}/customer-edge/feedback/*"]
  }
}

resource "aws_iam_role_policy" "feedback_screenshots" {
  name   = "s3-feedback-write"
  role   = aws_iam_role.feedback_screenshots.id
  policy = data.aws_iam_policy_document.feedback_screenshots.json
}

# The edge's own ServiceAccount (openbank-infra/gitops/components/customer-edge/customer-edge.yaml).
# The Rollout must set serviceAccountName: customer-edge or the pods keep running as
# `default` and the association silently never applies — the failure mode is a
# STORE_FAILED on every submission, not a crash.
resource "aws_eks_pod_identity_association" "feedback_screenshots" {
  cluster_name    = local.cluster_name
  namespace       = "customer-edge"
  service_account = "customer-edge"
  role_arn        = aws_iam_role.feedback_screenshots.arn
}

output "feedback_screenshots_bucket" {
  value = aws_s3_bucket.feedback_screenshots.bucket
}
