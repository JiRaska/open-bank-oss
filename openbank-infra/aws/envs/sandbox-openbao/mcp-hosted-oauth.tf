# Hosted OAuth uses bank-owned signing identities and separate encrypted token custody.
# The workload cannot create, rotate, read private material from, or delete any Transit key.
locals {
  mcp_hosted_signing_keys = toset([
    "mcp-bank-hosted-chatgpt-assertion",
    "mcp-bank-hosted-chatgpt-dpop",
    "mcp-bank-hosted-claude-assertion",
    "mcp-bank-hosted-claude-dpop",
  ])
  mcp_hosted_custody_key = "mcp-hosted-oauth-custody"
}

resource "vault_transit_secret_backend_key" "mcp_hosted_signing" {
  for_each               = local.mcp_hosted_signing_keys
  backend                = local.transit_mount
  name                   = each.value
  type                   = "ecdsa-p256"
  exportable             = false
  allow_plaintext_backup = false
  deletion_allowed       = false
}

resource "vault_transit_secret_backend_key" "mcp_hosted_custody" {
  backend                = local.transit_mount
  name                   = local.mcp_hosted_custody_key
  type                   = "aes256-gcm96"
  derived                = false
  exportable             = false
  allow_plaintext_backup = false
  deletion_allowed       = false
}

resource "vault_policy" "mcp_hosted_oauth" {
  name = "mcp-hosted-oauth"
  policy = join("\n", concat(
    [for key in vault_transit_secret_backend_key.mcp_hosted_signing :
      "path \"${local.transit_mount}/sign/${key.name}\" { capabilities = [\"update\"] }"
    ],
    [for operation in ["encrypt", "decrypt"] :
      "path \"${local.transit_mount}/${operation}/${vault_transit_secret_backend_key.mcp_hosted_custody.name}\" { capabilities = [\"update\"] }"
    ],
  ))
}

resource "vault_kubernetes_auth_backend_role" "mcp_hosted_oauth" {
  backend                          = local.kubernetes_auth_path
  role_name                        = "mcp-hosted-oauth"
  bound_service_account_names      = ["mcp-service"]
  bound_service_account_namespaces = ["platform"]
  token_policies                   = [vault_policy.mcp_hosted_oauth.name]
  token_ttl                        = 600
  token_max_ttl                    = 600
}
