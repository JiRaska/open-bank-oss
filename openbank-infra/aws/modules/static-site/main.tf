# ---------------------------------------------------------------------------
# Static marketing site: open-bank.tech apex (+ www).
#
# Serverless, cheapest + safest shape for static content:
#   Route53 ALIAS  ->  CloudFront (TLS1.2+, HTTP->HTTPS, security headers)
#                       -> S3 (PRIVATE, reachable only via Origin Access Control)
#
# No public S3, no compute, no cookies. The only third-party JS is the TestFlight
# beta signup (Web3Forms + hCaptcha), explicitly allowlisted in the CSP below.
# Fully isolated from the banking EKS cluster — admin.open-bank.tech (NLB ingress)
# is untouched.
# ---------------------------------------------------------------------------

# --- private origin bucket -------------------------------------------------
resource "aws_s3_bucket" "site" {
  bucket = var.bucket_name
  tags   = var.tags
}

resource "aws_s3_bucket_public_access_block" "site" {
  bucket                  = aws_s3_bucket.site.id
  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

resource "aws_s3_bucket_ownership_controls" "site" {
  bucket = aws_s3_bucket.site.id
  rule { object_ownership = "BucketOwnerEnforced" }
}

resource "aws_s3_bucket_versioning" "site" {
  bucket = aws_s3_bucket.site.id
  versioning_configuration { status = "Enabled" }
}

resource "aws_s3_bucket_server_side_encryption_configuration" "site" {
  bucket = aws_s3_bucket.site.id
  rule {
    apply_server_side_encryption_by_default { sse_algorithm = "AES256" }
  }
}

# Static site files are replaced atomically on each deploy; noncurrent versions
# (old file revisions) have no rollback value beyond a short window. Transition to
# STANDARD_IA after 30 days and expire at 60 days: covers the "we broke the site"
# revert window (Standard), then moves to cheaper storage until final expiry.
resource "aws_s3_bucket_lifecycle_configuration" "site" {
  bucket = aws_s3_bucket.site.id

  rule {
    id     = "expire-old-revisions"
    status = "Enabled"
    filter {}
    noncurrent_version_transition {
      noncurrent_days = 30
      storage_class   = "STANDARD_IA"
    }
    noncurrent_version_expiration {
      noncurrent_days = 60
    }
    abort_incomplete_multipart_upload {
      days_after_initiation = 7
    }
  }
}

# --- TLS cert (CloudFront requires it in us-east-1) ------------------------
resource "aws_acm_certificate" "cert" {
  provider                  = aws.us_east_1
  domain_name               = var.domain
  subject_alternative_names = [for a in var.aliases : a if a != var.domain]
  validation_method         = "DNS"
  tags                      = var.tags

  lifecycle { create_before_destroy = true }
}

resource "aws_route53_record" "cert_validation" {
  for_each = {
    for dvo in aws_acm_certificate.cert.domain_validation_options :
    dvo.domain_name => {
      name   = dvo.resource_record_name
      type   = dvo.resource_record_type
      record = dvo.resource_record_value
    }
  }
  zone_id         = var.zone_id
  name            = each.value.name
  type            = each.value.type
  records         = [each.value.record]
  ttl             = 300
  allow_overwrite = true
}

resource "aws_acm_certificate_validation" "cert" {
  provider                = aws.us_east_1
  certificate_arn         = aws_acm_certificate.cert.arn
  validation_record_fqdns = [for r in aws_route53_record.cert_validation : r.fqdn]
}

# --- Origin Access Control (SigV4, S3 stays private) ----------------------
resource "aws_cloudfront_origin_access_control" "oac" {
  name                              = "${var.bucket_name}-oac"
  origin_access_control_origin_type = "s3"
  signing_behavior                  = "always"
  signing_protocol                  = "sigv4"
}

# --- security response headers --------------------------------------------
resource "aws_cloudfront_response_headers_policy" "sec" {
  name = "${replace(var.domain, ".", "-")}-security-headers"

  security_headers_config {
    strict_transport_security {
      access_control_max_age_sec = 63072000 # 2 years
      include_subdomains         = true
      preload                    = true
      override                   = true
    }
    content_type_options { override = true } # X-Content-Type-Options: nosniff
    frame_options {
      frame_option = "DENY"
      override     = true
    }
    referrer_policy {
      referrer_policy = "strict-origin-when-cross-origin"
      override        = true
    }
    content_security_policy {
      override = true
      # The TestFlight beta signup on the landing page posts to Web3Forms and renders an
      # hCaptcha widget, so those origins have to be allowlisted or the form silently dies:
      # script-src blocks the widget from ever rendering, connect-src blocks the POST, and
      # frame-src (falling back to default-src) blocks the captcha iframe.
      # Everything else stays deny-by-default. See web/landing/README-testflight.md.
      content_security_policy = join("; ", [
        "default-src 'self'",
        "style-src 'self' 'unsafe-inline' https://fonts.googleapis.com https://*.hcaptcha.com",
        "font-src 'self' https://fonts.gstatic.com",
        "img-src 'self' data:",
        # web3forms.com is deliberately absent: the landing page loads no script from it
        # (unversioned, SRI-less third party). Only the form POST to api.web3forms.com remains,
        # which is connect-src/form-action, not script-src.
        "script-src 'self' https://js.hcaptcha.com https://*.hcaptcha.com",
        "connect-src 'self' https://api.web3forms.com https://*.hcaptcha.com",
        "frame-src https://hcaptcha.com https://*.hcaptcha.com",
        "object-src 'none'",
        "base-uri 'self'",
        "form-action 'self' https://api.web3forms.com",
        "frame-ancestors 'none'",
        "upgrade-insecure-requests",
      ])
    }
  }

  # security_headers_config has no native Permissions-Policy / Cross-Origin-Opener-Policy
  # knobs, so set them as custom response headers (still applied at the edge on every response).
  custom_headers_config {
    # Lock down powerful browser features the static landing page never uses.
    items {
      header   = "Permissions-Policy"
      value    = "accelerometer=(), autoplay=(), camera=(), display-capture=(), encrypted-media=(), fullscreen=(self), geolocation=(), gyroscope=(), magnetometer=(), microphone=(), midi=(), payment=(), usb=()"
      override = true
    }
    # Isolate the browsing context (process isolation; blocks cross-origin popups sharing it).
    items {
      header   = "Cross-Origin-Opener-Policy"
      value    = "same-origin"
      override = true
    }
  }

  # Don't advertise the origin software. CloudFront+S3 emits `Server: AmazonS3`; strip it so the
  # response carries no server/version fingerprint at all.
  remove_headers_config {
    items { header = "Server" }
  }
}

# --- CDN -------------------------------------------------------------------
resource "aws_cloudfront_distribution" "cdn" {
  enabled             = true
  is_ipv6_enabled     = true
  comment             = "open-bank.tech static landing"
  default_root_object = "index.html"
  aliases             = var.aliases
  price_class         = "PriceClass_100" # NA + EU edges only = cheapest
  http_version        = "http2and3"
  tags                = var.tags

  origin {
    domain_name              = aws_s3_bucket.site.bucket_regional_domain_name
    origin_id                = "s3-site"
    origin_access_control_id = aws_cloudfront_origin_access_control.oac.id
  }

  default_cache_behavior {
    target_origin_id           = "s3-site"
    viewer_protocol_policy     = "redirect-to-https"
    allowed_methods            = ["GET", "HEAD", "OPTIONS"]
    cached_methods             = ["GET", "HEAD"]
    compress                   = true
    cache_policy_id            = "658327ea-f89d-4fab-a63d-7e88639e58f6" # AWS Managed-CachingOptimized
    response_headers_policy_id = aws_cloudfront_response_headers_policy.sec.id
  }

  # Pretty 404 -> serve index (SPA-style is not needed; keep explicit 404 page if added later)
  custom_error_response {
    error_code            = 403
    response_code         = 404
    response_page_path    = "/index.html"
    error_caching_min_ttl = 10
  }

  restrictions {
    geo_restriction { restriction_type = "none" }
  }

  viewer_certificate {
    acm_certificate_arn      = aws_acm_certificate_validation.cert.certificate_arn
    ssl_support_method       = "sni-only"
    minimum_protocol_version = "TLSv1.2_2021"
  }
}

# --- bucket policy: only this CloudFront distribution may read + TLS-only ----
data "aws_iam_policy_document" "s3_oac" {
  statement {
    sid       = "AllowCloudFrontOAC"
    actions   = ["s3:GetObject"]
    resources = ["${aws_s3_bucket.site.arn}/*"]
    principals {
      type        = "Service"
      identifiers = ["cloudfront.amazonaws.com"]
    }
    condition {
      test     = "StringEquals"
      variable = "AWS:SourceArn"
      values   = [aws_cloudfront_distribution.cdn.arn]
    }
  }

  # Block any non-HTTPS access (defence-in-depth; CloudFront already enforces
  # redirect-to-https, but belt-and-suspenders at the bucket level).
  statement {
    sid     = "DenyInsecureTransport"
    effect  = "Deny"
    actions = ["s3:*"]
    resources = [
      aws_s3_bucket.site.arn,
      "${aws_s3_bucket.site.arn}/*",
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

resource "aws_s3_bucket_policy" "site" {
  bucket     = aws_s3_bucket.site.id
  policy     = data.aws_iam_policy_document.s3_oac.json
  depends_on = [aws_s3_bucket_public_access_block.site]
}

# --- DNS: apex + www -> CloudFront ----------------------------------------
locals {
  cf_alias = {
    name    = aws_cloudfront_distribution.cdn.domain_name
    zone_id = "Z2FDTNDATAQYW2" # CloudFront's fixed hosted-zone ID for alias records
  }
}

resource "aws_route53_record" "a" {
  for_each = toset(var.aliases)
  zone_id  = var.zone_id
  name     = each.value
  type     = "A"
  alias {
    name                   = local.cf_alias.name
    zone_id                = local.cf_alias.zone_id
    evaluate_target_health = false
  }
}

resource "aws_route53_record" "aaaa" {
  for_each = toset(var.aliases)
  zone_id  = var.zone_id
  name     = each.value
  type     = "AAAA"
  alias {
    name                   = local.cf_alias.name
    zone_id                = local.cf_alias.zone_id
    evaluate_target_health = false
  }
}
