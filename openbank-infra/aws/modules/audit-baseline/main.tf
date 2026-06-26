data "aws_caller_identity" "current" {}
data "aws_partition" "current" {}
data "aws_region" "current" {}

locals {
  account_id = data.aws_caller_identity.current.account_id
  partition  = data.aws_partition.current.partition
  region     = data.aws_region.current.name
  trail_arn  = "arn:${local.partition}:cloudtrail:${local.region}:${local.account_id}:trail/${var.name}-audit"
}

# ===========================================================================
# KMS — one CMK encrypts both the CloudTrail log files and the Config snapshots
# at rest in the log-archive bucket. Kept separate from the EKS secrets CMK:
# different lifecycle (audit retention is multi-year) and different consumers.
# ===========================================================================
resource "aws_kms_key" "audit" {
  description             = "${var.name} audit log encryption (CloudTrail + Config)"
  enable_key_rotation     = true
  deletion_window_in_days = 7
  policy                  = data.aws_iam_policy_document.kms.json
  tags                    = var.tags
}

resource "aws_kms_alias" "audit" {
  name          = "alias/${var.name}-audit"
  target_key_id = aws_kms_key.audit.key_id
}

data "aws_iam_policy_document" "kms" {
  # Root retains full control so we never lock ourselves out of the key.
  statement {
    sid       = "EnableRoot"
    actions   = ["kms:*"]
    resources = ["*"]
    principals {
      type        = "AWS"
      identifiers = ["arn:${local.partition}:iam::${local.account_id}:root"]
    }
  }

  # CloudTrail encrypts each log file with a data key, scoped to this trail.
  statement {
    sid       = "CloudTrailEncrypt"
    actions   = ["kms:GenerateDataKey*", "kms:DescribeKey"]
    resources = ["*"]
    principals {
      type        = "Service"
      identifiers = ["cloudtrail.amazonaws.com"]
    }
    condition {
      test     = "StringEquals"
      variable = "aws:SourceArn"
      values   = [local.trail_arn]
    }
  }

  # AWS Config encrypts to the config-history bucket's default SSE-KMS on two
  # identities (both unconditioned — the request context of Config's writability
  # check and delivery does not carry SourceArn/SourceAccount):
  #  - the config.amazonaws.com SERVICE PRINCIPAL runs the writability check, and
  #  - the recorder ROLE performs the ongoing snapshot delivery.
  # The key is account-local and both principals are this account's Config, so
  # the confused-deputy surface is nil.
  statement {
    sid       = "ConfigServiceEncrypt"
    actions   = ["kms:GenerateDataKey*", "kms:DescribeKey", "kms:Decrypt"]
    resources = ["*"]
    principals {
      type        = "Service"
      identifiers = ["config.amazonaws.com"]
    }
  }
  statement {
    sid       = "ConfigRecorderRoleEncrypt"
    actions   = ["kms:GenerateDataKey*", "kms:DescribeKey", "kms:Decrypt"]
    resources = ["*"]
    principals {
      type        = "AWS"
      identifiers = [aws_iam_role.config.arn]
    }
  }
}

# ===========================================================================
# S3 log-archive bucket — WORM store for the audit trail.
# Object Lock (created with the bucket, irreversible) + COMPLIANCE default
# retention makes every delivered log object immutable for the retention
# window. This is the DORA Art. 12 "tamper-evident, immutable" requirement.
# ===========================================================================
# Object Lock is enabled at creation (irreversible). Teardown note: while the
# trail/recorder run, every delivered object is COMPLIANCE-locked for
# log_retention_days and cannot be deleted by anyone (incl. root) until it
# expires — so `tofu destroy` of this bucket blocks until the trail/recorder
# stop and the last locks lapse. Sandbox keeps that window at 1 day.
resource "aws_s3_bucket" "log_archive" {
  bucket              = "${var.name}-log-archive-${local.account_id}"
  object_lock_enabled = true
  tags                = var.tags
}

resource "aws_s3_bucket_versioning" "log_archive" {
  bucket = aws_s3_bucket.log_archive.id
  versioning_configuration {
    status = "Enabled"
  }
}

resource "aws_s3_bucket_object_lock_configuration" "log_archive" {
  bucket = aws_s3_bucket.log_archive.id
  rule {
    default_retention {
      mode = "COMPLIANCE"
      days = var.log_retention_days
    }
  }
}

resource "aws_s3_bucket_server_side_encryption_configuration" "log_archive" {
  bucket = aws_s3_bucket.log_archive.id
  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm     = "aws:kms"
      kms_master_key_id = aws_kms_key.audit.arn
    }
    bucket_key_enabled = true
  }
}

