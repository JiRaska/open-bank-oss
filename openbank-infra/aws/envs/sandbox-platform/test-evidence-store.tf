# ---------------------------------------------------------------------------
# Commit-addressed Test Intelligence evidence store (#13201).
#
# admin-ui's Test Intelligence page joins every component to the commit it is RUNNING
# (from the image tag) and reads that commit's evidence from here:
#
#   https://<bucket>.s3.<region>.amazonaws.com/<component>/<sha8>/test-intelligence.json
#
# <sha8> is exactly the prefix auto-deploy writes into the image tag; the body is the
# component's projected ComponentTestPosture. Evidence about a commit never changes, so
# objects are WRITE-ONCE: the bucket policy refuses any PutObject that is not a
# conditional create (`If-None-Match: *`), and a second upload of the same key answers
# 412 instead of replacing what the first run published.
#
# Writers: `_service-ci.yml`'s publish job (tests ran on the built commit) and
# `test-evidence-reconcile.yml` (running image commit, tested on a commit proven build-identical),
# both on main, via GitHub OIDC — no static keys. PutObject only.
#
# Reader: admin-ui, with NO credential. Access is granted to the VPC's existing S3
# gateway endpoint (aws:SourceVpce), so the objects are readable from inside the VPC and
# from nowhere else. Chosen over an in-cluster read proxy because it needs no new
# workload, no certificate and costs nothing (gateway endpoints are free): S3's own
# regional endpoint already presents HTTPS, and the gateway endpoint is already on the
# private route table every node uses. A policy whose only principal-"*" grant is pinned
# to a VPC endpoint is not "public" to S3 Block Public Access, so all four blocks stay on.
# ListBucket is granted alongside GetObject on purpose: without it S3 answers a missing
# key with 403, which the reader counts as a store failure and trips its breaker, instead
# of 404 ("not yet published"). Keys are component/commit names — nothing secret.
#
# Retention: 400 days. A component that is not redeployed for longer than that loses its
# evidence and renders as "not published" — honest, not wrong.
# ---------------------------------------------------------------------------

locals {
  test_evidence_bucket = "${local.cluster_name}-test-evidence"
  # The only workflows that may write, as run from main. A reusable workflow's token names
  # itself in job_workflow_ref; a top-level one names its own file.
  test_evidence_writer_workflows = [
    "${local.ci_github_repo}/.github/workflows/_service-ci.yml@refs/heads/main",
    "${local.ci_github_repo}/.github/workflows/test-evidence-reconcile.yml@refs/heads/main",
  ]
}

data "aws_vpc_endpoint" "s3_gateway" {
  vpc_id       = local.s.vpc_id
  service_name = "com.amazonaws.${local.region}.s3"
  filter {
    name   = "vpc-endpoint-type"
    values = ["Gateway"]
  }
}

resource "aws_s3_bucket" "test_evidence" {
  bucket        = local.test_evidence_bucket
  force_destroy = true # sandbox only — the evidence is reproducible by re-running CI
  tags          = { Project = "openbank", ManagedBy = "opentofu", Issue = "13201" }
}

resource "aws_s3_bucket_public_access_block" "test_evidence" {
  bucket                  = aws_s3_bucket.test_evidence.id
  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

resource "aws_s3_bucket_ownership_controls" "test_evidence" {
  bucket = aws_s3_bucket.test_evidence.id
  rule {
    object_ownership = "BucketOwnerEnforced"
  }
}

resource "aws_s3_bucket_server_side_encryption_configuration" "test_evidence" {
  bucket = aws_s3_bucket.test_evidence.id
  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm = "AES256"
    }
  }
}

# Versioning is the backstop to the write-once policy: if the policy were ever loosened,
# an overwrite would leave the first body recoverable as a noncurrent version.
resource "aws_s3_bucket_versioning" "test_evidence" {
  bucket = aws_s3_bucket.test_evidence.id
  versioning_configuration {
    status = "Enabled"
  }
}

