locals {
  admin_ui_evidence_datasets  = ["gate-health", "readiness", "test-intelligence", "security-kpis", "content"]
  admin_ui_evidence_selectors = [for dataset in local.admin_ui_evidence_datasets : "${aws_s3_bucket.test_evidence.arn}/admin-ui/${dataset}/current.json"]
}

# Admin UI evidence publication is independent of image delivery. This role cannot push
# images, edit GitOps, or change other components' immutable test evidence.
data "aws_iam_policy_document" "admin_ui_evidence_assume" {
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
      test     = "StringEqualsIfExists"
      variable = "${local.ci_oidc_host}:job_workflow_ref"
      values   = ["${local.ci_github_repo}/.github/workflows/admin-ui-evidence.yml@refs/heads/main"]
    }
  }
}

resource "aws_iam_role" "admin_ui_evidence" {
  name                 = "${local.cluster_name}-admin-ui-evidence"
  assume_role_policy   = data.aws_iam_policy_document.admin_ui_evidence_assume.json
  max_session_duration = 3600
}

data "aws_iam_policy_document" "admin_ui_evidence" {
  statement {
    sid       = "AdminUiSnapshotsAndConditionalSelectors"
    actions   = ["s3:GetObject", "s3:PutObject"]
    resources = concat(local.admin_ui_evidence_selectors, [for dataset in local.admin_ui_evidence_datasets : "${aws_s3_bucket.test_evidence.arn}/admin-ui/${dataset}/snapshots/*"])
  }
  statement {
    # The publisher lists this exact key before reading the selector, so initial
    # absence is distinguishable without a bucket-wide inventory permission.
    sid       = "DistinguishMissingSelector"
    actions   = ["s3:ListBucket"]
    resources = [aws_s3_bucket.test_evidence.arn]
    condition {
      test     = "StringEquals"
      variable = "s3:prefix"
      values   = [for dataset in local.admin_ui_evidence_datasets : "admin-ui/${dataset}/current.json"]
    }
  }
}

resource "aws_iam_role_policy" "admin_ui_evidence" {
  name   = "gate-health-publication"
  role   = aws_iam_role.admin_ui_evidence.id
  policy = data.aws_iam_policy_document.admin_ui_evidence.json
}

output "admin_ui_evidence_writer_role_arn" {
  value = aws_iam_role.admin_ui_evidence.arn
}
