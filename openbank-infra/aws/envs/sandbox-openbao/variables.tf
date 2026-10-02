variable "openbao_addr" {
  description = "OpenBao API address. In-cluster only; reach it with `kubectl -n vault port-forward svc/openbao-active 8200:8200`."
  type        = string
  default     = "http://127.0.0.1:8200"
}

variable "operator_subject" {
  description = <<-EOT
    Keycloak user id (the `sub` claim) of the operator bound to both OIDC roles
    (openbao-config-admin and openbank-sso-writer). OpenBao binds a role to one subject; a second
    operator gets a second pair of roles. Supplied at apply time (TF_VAR_operator_subject=<uuid>),
    never committed.
  EOT
  type        = string

  validation {
    condition     = can(regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$", var.operator_subject))
    error_message = "operator_subject must be a Keycloak user id (UUID), not a username."
  }
}
