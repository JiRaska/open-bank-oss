# openbao-breakglass — root-equivalent access through the operator's Keycloak SSO, so that
# bootstrap-class changes do not depend on a long-lived root token (runbook 0027).
# Guarded by the OIDC role: one Keycloak subject, MFA (acr=mfa) on both sides, 15-minute token.
# The role and this policy are themselves denied below, so a break-glass token cannot loosen its
# own guards; changing them needs the root token.
path "*" {
  capabilities = ["create", "read", "update", "patch", "delete", "list", "sudo"]
}

# Not needed for break-glass configuration work, and too dangerous to hand to any SSO login:
# raw storage access bypasses every policy, and sealing takes the whole platform down.
path "sys/raw" {
  capabilities = ["deny"]
}
path "sys/raw/*" {
  capabilities = ["deny"]
}
path "sys/seal" {
  capabilities = ["deny"]
}
path "sys/step-down" {
  capabilities = ["deny"]
}

# Self-protection: no rewriting of this role, this policy or the OIDC mount config.
path "auth/oidc/role/openbao-breakglass" {
  capabilities = ["deny"]
}
path "sys/policies/acl/openbao-breakglass" {
  capabilities = ["deny"]
}
path "auth/oidc/config" {
  capabilities = ["deny"]
}

# Same effect as raw storage: a forced snapshot restore rewrites everything; audit devices are
# the record of what break-glass did.
path "sys/storage/raft/snapshot-force" {
  capabilities = ["deny"]
}
path "sys/audit" {
  capabilities = ["deny"]
}
path "sys/audit/*" {
  capabilities = ["deny"]
}
