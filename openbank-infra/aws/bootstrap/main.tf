# One-time bootstrap: the S3 bucket that holds remote state for every other
# OpenTofu root. Runs with LOCAL state (chicken-and-egg). Native S3 state
# locking (use_lockfile, OpenTofu 1.10+) means no DynamoDB table is needed.

resource "aws_s3_bucket" "state" {
  bucket = var.state_bucket_name

  tags = {
    Project     = "openbank"
    ManagedBy   = "opentofu"
    Environment = var.environment
    Component   = "tf-state"
  }

  # State is the source of truth for live infra — never auto-destroy it.
  lifecycle {
    prevent_destroy = true
  }
}

# Noncurrent versions accumulate indefinitely without this rule. 90 days keeps
# the last ~3 months of state history for rollback while bounding storage cost.
resource "aws_s3_bucket_lifecycle_configuration" "state" {
  bucket = aws_s3_bucket.state.id

  rule {
    id     = "expire-noncurrent-versions"
    status = "Enabled"
    filter {}
    noncurrent_version_expiration {
      noncurrent_days = 90
    }
    abort_incomplete_multipart_upload {
      days_after_initiation = 7
    }
  }
}

resource "aws_s3_bucket_versioning" "state" {
  bucket = aws_s3_bucket.state.id
  versioning_configuration {
    status = "Enabled"
  }
}

resource "aws_s3_bucket_server_side_encryption_configuration" "state" {
  bucket = aws_s3_bucket.state.id
  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm = "aws:kms"
    }
    bucket_key_enabled = true
  }
}

resource "aws_s3_bucket_public_access_block" "state" {
  bucket                  = aws_s3_bucket.state.id
  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

output "state_bucket" {
  value = aws_s3_bucket.state.id
}
