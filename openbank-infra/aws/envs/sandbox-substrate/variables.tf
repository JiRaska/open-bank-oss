variable "region" {
  type    = string
  default = "eu-north-1"
}

variable "cluster_name" {
  type    = string
  default = "openbank-sandbox"
}

variable "kubernetes_version" {
  type    = string
  default = "1.35"
}

variable "domain" {
  description = "Public apex domain for the sandbox (Route53 hosted zone + ACME). Delegate NS at the registrar after apply."
  type        = string
  default     = "open-bank.tech"
}

variable "admin_access_principal_arns" {
  description = "Extra IAM roles granted cluster-admin. The OpenTofu-running SSO role already gets admin via bootstrap_cluster_creator_admin_permissions, so this defaults empty."
  type        = list(string)
  default     = []
}
