# Status stays on a private S3 origin and CloudFront, independent of banking EKS.
# The hosted zone belongs to the substrate stack; this state owns only status.* records.
data "aws_route53_zone" "public" {
  name         = var.zone_name
  private_zone = false
}

module "site" {
  source = "../../modules/static-site"
  providers = {
    aws           = aws
    aws.us_east_1 = aws.us_east_1
  }

  domain       = "status.${var.zone_name}"
  aliases      = ["status.${var.zone_name}"]
  zone_id      = data.aws_route53_zone.public.zone_id
  bucket_name  = var.bucket_name
  comment      = "OpenBank independent public status"
  uncached_api = true
  content_security_policy = join("; ", [
    "default-src 'self'",
    "script-src 'self'",
    "style-src 'self'",
    "img-src 'self' data:",
    "connect-src 'self'",
    "object-src 'none'",
    "base-uri 'none'",
    "form-action 'none'",
    "frame-ancestors 'none'",
    "upgrade-insecure-requests",
  ])
  tags = {
    Project     = "openbank"
    ManagedBy   = "opentofu"
    Environment = "prod"
    Component   = "public-status"
  }
}

data "aws_caller_identity" "current" {}
data "aws_partition" "current" {}

# The status publisher runs on an external GitHub runner. Only this repository's
# protected main branch can assume the role; no banking-cluster identity is used.
data "aws_iam_policy_document" "publisher_trust" {
  statement {
    actions = ["sts:AssumeRoleWithWebIdentity"]
    principals {
      type = "Federated"
      identifiers = [
        "arn:${data.aws_partition.current.partition}:iam::${data.aws_caller_identity.current.account_id}:oidc-provider/token.actions.githubusercontent.com",
      ]
    }
    condition {
      test     = "StringEquals"
      variable = "token.actions.githubusercontent.com:aud"
      values   = ["sts.amazonaws.com"]
    }
    condition {
      test     = "StringEquals"
      variable = "token.actions.githubusercontent.com:sub"
      values   = ["repo:JiRaska/open-bank-oss:ref:refs/heads/main"]
    }
  }
}

resource "aws_iam_role" "publisher" {
  name               = "openbank-public-status-publisher"
  assume_role_policy = data.aws_iam_policy_document.publisher_trust.json
}

data "aws_iam_policy_document" "publisher" {
  statement {
    actions   = ["s3:ListBucket"]
    resources = ["arn:${data.aws_partition.current.partition}:s3:::${var.bucket_name}"]
  }
  statement {
    actions   = ["s3:GetObject", "s3:PutObject"]
    resources = ["arn:${data.aws_partition.current.partition}:s3:::${var.bucket_name}/*"]
  }
  statement {
    actions = ["cloudfront:CreateInvalidation"]
    resources = [
      "arn:${data.aws_partition.current.partition}:cloudfront::${data.aws_caller_identity.current.account_id}:distribution/${module.site.distribution_id}",
    ]
  }
}

resource "aws_iam_role_policy" "publisher" {
  name   = "public-status-origin-only"
  role   = aws_iam_role.publisher.id
  policy = data.aws_iam_policy_document.publisher.json
}
