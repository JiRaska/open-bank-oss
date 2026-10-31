variable "region" {
  type    = string
  default = "eu-north-1"
}

variable "cluster_name" {
  type    = string
  default = "openbank-sandbox"
}

variable "kubernetes_version" {
  type = string
  # Held at the LIVE control-plane version. This value is applied as-is by any
  # substrate `tofu apply`, so declaring the target early would upgrade the
  # control plane and all six managed add-ons as a side effect of an unrelated
  # apply, out of the order the upgrade needs. The flip to 1.37 is step 4.0 of
  # docs/runbooks/0029-eks-minor-version-upgrade.md, made inside the window.
  default = "1.36"
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
