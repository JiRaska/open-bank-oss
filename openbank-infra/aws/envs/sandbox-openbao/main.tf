# OpenBao access configuration as code (#11707).
#
# Before this stack, OpenBao's policies and auth roles existed only in the running cluster, so
# every change needed a privileged token held by a human. Here they are reviewed HCL, applied by
# the operator's AWS SSO identity through OpenBao's `aws` auth method. Bootstrap once with a
# privileged token (runbook 0027), revoke it, and never use one again.
#
# Scope is deliberately ACCESS ONLY: policies and auth roles. Secret VALUES are never managed
# here — they would land in state.

provider "vault" {
  address          = var.openbao_addr
  skip_child_token = true

  # Bootstrap reads BAO_TOKEN/VAULT_TOKEN from the environment; every later run logs in as the
  # operator's AWS SSO role. The role name is a literal on purpose: a provider block cannot
  # reference a resource it is about to create.
  dynamic "auth_login_aws" {
    for_each = var.bootstrap ? [] : [1]
    content {
      role         = "openbao-config-admin"
      mount        = "aws"
      header_value = var.iam_server_id_header
    }
  }
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

# Created by hand on 2026-10-01 (#10896) before this stack existed: adopted, not recreated.
import {
  to = vault_policy.sso_writer
  id = "openbank-sso-writer"
}

# --- aws auth: the operator's AWS SSO role administers this stack ------------------------------

resource "vault_auth_backend" "aws" {
  type        = "aws"
  path        = "aws"
  description = "AWS IAM auth: operator SSO role administers OpenBao policies/auth (envs/sandbox-openbao)"
}

resource "vault_aws_auth_backend_client" "aws" {
  backend                    = vault_auth_backend.aws.path
  iam_server_id_header_value = var.iam_server_id_header
}

resource "vault_aws_auth_backend_role" "config_admin" {
  backend                  = vault_auth_backend.aws.path
  role                     = "openbao-config-admin"
  auth_type                = "iam"
  bound_iam_principal_arns = var.admin_principal_arns
  # The SSO permission-set role carries a random suffix, so the bound ARN is a wildcard, and a
  # wildcard requires this.
  resolve_aws_unique_ids = false
  token_policies         = [vault_policy.config_admin.name]
  token_ttl              = 900
  token_max_ttl          = 900
}

# --- oidc: the SSO writer role (one Keycloak subject, 15-minute tokens) ------------------------

resource "vault_jwt_auth_backend_role" "sso_writer" {
  backend         = "oidc"
  role_name       = "openbank-sso-writer"
  role_type       = "oidc"
  user_claim      = "sub"
  bound_subject   = var.sso_writer_subject
  bound_audiences = ["openbao"]
  oidc_scopes     = ["openid", "profile"]
  allowed_redirect_uris = [
    "http://localhost:8250/oidc/callback",
    "http://localhost:8200/ui/vault/auth/oidc/oidc/callback",
  ]
  token_policies = ["default", vault_policy.sso_writer.name]
  token_ttl      = 900
  token_max_ttl  = 900
}

import {
  to = vault_jwt_auth_backend_role.sso_writer
  id = "auth/oidc/role/openbank-sso-writer"
}
