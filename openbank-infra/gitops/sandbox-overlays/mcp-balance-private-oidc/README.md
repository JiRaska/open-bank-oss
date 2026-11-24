# Optional private Balance OIDC discovery

Include this Kustomize component after `mcp-bound-balance` when the sandbox has the private OIDC
backchannel. Omitting it retains the existing Balance behavior and needs no discovery CA Secret.

Set `discovery-url` in the site-owned `balances/balance-mcp-read-origins` ConfigMap to the exact
private HTTPS customers-realm URL. Keep `issuer-url` as the canonical public customers realm the
gateway and Keycloak advertise. Balance uses that canonical value for both Quarkus JWT validation
and the existing application identity check. The staff OIDC client keeps its existing configuration.

Project only `ca.crt` from the site-owned `balances/balance-mcp-oidc-trust` Secret. The CA must
verify the backchannel listener certificate, whose SAN must match the discovery URL. The named
Quarkus bucket retains hostname verification. It has no client private key and is separate from
the Balance inbound MCP client trust and outbound Delegation server trust.

Before activating, verify that the Keycloak backchannel metadata gives the canonical `issuer` and
a reachable private `jwks_uri` under the same TLS origin. The site backchannel policy should select
`app.kubernetes.io/name: balance-service` in `balances` as the caller and the Keycloak workload in
`mcp-synthetic-sandbox` as the destination. Keep these ingress and egress rules in the Keycloak
backchannel overlay. Neither component sets MCP activation flags.
