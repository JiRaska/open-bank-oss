# openbao-breakglass — root-equivalent access through the operator's Keycloak SSO, so that
# bootstrap-class changes do not depend on a long-lived root token (runbook 0027).
# Guarded by the OIDC role: one Keycloak subject, MFA (acr=mfa) on both sides, 15-minute token.
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
