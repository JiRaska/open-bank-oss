variable "domain" {
  description = "Apex domain served by this site (e.g. open-bank.tech)."
  type        = string
}

variable "aliases" {
  description = "Fully-qualified names the distribution answers for (apex + www)."
  type        = list(string)
}

variable "zone_id" {
  description = "Route53 hosted zone ID that owns the domain (looked up in the root)."
  type        = string
}

variable "bucket_name" {
  description = "Globally-unique S3 bucket name for the private origin."
  type        = string
}

variable "tags" {
  type    = map(string)
  default = {}
}

variable "api_origin_domain_name" {
  description = "Optional HTTPS Lambda URL hostname for a read-only /api/* origin."
  type        = string
  default     = null
}

variable "api_origin_access_control_id" {
  description = "CloudFront Lambda origin access control ID when an API origin is configured."
  type        = string
  default     = null
}

variable "serve_missing_as_index" {
  description = "Preserve the marketing site's legacy index-based 404 response."
  type        = bool
  default     = true
}
