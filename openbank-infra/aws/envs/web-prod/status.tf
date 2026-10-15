# The public status surface is a separate S3/CloudFront origin with an independent
# scheduled checker and private state bucket. No EKS or banking database dependency.
data "aws_caller_identity" "status" {}

resource "aws_s3_bucket" "status_data" {
  bucket = "openbank-status-data-${data.aws_caller_identity.status.account_id}"
}

resource "aws_s3_bucket_public_access_block" "status_data" {
  bucket                  = aws_s3_bucket.status_data.id
  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

resource "aws_s3_bucket_server_side_encryption_configuration" "status_data" {
  bucket = aws_s3_bucket.status_data.id
  rule {
    apply_server_side_encryption_by_default { sse_algorithm = "AES256" }
  }
}

resource "aws_s3_bucket_versioning" "status_data" {
  bucket = aws_s3_bucket.status_data.id
  versioning_configuration { status = "Enabled" }
}

resource "aws_s3_bucket_lifecycle_configuration" "status_data" {
  bucket = aws_s3_bucket.status_data.id
  rule {
    id     = "expire-old-snapshots"
    status = "Enabled"
    filter {}
    noncurrent_version_expiration { noncurrent_days = 3 }
  }
}

resource "aws_cloudfront_origin_access_control" "status_api" {
  name                              = "openbank-public-status-api"
  origin_access_control_origin_type = "lambda"
  signing_behavior                  = "always"
  signing_protocol                  = "sigv4"
}

data "archive_file" "status_handler" {
  type        = "zip"
  source_file = "${path.module}/../../../web/status/backend/handler.py"
  output_path = "${path.module}/.terraform/status-handler.zip"
}

data "aws_iam_policy_document" "status_lambda_assume" {
  statement {
    actions = ["sts:AssumeRole"]
    principals {
      type        = "Service"
      identifiers = ["lambda.amazonaws.com"]
    }
  }
}

resource "aws_iam_role" "status_lambda" {
  name               = "openbank-public-status"
  assume_role_policy = data.aws_iam_policy_document.status_lambda_assume.json
}

data "aws_iam_policy_document" "status_lambda" {
  statement {
    sid       = "DetectFirstSnapshot"
    actions   = ["s3:ListBucket"]
    resources = [aws_s3_bucket.status_data.arn]
  }
  statement {
    sid       = "ReadWritePublicStatusSnapshot"
    actions   = ["s3:GetObject", "s3:PutObject"]
    resources = ["${aws_s3_bucket.status_data.arn}/state.json"]
  }
  statement {
    sid       = "ReadSanitizedInternalVerdict"
    actions   = ["s3:GetObject"]
    resources = ["${aws_s3_bucket.status_data.arn}/internal-aggregate.json"]
  }
  statement {
    sid       = "WriteFunctionLogs"
    actions   = ["logs:CreateLogStream", "logs:PutLogEvents"]
    resources = ["${aws_cloudwatch_log_group.status_lambda.arn}:*"]
  }
}

resource "aws_iam_role_policy" "status_lambda" {
  name   = "public-status-state-and-logs"
  role   = aws_iam_role.status_lambda.id
  policy = data.aws_iam_policy_document.status_lambda.json
}

resource "aws_cloudwatch_log_group" "status_lambda" {
  name              = "/aws/lambda/openbank-public-status"
  retention_in_days = 30
}

resource "aws_lambda_function" "status" {
  function_name    = "openbank-public-status"
  role             = aws_iam_role.status_lambda.arn
  handler          = "handler.handler"
  runtime          = "python3.13"
  timeout          = 25
  memory_size      = 256
  filename         = data.archive_file.status_handler.output_path
  source_code_hash = data.archive_file.status_handler.output_base64sha256
  depends_on       = [aws_iam_role_policy.status_lambda]

  environment {
    variables = { STATUS_DATA_BUCKET = aws_s3_bucket.status_data.bucket }
  }
}

resource "aws_lambda_function_url" "status" {
  function_name      = aws_lambda_function.status.function_name
  authorization_type = "AWS_IAM"
}

resource "aws_cloudwatch_event_rule" "status_checks" {
  name                = "openbank-public-status-checks"
  schedule_expression = "rate(2 minutes)"
}

resource "aws_cloudwatch_event_target" "status_checks" {
  rule = aws_cloudwatch_event_rule.status_checks.name
  arn  = aws_lambda_function.status.arn
}

resource "aws_lambda_permission" "status_checks" {
  statement_id  = "AllowScheduledChecks"
  action        = "lambda:InvokeFunction"
  function_name = aws_lambda_function.status.function_name
  principal     = "events.amazonaws.com"
  source_arn    = aws_cloudwatch_event_rule.status_checks.arn
}

module "status_site" {
  source = "../../modules/static-site"
  providers = {
    aws           = aws
    aws.us_east_1 = aws.us_east_1
  }

  domain                       = "status.${var.domain}"
  aliases                      = ["status.${var.domain}"]
  zone_id                      = data.aws_route53_zone.public.zone_id
  bucket_name                  = "openbank-status-web-${data.aws_caller_identity.status.account_id}"
  api_origin_domain_name       = trimsuffix(trimprefix(aws_lambda_function_url.status.function_url, "https://"), "/")
  api_origin_access_control_id = aws_cloudfront_origin_access_control.status_api.id
  serve_missing_as_index       = false

  tags = {
    Project     = "openbank"
    ManagedBy   = "opentofu"
    Environment = "prod"
    Component   = "public-status"
  }
}

# The function URL accepts requests only when signed by this distribution.
resource "aws_lambda_permission" "status_cloudfront_url" {
  statement_id           = "AllowStatusCloudFrontUrl"
  action                 = "lambda:InvokeFunctionUrl"
  function_name          = aws_lambda_function.status.function_name
  principal              = "cloudfront.amazonaws.com"
  source_arn             = module.status_site.distribution_arn
  function_url_auth_type = "AWS_IAM"
}

resource "aws_lambda_permission" "status_cloudfront_invoke" {
  statement_id  = "AllowStatusCloudFrontInvoke"
  action        = "lambda:InvokeFunction"
  function_name = aws_lambda_function.status.function_name
  principal     = "cloudfront.amazonaws.com"
  source_arn    = module.status_site.distribution_arn
}
