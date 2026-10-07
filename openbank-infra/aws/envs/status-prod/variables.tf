variable "region" {
  type    = string
  default = "eu-north-1"
}

variable "zone_name" {
  type    = string
  default = "open-bank.tech"
}

variable "bucket_name" {
  description = "Globally unique private origin bucket name, supplied by the operator."
  type        = string
}
