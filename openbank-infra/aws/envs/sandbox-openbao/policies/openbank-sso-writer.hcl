# openbank-sso-writer — the narrow write grant for an operator's Keycloak SSO login.
#
# Exactly the KV entries an operator provisions by hand:
#   - the two realm-import DR artifacts (runbook 0009),
#   - per-service Keycloak M2M client secrets (runbook 0009 batches): keycloak/<service>,
#   - the delegation disclosure client secret (#9237): delegation-disclosure-service.
# No delete, no destroy, no metadata write, no sys/*, no auth/*, no policy edits.

path "openbank/data/keycloak-realm-import" {
  capabilities = ["create", "update", "read"]
}

path "openbank/data/keycloak-customers-realm-import" {
  capabilities = ["create", "update", "read"]
}

path "openbank/data/keycloak/*" {
  capabilities = ["create", "update", "read"]
}

path "openbank/data/delegation-disclosure-service" {
  capabilities = ["create", "update", "read"]
}