resource "aws_s3_bucket_lifecycle_configuration" "test_evidence" {
  bucket     = aws_s3_bucket.test_evidence.id
  depends_on = [aws_s3_bucket_versioning.test_evidence]
  rule {
    id     = "issue-13201-retention"
    status = "Enabled"
    filter {}
    expiration {
      days = 400
    }
    noncurrent_version_expiration {
      noncurrent_days = 30
    }
    abort_incomplete_multipart_upload {
      days_after_initiation = 1
    }
  }
}

data "aws_iam_policy_document" "test_evidence_bucket" {
  statement {
    sid     = "DenyInsecureTransport"
    effect  = "Deny"
    actions = ["s3:*"]
    resources = [
      aws_s3_bucket.test_evidence.arn,
      "${aws_s3_bucket.test_evidence.arn}/*",
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

  # Write-once: an unconditional PUT (which would overwrite) is refused for everyone,
  # including the writer role. Only `If-None-Match: *` creates are allowed.
  statement {
    sid       = "DenyOverwrite"
    effect    = "Deny"
    actions   = ["s3:PutObject"]
    resources = ["${aws_s3_bucket.test_evidence.arn}/*"]
    principals {
      type        = "*"
      identifiers = ["*"]
    }
    condition {
      test     = "Null"
      variable = "s3:if-none-match"
      values   = ["true"]
    }
  }

  statement {
    sid       = "ReadFromClusterVpcOnly"
    effect    = "Allow"
    actions   = ["s3:GetObject", "s3:ListBucket"]
    resources = [aws_s3_bucket.test_evidence.arn, "${aws_s3_bucket.test_evidence.arn}/*"]
    principals {
      type        = "*"
      identifiers = ["*"]
    }
    condition {
      test     = "StringEquals"
      variable = "aws:SourceVpce"
      values   = [data.aws_vpc_endpoint.s3_gateway.id]
    }
  }
}

resource "aws_s3_bucket_policy" "test_evidence" {
  bucket     = aws_s3_bucket.test_evidence.id
  policy     = data.aws_iam_policy_document.test_evidence_bucket.json
  depends_on = [aws_s3_bucket_public_access_block.test_evidence]
}

# ---------------------------------------------------------------------------
# Writer role — GitHub OIDC, main pushes of this repo, the reusable service CI only.
# ---------------------------------------------------------------------------
data "aws_iam_policy_document" "test_evidence_writer_assume" {
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
    # Same IfExists caveat as the apply role: older runners omit the claim.
    condition {
      test     = "StringEqualsIfExists"
      variable = "${local.ci_oidc_host}:job_workflow_ref"
      values   = local.test_evidence_writer_workflows
    }
  }
}

resource "aws_iam_role" "test_evidence_writer" {
  name                 = "${local.cluster_name}-test-evidence-writer"
  assume_role_policy   = data.aws_iam_policy_document.test_evidence_writer_assume.json
  max_session_duration = 3600
  tags                 = { Project = "openbank", ManagedBy = "opentofu", Issue = "13201" }
}

data "aws_iam_policy_document" "test_evidence_writer" {
  statement {
    sid = "PutEvidenceOnly"
    # PutObject only — no Get, no Delete, no List. Keys are <component>/<sha8>/<file>;
    # every component is an `openbank-*` folder.
    actions   = ["s3:PutObject"]
    resources = ["${aws_s3_bucket.test_evidence.arn}/openbank-*/*/test-intelligence.json"]
  }
}

resource "aws_iam_role_policy" "test_evidence_writer" {
  name   = "s3-test-evidence-put"
  role   = aws_iam_role.test_evidence_writer.id
  policy = data.aws_iam_policy_document.test_evidence_writer.json
}

output "test_evidence_bucket" {
  value = aws_s3_bucket.test_evidence.bucket
}

output "test_evidence_store_url" {
  value = "https://${aws_s3_bucket.test_evidence.bucket}.s3.${local.region}.amazonaws.com"
}

output "test_evidence_writer_role_arn" {
  value = aws_iam_role.test_evidence_writer.arn
}
