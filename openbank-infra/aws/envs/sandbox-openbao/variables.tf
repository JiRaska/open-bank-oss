variable "openbao_addr" {
  description = "OpenBao API address. In-cluster only; reach it with `kubectl -n vault port-forward svc/openbao-active 8200:8200`."
  type        = string
  default     = "http://127.0.0.1:8200"
}

variable "bootstrap" {
  description = <<-EOT
    true ONLY for the one-time bootstrap (runbook 0027): the provider then uses the token in
    BAO_TOKEN/VAULT_TOKEN instead of logging in through the aws auth method, which does not exist
    yet. Every later apply runs with the default (false) and authenticates as the operator's AWS
    SSO role — no root token.
  EOT
  type        = bool
  default     = false
}

variable "admin_principal_arns" {
  description = <<-EOT
    IAM principals allowed to log in as openbao-config-admin through the aws auth method. Defaults
    to the AWS SSO AdministratorAccess permission set of the sandbox account (the identity that
    already runs platform tofu from a laptop). Wildcards need resolve_aws_unique_ids = false.
  EOT
  type        = list(string)
  default     = ["arn:aws:iam::265175468565:role/aws-reserved/sso.amazonaws.com/*/AWSReservedSSO_AdministratorAccess_*"]
}

variable "iam_server_id_header" {
  description = "X-Vault-AWS-IAM-Server-ID value. Binds a signed login request to THIS OpenBao, so a request captured elsewhere cannot be replayed here."
  type        = string
  default     = "openbao.sandbox.open-bank"
}

variable "sso_writer_subjects" {
  description = <<-EOT
    Keycloak user ids (the `sub` claim) allowed to obtain an openbank-sso-writer token through the
    oidc mount. Supplied at apply time (TF_VAR_sso_writer_subjects='["<uuid>"]'), never committed.
  EOT
  type        = list(string)
}
