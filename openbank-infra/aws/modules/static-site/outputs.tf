output "bucket" {
  description = "Origin bucket name (target for `aws s3 sync`)."
  value       = aws_s3_bucket.site.bucket
}

output "distribution_id" {
  description = "CloudFront distribution ID (target for cache invalidation)."
  value       = aws_cloudfront_distribution.cdn.id
}

output "distribution_domain" {
  description = "CloudFront default domain (debug / health check)."
  value       = aws_cloudfront_distribution.cdn.domain_name
}

output "urls" {
  value = [for a in var.aliases : "https://${a}"]
}
