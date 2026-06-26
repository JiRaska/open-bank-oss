variable "region" {
  description = "Operating region for S3 origin + state (CloudFront/ACM are handled separately)."
  type        = string
  default     = "eu-north-1"
}

variable "domain" {
  description = "Apex domain (its Route53 zone is owned by the substrate stack)."
  type        = string
  default     = "open-bank.tech"
}
