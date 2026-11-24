# Customer MCP channel — go-live (ADR-0383 / ADR-0384 / ADR-0385)

Tracking: #16590 (delivery), #16605 (observability), #16847 (route prerequisites).
Alerts and their responses: [`mcp-customer-channel.md`](mcp-customer-channel.md).
Service runbook: [`svc-mcp.md`](svc-mcp.md).

The route to `openbank-mcp-service` for customers lives in
`openbank-infra/gitops/components/mcp-customer-route/` and is **off by construction**: no ArgoCD
Application points at that directory, and the Gateway declares no `https-mcp` listener, so the
route can never be Accepted. Nothing in this runbook is done by merging a PR that only edits that
directory. Each step below is its own PR, merged and synced before the next one starts.

## 0. Preconditions — every box, or stop

No step in section 1 starts until all of these are true **on `origin/main`** and, where stated, in
the sandbox. Check each against the code, not against this list.

### Delivery (#16590)

- [ ] D1 customers-realm tenant selected by `iss`, allowlisted clients only, PKCE S256, exact
      redirect URIs — admission tiers per ADR-0385 (#16664, #16672).
- [ ] D2 DPoP-bound access tokens (5 min, `aud` = mcp) and rotating DPoP-bound refresh with reuse
      detection; connected-clients view and revoke in the app.
- [ ] D3 grant validated against customer + client + `cnf.jkt`; reads only under an active
      `MCP_CLIENT` grant (#16800).
- [ ] D4 customer-scoped downstream tokens; no shared `openbank-services` principal on this channel.
- [ ] D5 throttling before policy: edge per-IP + body cap (this route), shared fail-closed replay
      store and budgets (#16791), concurrency caps, load shedding.
- [ ] D6 proposal store + in-app approval with full SCA and dynamic linking, no exemptions, expiry,
      no bulk approval (#16853, #16845, #16864, #16849); the PDP veto on MCP-client tokens for
      `customer.mcp-proposals.*` is on main (#16915).
- [ ] Caller-scoped opaque account handle instead of raw account ids in tool results (#16863).
- [ ] Untrusted free text sanitised in tool results (#16637).
- [ ] Gate `mcp-customer-channel-propose-only` enforced (it is, on main).
- [ ] Threat model `docs/threat-models/openbank-mcp-service.md` section "Customer channel — route
      exposure" has no row marked **OPEN — blocks go-live**.

### Route prerequisites (#16847)

- [ ] Subject-less audit window keyed by IPv6 /64 and IPv4 /32 (#16919).
- [ ] The mTLS edge hop on `main` (#17004): mcp-service `:8443` (TLS 1.3, client auth REQUIRED,
      Certificate `mcp-service-internal-tls`), `MCP_EDGE_GATEWAY_IDENTITY` pinned to the SAN of
      `envoy-gateway/edge-backend-client-tls.yaml`, and `EnvoyProxy/edge spec.backendTLS.clientCertificateRef`
      in `envoy-gateway/gateway.yaml`. Both Certificates `Ready=True` in the cluster.
- [ ] Issuance of the pinned SAN constrained (see "Open decision" below).

### Observability (#16605, ADR-0384) — no route opens until abuse can be seen

- [ ] Connection lifecycle events from delegation-service for `MCP_CLIENT` grants (today they are
      refused at construction: `DelegationEvents.kt`, "MCP_CLIENT grants are dormant").
- [ ] One bounded tool-call event per call from mcp-service, separate from the audit trail.
- [ ] Proposal funnel events and metrics (created / approved / rejected / expired, time to decision).
- [ ] Active-connection and connected-client gauges from a delegation-service projection.
- [ ] SLOs as code for availability and p95 per read tool.
- [ ] Alerts: proposal-rejection ratio and zero-traffic liveness, next to the three on main
      (`prometheus-rules-mcp-customer.yaml`).
- [ ] Admin page: per-client kill switch, connection search and revoke (today the page says these
      are not built: `openbank-admin-ui/src/app/delegations/mcp-channel/page.tsx`).
- [ ] Warehouse silver and gold marts for connections, tool calls and proposals, party pseudonymised.
- [ ] Fraud-service signals for the channel, in shadow.
- [ ] A synthetic customer (ADR-0252) driving the channel appears in every view above.

### Availability

- [ ] `mcp-service` survives losing a pod: >= 2 replicas, a PodDisruptionBudget and a hostname
      spread. Today it is single-replica by constraint (per-pod in-memory state), and the three
      `public-workload-ha` findings for it are baselined in `.github/public-workload-ha-baseline.txt`
      only because the route is off. Delete those three lines in the same PR that fixes them.

## Open decision — who can mint the pinned client SAN

The edge hop is trusted by IDENTITY, not by IP (owner decision 2026-11-21, #16847, #17004): no
`quarkus.http.proxy.*` forwarding and no trusted-proxy list exist, and the edge client-address
header is honoured only from a client certificate whose DNS SAN equals `MCP_EDGE_GATEWAY_IDENTITY`.
`openbank-ca` is a ClusterIssuer and the repo carries no cert-manager approver policy, so any
namespace that can create a `Certificate` can obtain that SAN. The header only feeds the
subject-less audit / throttle window key, never authentication, but constrain issuance (an
approver policy, or a dedicated issuer for the edge client certificate) and record it in the
threat model (row C-S4) before step 3.

### CA bundle for the BackendTLSPolicy

There is no trust-manager in the cluster and no CA ConfigMap anywhere in gitops; every in-cluster
TLS client here reads `ca.crt` from its cert-manager Secret. `mcp-customer-backend` therefore
names the Secret `mcp-service-internal-tls` (Envoy Gateway accepts `kind: Secret` in
`caCertificateRefs`). Before step 5 confirm the policy reports `Accepted=True` and
`ResolvedRefs=True` (`kubectl -n platform get backendtlspolicy mcp-customer-backend -o yaml`). If
the installed Envoy Gateway rejects the Secret kind, the owner step is a ConfigMap
`openbank-ca-bundle` in `platform` with key `ca.crt` (the public CA certificate only, copied from
that Secret) and the ref switched to `kind: ConfigMap`.

## 1. Go-live steps — one PR each, in order

1. **Certificate and grant.** Add an ArgoCD Application `mcp-customer-route` in
   `openbank-infra/gitops/apps/` pointing at `components/mcp-customer-route`. Its
   `kustomization.yaml` lists only `grants.yaml` (the Certificate and the ReferenceGrant). Wait
   for `mcp-edge-tls` to be `Ready=True`.
2. **Listener.** Add `https-mcp` to `envoy-gateway/gateway.yaml` (hostname `mcp.open-bank.tech`,
   Terminate with `mcp-edge-tls` from namespace `platform`, `allowedRoutes` selecting only
   `kubernetes.io/metadata.name: platform`). A separate sync from step 1: switching a listener in
   the same sync as its grant left langfuse's listener `RefNotPermitted` (2026-10-03).
3. **Edge hop check (no change, verify only).** No forwarding switch exists any more. Confirm the
   rendered NetworkPolicy `mcp-service-ingress-allow-list` admits `envoy-gateway-system` on
   `:8443` only (generated from this route's backendRef: `openbank-infra/scripts/gen-network-policies.py`),
   and that mcp-service logs no `ProxyForwardingGuard` refusal after the #17004 rollout. A rollout
   that never becomes Ready is the guard failing safe.
4. **Channel on.** `MCP_CUSTOMER_ENABLED=true`, `MCP_CUSTOMER_ISSUER`, and the real client
   allowlist (`MCP_CUSTOMER_ALLOWLIST`). Boot refuses a missing issuer or a malformed allowlist.
   Reconcile the running Keycloak customers realm from the generated template first. Before opening
   the route, verify that the registered resource and the deployed allowlist both equal
   `https://mcp.open-bank.tech/customer/mcp`.
5. **Route.** Add `httproute.yaml` to that `kustomization.yaml` (it carries the HTTPRoute, the
   `BackendTLSPolicy` `mcp-customer-backend`, and the edge policies). external-dns publishes
   `mcp.open-bank.tech` once the route is Accepted.
6. **Verify by effect, from outside the cluster** (each must give the stated answer):
   - `curl -s -o /dev/null -w '%{http_code}' https://mcp.open-bank.tech/anything` -> 404 (only
     `/customer/mcp` and the metadata paths); `POST /mcp` (staff root) -> 404 too.
   - `GET /.well-known/oauth-protected-resource/customer/mcp` -> 200, with the same canonical
     `resource` as the deployed allowlist and Keycloak registration.
   - `POST /customer/mcp` with no token -> 401 with a `DPoP` challenge carrying `resource_metadata`.
   - A body over 64 KiB -> 413 at the edge.
   - 30 requests in one second from one address -> some 429.
   - Envoy reaches mcp-service over mTLS: the route's backend shows healthy and a direct
     plain-HTTP or no-client-cert request to `mcp-service.platform.svc:8443` from another pod fails
     the handshake.
   - A request carrying forged `X-Forwarded-Host`, `X-Forwarded-For` and
     `X-Envoy-External-Address` headers: the audit row's subject-less key and the `htu` check
     behave exactly as without them (the forged values never reach the service).
   - A synthetic customer completes a read and a proposal, and the proposal appears in the app for
     SCA approval; it never executes from the MCP call.
7. **Delete the three `mcp-service` lines** from `.github/public-workload-ha-baseline.txt` once the
   availability box above is ticked.

## Rollback

Fastest first; each is enough on its own.

1. Remove the `https-mcp` listener from `envoy-gateway/gateway.yaml` (the route detaches; DNS
   record is withdrawn by external-dns).
2. Delete the `mcp-customer-route` Application (prune removes route, policies, grant, certificate).
3. `MCP_CUSTOMER_ENABLED=false` on mcp-service: customer tokens are no longer admitted and the
   metadata endpoints answer 404.

Do not disable DPoP replay protection, client-auth on `:8443`, or the forwarding guard to restore service: failing closed
is the control.
