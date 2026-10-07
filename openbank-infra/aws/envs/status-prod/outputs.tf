output "bucket" {
  value = module.site.bucket
}

output "distribution_id" {
  value = module.site.distribution_id
}

output "urls" {
  value = module.site.urls
}

output "publisher_role_arn" {
  value = aws_iam_role.publisher.arn
}
