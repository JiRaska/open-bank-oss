# openbao-config-admin — what `tofu apply` in envs/sandbox-openbao needs, and nothing more.
#
# Scoped to the NAMED objects this stack owns. No secret path (openbank/*), no auth-method
# change, no unseal/rekey/root. It may not edit its own policy (read only), so the token cannot
# grant itself more than this file says.
#
# Residual risk, stated rather than hidden: editing openbank-sso-writer is in scope, and any
# identity that can edit a policy another identity holds can widen that identity. That is
# inherent to "administer policies"; the control is that the change is a reviewed PR here and
# the token lives 15 minutes. Adding a policy or role to this stack means adding its path below.

path "sys/policies/acl" {
  capabilities = ["list"]
}

path "sys/policies/acl/openbao-config-admin" {
  capabilities = ["read"]
}

path "sys/policies/acl/openbank-sso-writer" {
  capabilities = ["create", "read", "update"]
}

path "auth/oidc/role" {
  capabilities = ["list"]
}

path "auth/oidc/role/openbao-config-admin" {
  capabilities = ["read"]
}

path "auth/oidc/role/openbank-sso-writer" {
  capabilities = ["create", "read", "update"]
}

# --- Product-IR signing (ADR-0352) ------------------------------------------------------------
# Bootstrap-class: this block widens the admin's own policy, so the FIRST apply that carries it
# needs a privileged token (runbook 0027, "Product IR signing"). Named objects only.

path "sys/policies/acl/product-ir-sign" {
  capabilities = ["create", "read", "update"]
}

path "sys/policies/acl/product-ir-verify" {
  capabilities = ["create", "read", "update"]
}

# Create + read the key and set its config (exportable/backup/deletion flags). No `delete`:
# deletion_allowed=false is the second lock, this is the first.
path "transit/keys/product-ir-publish" {
  capabilities = ["create", "read", "update"]
}

path "transit/keys/product-ir-publish/config" {
  capabilities = ["create", "update"]
}

path "auth/kubernetes/role" {
  capabilities = ["list"]
}

path "auth/kubernetes/role/product-catalog-ir-signing" {
  capabilities = ["create", "read", "update"]
}

path "auth/kubernetes/role/billing-ir-verify" {
  capabilities = ["create", "read", "update"]
}

path "auth/kubernetes/role/interest-ir-verify" {
  capabilities = ["create", "read", "update"]
}