resource "aws_s3_bucket_public_access_block" "log_archive" {
  bucket                  = aws_s3_bucket.log_archive.id
  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

resource "aws_s3_bucket_policy" "log_archive" {
  bucket = aws_s3_bucket.log_archive.id
  policy = data.aws_iam_policy_document.bucket.json
}

# Lifecycle for the WORM audit log bucket.
# - Objects are COMPLIANCE-locked for log_retention_days (no one can delete them early).
# - After the lock lapses, the lifecycle rule expires noncurrent versions within 30 days
#   and aborts any stuck multipart uploads quickly. Current-version expiry is intentionally
#   omitted: COMPLIANCE Object Lock already drives retention; we only clean up leftovers.
resource "aws_s3_bucket_lifecycle_configuration" "log_archive" {
  bucket = aws_s3_bucket.log_archive.id

  rule {
    id     = "cleanup-after-lock-expires"
    status = "Enabled"
    filter {}
    noncurrent_version_expiration {
      # Noncurrent versions are unlocked once the default retention lapses.
      # Add a 30-day grace period so operators can inspect them before deletion.
      noncurrent_days = var.log_retention_days + 30
    }
    abort_incomplete_multipart_upload {
      days_after_initiation = 7
    }
  }
}

data "aws_iam_policy_document" "bucket" {
  # --- CloudTrail delivery ---
  statement {
    sid       = "CloudTrailAclCheck"
    actions   = ["s3:GetBucketAcl"]
    resources = [aws_s3_bucket.log_archive.arn]
    principals {
      type        = "Service"
      identifiers = ["cloudtrail.amazonaws.com"]
    }
    condition {
      test     = "StringEquals"
      variable = "aws:SourceArn"
      values   = [local.trail_arn]
    }
  }
  statement {
    sid       = "CloudTrailWrite"
    actions   = ["s3:PutObject"]
    resources = ["${aws_s3_bucket.log_archive.arn}/AWSLogs/${local.account_id}/*"]
    principals {
      type        = "Service"
      identifiers = ["cloudtrail.amazonaws.com"]
    }
    condition {
      test     = "StringEquals"
      variable = "s3:x-amz-acl"
      values   = ["bucket-owner-full-control"]
    }
    condition {
      test     = "StringEquals"
      variable = "aws:SourceArn"
      values   = [local.trail_arn]
    }
  }

  # AWS Config delivers to a SEPARATE bucket (config_history) — not this one.
  # Config's PutDeliveryChannel writability probe writes then DELETES a test
  # object, which a COMPLIANCE Object-Lock bucket forbids, so Config can never
  # validate against this WORM store. See the config_history bucket below.

  # Enforce TLS for every request against the audit store.
  statement {
    sid       = "DenyInsecureTransport"
    effect    = "Deny"
    actions   = ["s3:*"]
    resources = [aws_s3_bucket.log_archive.arn, "${aws_s3_bucket.log_archive.arn}/*"]
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

# ===========================================================================
# S3 config-history bucket — AWS Config delivery target. Deliberately NOT
# Object-Lock'd: Config's PutDeliveryChannel writability probe writes then
# DELETES a test object, which a COMPLIANCE-locked bucket forbids (every
# delivery-channel create then fails with InsufficientDeliveryPolicy). Config
# snapshots are supplementary "what the resource looked like" state; the
# tamper-evident audit trail (CloudTrail digest chain) lives in the WORM bucket
# above. This bucket is still versioned + KMS-encrypted + fully private +
# TLS-only, so Config history is recoverable and confidential.
# ===========================================================================
resource "aws_s3_bucket" "config_history" {
  bucket = "${var.name}-config-history-${local.account_id}"
  tags   = var.tags
}

resource "aws_s3_bucket_versioning" "config_history" {
  bucket = aws_s3_bucket.config_history.id
  versioning_configuration {
    status = "Enabled"
  }
}

resource "aws_s3_bucket_server_side_encryption_configuration" "config_history" {
  bucket = aws_s3_bucket.config_history.id
  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm     = "aws:kms"
      kms_master_key_id = aws_kms_key.audit.arn
    }
    bucket_key_enabled = true
  }
}

resource "aws_s3_bucket_public_access_block" "config_history" {
  bucket                  = aws_s3_bucket.config_history.id
  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

resource "aws_s3_bucket_policy" "config_history" {
  bucket = aws_s3_bucket.config_history.id
  policy = data.aws_iam_policy_document.config_bucket.json
}

# Config history has no Object Lock so the lifecycle rule drives the full object
# lifecycle. Transition to Glacier Instant Retrieval at 30 days (same retrieval
# speed, ~68% cheaper storage — Config snapshots are never hot after a few days).
# Expire at config_history_retention_days (default 90 for sandbox).
resource "aws_s3_bucket_lifecycle_configuration" "config_history" {
  bucket = aws_s3_bucket.config_history.id

  rule {
    id     = "tiered-retention"
    status = "Enabled"
    filter {}
    transition {
      days          = 30
      storage_class = "GLACIER_IR"
    }
    expiration {
      days = var.config_history_retention_days
    }
    abort_incomplete_multipart_upload {
      days_after_initiation = 7
    }
  }
}

# Config statements carry no aws:SourceAccount condition: the writability check
# the service runs at PutDeliveryChannel is denied when the condition is present
# (the probe's request context does not satisfy it). Confused-deputy exposure is
# bounded by the resource scope (this account's AWSLogs/<account>/Config/*) and
# the bucket's full public-access block.
data "aws_iam_policy_document" "config_bucket" {
  statement {
    sid       = "ConfigAclCheck"
    actions   = ["s3:GetBucketAcl"]
    resources = [aws_s3_bucket.config_history.arn]
    principals {
      type        = "Service"
      identifiers = ["config.amazonaws.com"]
    }
  }
  statement {
    sid       = "ConfigBucketExistenceCheck"
    actions   = ["s3:ListBucket"]
    resources = [aws_s3_bucket.config_history.arn]
    principals {
      type        = "Service"
      identifiers = ["config.amazonaws.com"]
    }
  }
  statement {
    sid       = "ConfigWrite"
    actions   = ["s3:PutObject"]
    resources = ["${aws_s3_bucket.config_history.arn}/AWSLogs/${local.account_id}/Config/*"]
    principals {
      type        = "Service"
      identifiers = ["config.amazonaws.com"]
    }
  }

  statement {
    sid       = "DenyInsecureTransport"
    effect    = "Deny"
    actions   = ["s3:*"]
    resources = [aws_s3_bucket.config_history.arn, "${aws_s3_bucket.config_history.arn}/*"]
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

# ===========================================================================
# CloudTrail — multi-region management-event trail with log-file validation
# (SHA-256 digest chain) so tampering is detectable independent of S3.
# ===========================================================================
resource "aws_cloudtrail" "audit" {
  name           = "${var.name}-audit"
  s3_bucket_name = aws_s3_bucket.log_archive.id

  is_multi_region_trail         = true
  include_global_service_events = true
  enable_log_file_validation    = true
  kms_key_id                    = aws_kms_key.audit.arn

  dynamic "event_selector" {
    for_each = var.record_s3_data_events ? [1] : []
    content {
      read_write_type           = "All"
      include_management_events = true

      data_resource {
        type   = "AWS::S3::Object"
        values = ["arn:${local.partition}:s3:::"]
      }
    }
  }

  depends_on = [aws_s3_bucket_policy.log_archive]
}

# ===========================================================================
# AWS Config — records the configuration state of every supported resource
# (incl. global) and delivers snapshots to the same WORM bucket. Pairs the
# "who did what" (CloudTrail) with "what the resource looked like" (Config).
# ===========================================================================
resource "aws_iam_role" "config" {
  name               = "${var.name}-config-recorder"
  assume_role_policy = data.aws_iam_policy_document.config_assume.json
  tags               = var.tags
}

data "aws_iam_policy_document" "config_assume" {
  statement {
    actions = ["sts:AssumeRole"]
    principals {
      type        = "Service"
      identifiers = ["config.amazonaws.com"]
    }
  }
}

resource "aws_iam_role_policy_attachment" "config_managed" {
  role       = aws_iam_role.config.name
  policy_arn = "arn:${local.partition}:iam::aws:policy/service-role/AWS_ConfigRole"
}

resource "aws_config_configuration_recorder" "audit" {
  name     = "${var.name}-recorder"
  role_arn = aws_iam_role.config.arn

  recording_group {
    recording_strategy {
      use_only = "ALL_SUPPORTED_RESOURCE_TYPES"
    }
  }

  # Recording cadence. CONTINUOUS records every configuration change; DAILY records one
  # snapshot per resource per day. With no change-triggered Config rules on this account,
  # DAILY keeps full resource-type coverage (no audit gap) while cutting the dominant
  # cost — ConfigurationItems from a high-churn Karpenter/runner estate (FinOps, ADR-0054).
  recording_mode {
    recording_frequency = var.config_recording_frequency
  }
}

# No s3_kms_key_arn: encryption is the bucket's default SSE-KMS (audit CMK), so
# Config does not validate/manage the key directly at PutDeliveryChannel. The
# recorder role's KMS grant (ConfigRecorderRoleEncrypt) lets S3 generate the
# data key on its behalf.
resource "aws_config_delivery_channel" "audit" {
  name           = "${var.name}-delivery"
  s3_bucket_name = aws_s3_bucket.config_history.id

  depends_on = [
    aws_config_configuration_recorder.audit,
    aws_s3_bucket_policy.config_history,
  ]
}

resource "aws_config_configuration_recorder_status" "audit" {
  name       = aws_config_configuration_recorder.audit.name
  is_enabled = true
  depends_on = [aws_config_delivery_channel.audit]
}
