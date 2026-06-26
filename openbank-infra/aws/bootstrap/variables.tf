variable "region" {
  description = "AWS region for the state bucket. Phase-0 sandbox lives where the account is set up (eu-north-1)."
  type        = string
  default     = "eu-north-1"
}

variable "environment" {
  description = "Environment name applied as a tag to all resources in this root."
  type        = string
  default     = "bootstrap"
}

variable "state_bucket_name" {
  description = "Globally-unique S3 bucket name for OpenTofu remote state."
  type        = string
  default     = "openbank-tofu-state-265175468565"
}
