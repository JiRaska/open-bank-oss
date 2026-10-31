# Dedicated, write-only identity for the scheduled aggregate status exporter.
# The public status API has a separate read grant to this single object; neither
# identity can query Prometheus across the boundary.
data "aws_caller_identity" "status_export" {}

data "aws_iam_policy_document" "status_export_assume" {
  statement {
    actions = ["sts:AssumeRole", "sts:TagSession"]
    principals {
      type        = "Service"
      identifiers = ["pods.eks.amazonaws.com"]
    }
  }
}

resource "aws_iam_role" "status_export" {
  name               = "${local.cluster_name}-status-export"
  assume_role_policy = data.aws_iam_policy_document.status_export_assume.json
}

data "aws_iam_policy_document" "status_export" {
  statement {
    sid       = "PutSanitizedVerdictOnly"
    actions   = ["s3:PutObject"]
    resources = ["arn:aws:s3:::openbank-status-data-${data.aws_caller_identity.status_export.account_id}/internal-aggregate.json"]
  }
}

resource "aws_iam_role_policy" "status_export" {
  name   = "status-aggregate-write"
  role   = aws_iam_role.status_export.id
  policy = data.aws_iam_policy_document.status_export.json
}

resource "aws_eks_pod_identity_association" "status_export" {
  cluster_name    = local.cluster_name
  namespace       = "observability"
  service_account = "public-status-export"
  role_arn        = aws_iam_role.status_export.arn
}
