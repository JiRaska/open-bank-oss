# OpenBao access configuration as code (#11707).
#
# Before this stack, OpenBao's policies and auth roles existed only in the running cluster, so
# every change needed a privileged token held by a human. Here they are reviewed HCL.
#
# Who applies it: the operator, logged in through the EXISTING `oidc` mount (Keycloak SSO) with
# role `openbao-config-admin` — a 15-minute token bound to one Keycloak subject. The provider
# reads that token from VAULT_TOKEN. Bootstrap once with a privileged token (runbook 0027);
# after that nobody types an OpenBao token again.
#
# Why not the `aws` auth method: OpenBao does not ship it (cloud auth plugins were removed from
# the builtin catalog; `sys/auth/aws` answers "plugin not found in the catalog: aws"). The
# Keycloak OIDC mount is already the operator's identity for OpenBao, so it is the one to use.
#
# Scope is deliberately ACCESS ONLY: policies and auth roles. Secret VALUES are never managed
# here — they would land in state.

provider "vault" {
  address          = var.openbao_addr
  skip_child_token = true
}

locals {
  oidc_redirect_uris = [
    "http://localhost:8250/oidc/callback",
    "http://localhost:8200/ui/vault/auth/oidc/oidc/callback",
  ]
}

# --- policies ---------------------------------------------------------------------------------

resource "vault_policy" "config_admin" {
  name   = "openbao-config-admin"
  policy = file("${path.module}/policies/openbao-config-admin.hcl")
}

resource "vault_policy" "sso_writer" {
  name   = "openbank-sso-writer"
  policy = file("${path.module}/policies/openbank-sso-writer.hcl")
}

# --- oidc roles (Keycloak SSO; one subject each; 15-minute tokens) -----------------------------

resource "vault_jwt_auth_backend_role" "config_admin" {
  backend               = "oidc"
  role_name             = "openbao-config-admin"
  role_type             = "oidc"
  user_claim            = "sub"
  bound_subject         = var.operator_subject
  bound_audiences       = ["openbao"]
  oidc_scopes           = ["openid", "profile"]
  allowed_redirect_uris = local.oidc_redirect_uris
  token_policies        = ["default", vault_policy.config_admin.name]
  token_ttl             = 900
  token_max_ttl         = 900
}

resource "vault_jwt_auth_backend_role" "sso_writer" {
  backend               = "oidc"
  role_name             = "openbank-sso-writer"
  role_type             = "oidc"
  user_claim            = "sub"
  bound_subject         = var.operator_subject
  bound_audiences       = ["openbao"]
  oidc_scopes           = ["openid", "profile"]
  allowed_redirect_uris = local.oidc_redirect_uris
  token_policies        = ["default", vault_policy.sso_writer.name]
  token_ttl             = 900
  token_max_ttl         = 900
}
