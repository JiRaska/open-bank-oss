# Private sandbox TLS for MCP balance reads

Compose this Kustomize component with the site owner’s existing Balance Rollout, stable and canary
Services, Delegation Deployment and Service, and MCP Deployment. The component is unreferenced by
shared applications. Retain the existing workload owners when incorporating the patches.

The component adds HTTPS 8443 to the existing private Balance and Delegation Services. The HTTP
service ports, probes, Sidecars, and rollout selectors continue using their existing configuration.
Balance requests a client certificate in the production image and the read endpoint independently
requires a verified bearer token bound to that certificate. Delegation authenticates TLS with its
server certificate and authorizes the dedicated Balance reader through its existing staff OIDC
service account. The HTTPS path has no Gateway or Ingress resource.

## Site inputs

Supply the following resources from the private site configuration and PKI before composing the
component. The PEM mounts are required for pods to start once the component is included.

| Namespace | Secret | Keys | Identity or trust |
| --- | --- | --- | --- |
| balances | `balance-mcp-server-tls` | `tls.crt`, `tls.key` | Server authentication and exact DNS SAN of the Balance HTTPS origin. |
| balances | `balance-mcp-client-trust` | `ca.crt` | CA chain authorized to issue the MCP outbound client leaf. |
| balances | `balance-mcp-delegation-trust` | `ca.crt` | Delegation server CA chain. |
| delegation | `delegation-mcp-server-tls` | `tls.crt`, `tls.key` | Server authentication and exact DNS SAN of the Delegation HTTPS origin. |
| platform | `mcp-bound-read-client-tls` | `tls.crt`, `tls.key` | Client authentication and URI SAN `spiffe://openbank/mcp-read`. The same MCP leaf calls the Keycloak issuer and reads Balance. |
| platform | `mcp-bound-read-server-trust` | `ca.crt` | PEM bundle of the approved Keycloak and Balance private server CA chains. |
| balances | `balance-mcp-reader-oidc` | `BALANCE_MCP_READER_SECRET` | Credential for the staff client `openbank-balance-mcp-reader` with its exact expected account and role. |

The client leaf issuer trust, native server issuer trust, and Gateway client trust have distinct
purposes. Project only CA certificates into trust mounts. Serve complete leaf/intermediate chains
through `tls.crt` and rotate certificate pairs together.

The `balance-mcp-read-origins` ConfigMap in `balances` supplies `delegation-url` for the private HTTPS
service, `issuer-url` for the canonical customers realm, and `staff-oidc-url` for Balance client
credentials. Balance must reach the canonical customers realm and trust its discovery and JWKS
endpoint through its OIDC trust chain, or use the [private discovery component](../mcp-balance-private-oidc). The `mcp-bound-read-origins` ConfigMap in `platform` supplies
the private `balance-url` and `issuer-url` HTTPS origins. Use the exact server certificate SAN
names. Preserve the canonical OIDC issuer even when the native private issuer has a separate URL.

The supplied 8443 ingress policies add the exact source workload for the two hops; site egress must
admit those destinations. The base derived policies retain their existing same-namespace rules.
Add the Keycloak-to-Delegation 8443 edge with the Keycloak overlay if that consumer uses this
listener. Existing HTTP callers continue using their declared destinations.

The component leaves admission and connection binding disabled. A separate activation change can
follow a synthetic handshake and application check that verifies both private destinations, staff
OIDC token audience, exact certificate binding, and legacy HTTP reachability.
