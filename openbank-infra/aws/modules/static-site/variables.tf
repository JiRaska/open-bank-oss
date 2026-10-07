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

variable "content_security_policy" {
  description = "Optional stricter CSP for a second static site; null preserves the landing site policy."
  type        = string
  default     = null
}

variable "comment" {
  description = "CloudFront distribution description."
  type        = string
  default     = "OpenBank static landing"
}

variable "uncached_api" {
  description = "Serve /api/* from the private S3 origin without CDN caching."
  type        = bool
  default     = false
}
