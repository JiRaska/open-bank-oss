output "log_archive_bucket" {
  description = "Name of the WORM audit log bucket."
  value       = aws_s3_bucket.log_archive.id
}

output "log_archive_bucket_arn" {
  value = aws_s3_bucket.log_archive.arn
}

output "config_history_bucket" {
  description = "Name of the AWS Config delivery bucket (versioned, not Object-Lock'd)."
  value       = aws_s3_bucket.config_history.id
}

output "cloudtrail_arn" {
  value = aws_cloudtrail.audit.arn
}

output "config_recorder_name" {
  value = aws_config_configuration_recorder.audit.name
}

output "audit_kms_key_arn" {
  value = aws_kms_key.audit.arn
}
