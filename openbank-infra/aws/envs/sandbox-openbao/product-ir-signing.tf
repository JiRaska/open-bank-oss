# Product-IR signing (ADR-0352): the Transit key that signs an IR-bearing product revision's
# canonical hash, and the three identities allowed to use it.
#
#   product-catalog  (accounts) — sign   (role product-catalog-ir-signing)
#   billing-service  (billing)  — verify (role billing-ir-verify)
#   interest-service (interest) — verify (role interest-ir-verify)
#
# The role names are the services' application defaults; the ServiceAccounts are the dedicated
# ones in each service's gitops manifest. NOBODY gets create/update on transit/keys/* — the key
# exists only because this stack declares it, so it cannot be rotated, exported or deleted by a
# workload.
#
# The `transit` mount itself is deliberately NOT managed here: it may already exist (other
# Transit keys live on it), and a tofu-managed mount would either fail to create or, worse, be
# destroyed with this stack. The apply procedure enables it idempotently first (runbook 0027).

locals {
  transit_mount        = "transit"
  product_ir_key       = "product-ir-publish"
  kubernetes_auth_path = "kubernetes"
}

resource "vault_transit_secret_backend_key" "product_ir_publish" {
  backend                = local.transit_mount
  name                   = local.product_ir_key
  type                   = "ed25519"
  exportable             = false
  allow_plaintext_backup = false
  deletion_allowed       = false
}

resource "vault_policy" "product_ir_sign" {
  name   = "product-ir-sign"
  policy = file("${path.module}/policies/product-ir-sign.hcl")
}

resource "vault_policy" "product_ir_verify" {
  name   = "product-ir-verify"
  policy = file("${path.module}/policies/product-ir-verify.hcl")
}

resource "vault_kubernetes_auth_backend_role" "product_catalog_ir_signing" {
  backend                          = local.kubernetes_auth_path
  role_name                        = "product-catalog-ir-signing"
  bound_service_account_names      = ["product-catalog"]
  bound_service_account_namespaces = ["accounts"]
  token_policies                   = [vault_policy.product_ir_sign.name]
  token_ttl                        = 600
  token_max_ttl                    = 600
}

resource "vault_kubernetes_auth_backend_role" "billing_ir_verify" {
  backend                          = local.kubernetes_auth_path
  role_name                        = "billing-ir-verify"
  bound_service_account_names      = ["billing-service"]
  bound_service_account_namespaces = ["billing"]
  token_policies                   = [vault_policy.product_ir_verify.name]
  token_ttl                        = 600
  token_max_ttl                    = 600
}

resource "vault_kubernetes_auth_backend_role" "interest_ir_verify" {
  backend                          = local.kubernetes_auth_path
  role_name                        = "interest-ir-verify"
  bound_service_account_names      = ["interest-service"]
  bound_service_account_namespaces = ["interest"]
  token_policies                   = [vault_policy.product_ir_verify.name]
  token_ttl                        = 600
  token_max_ttl                    = 600
}
