# openbao-breakglass — root-equivalent access through the operator's Keycloak SSO, so that
# bootstrap-class changes do not depend on a long-lived root token (runbook 0027).
# Guarded by the OIDC role: one Keycloak subject, MFA (acr=mfa) on both sides, 15-minute token.
path "*" {
  capabilities = ["create", "read", "update", "patch", "delete", "list", "sudo"]
}
