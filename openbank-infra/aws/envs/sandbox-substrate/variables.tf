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

# ADR-0357 slice 2: Slack delivery of the honeytoken topics via AWS Chatbot. Deliberately no
# defaults and never committed: pass them from a tfvars file outside the repository. Unset
# (null) leaves the Chatbot channel uncreated and email the only delivery.
variable "deception_slack_team_id" {
  description = "Slack workspace (team) id authorised once in the AWS Chatbot console. Keep out of git."
  type        = string
  default     = null
}

variable "deception_slack_channel_id" {
  description = "Slack channel id that receives honeytoken alerts. Keep out of git."
  type        = string
  default     = null
}
