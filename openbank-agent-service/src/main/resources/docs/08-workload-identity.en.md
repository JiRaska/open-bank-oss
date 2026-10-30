# Workload identity — openbank-agent-service

ADR-0177 Phase 1 pilot. The deployment runs as ServiceAccount `openbank-agent`
(namespace `platform`) and mounts a projected ServiceAccount token at
`/var/run/secrets/tokens/sa-token` — a JWT with `aud: keycloak`, ~1h TTL, rotated
by the kubelet. Keycloak's realm (`openbank`) trusts the cluster's Kubernetes OIDC
issuer as identity provider alias `kubernetes`, and the `openbank-agent` client
carries the `standard.token.exchange.enabled` attribute plus a `token-exchange`
scope permission granted to itself, so the SA token can be exchanged for an
`openbank-agent` access token per RFC 8693.

## Status: wiring only, not yet consumed

Nothing in the service code reads the token file yet. Outbound MCP tool calls
still authenticate with the static `OIDC_M2M_CLIENT_SECRET` client-credentials
grant (named oidc-client `m2m`), and that secret path remains the **fallback**
until the live exchange is verified end-to-end (owner-gated, see the ADR-0177
Phase 1 delivery note) and a follow-up change switches the client to prefer the
exchanged token behind a flag.

## Why the fallback stays

The token-exchange path depends on live Keycloak state the repo cannot apply by
itself: the realm import runs on Keycloak cold start only, and the running realm
needs the IdP and permission applied out-of-band. Until that is owner-verified in
the non-prod realm, removing the secret path would turn a configuration gap into
an auth outage — the exact failure mode ADR-0177's phased rollout exists to
prevent.
