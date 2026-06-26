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
