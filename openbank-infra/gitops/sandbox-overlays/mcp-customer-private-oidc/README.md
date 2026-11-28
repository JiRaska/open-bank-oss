# Optional private MCP customer OIDC

Compose this component after `mcp-bound-balance` when the private customers-realm backchannel is
ready. Provide a site-owned `platform/mcp-customer-oidc-origins` Secret with the exact private
HTTPS realm URL in `discovery-url` and a `platform/mcp-customer-oidc-trust` Secret containing the
backchannel server CA chain in `ca.crt`. The listener certificate SAN must match the URL.

The existing `MCP_CUSTOMER_OIDC_URL` configures both the Quarkus `customers` tenant and the
customers-realm token-exchange REST client. The tenant uses the named trust for discovery and JWKS,
and for UserInfo if enabled in that tenant. MCP has no separate UserInfo REST client. The
token-exchange REST client uses the same CA with its ordinary OAuth credentials.

The dedicated MCP bound-read clients retain their existing mTLS bucket and client certificate.
The application’s `MCP_CUSTOMER_ISSUER` still supplies the canonical token issuer and public
metadata. The staff tenant, existing public route, and activation flags are unchanged.

Before activation, verify that the private discovery metadata contains the canonical issuer and
reachable private JWKS, UserInfo, and token endpoint URLs where applicable. The site backchannel
policies must permit the exact MCP pod in `platform` to reach the Keycloak workload.

Provision the matching OpenBao properties under the `mcp-synthetic-sandbox/` path
listed in `site-inputs.yaml`. Verify ExternalSecret readiness before enabling workloads.
