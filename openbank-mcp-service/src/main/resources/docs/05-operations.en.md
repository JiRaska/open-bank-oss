# openbank-mcp-service — operations

## Shared build dependencies

This service's `build.gradle.kts` does not list the dependencies every Quarkus service shares: the Quarkus BOM, quarkus-kotlin, RESTEasy Reactive with Jackson, SmallRye Health and OpenAPI, OIDC, the Micrometer Prometheus registry and config-yaml, plus the quarkus-junit5, AssertJ and REST Assured test libraries. The `openbank.quarkus-service` convention plugin in `build-logic` declares them. The resolved classpath is unchanged. `build.gradle.kts` lists only what this service adds.

## account-service client

The read ports call account-service through the client generated from its `openapi.yaml` (`openbank-client-account`, ADR-0319, #13169) instead of a hand-written REST client. `AccountDirectory` reads both lookups (`GET /api/v1/accounts/iban/{iban}` and `GET /api/v1/accounts/{accountId}`, each `@Authorize(account.read)` downstream) through the shared `AccountClient` facade. A 404 still raises a `NotFoundException`, so the tool call is denied and never answered with an empty account; any other status or a failed call is thrown unchanged. Reads stay blocking, as the previous client was. An account answer without an `id` (IBAN lookup) or without an `accountNumber` (debtor lookup for a domestic payment) is treated as malformed and fails the call.

## Test profile configuration

`application.yaml` has a `%test` section that turns off the OIDC resource server (`quarkus.oidc.tenant-enabled`) and the outbound `oidc-client`, because `@QuarkusTest` classes run without Keycloak. It applies only under the test profile and does not change deployed behaviour.

## Business ownership advertised by tools/list

The tool `service` identity is unchanged. Its `domain` is the canonical English display
label from the taxonomy and service governance declarations packaged with this image
(ADR-0364), rather than an independently maintained MCP category. Account, balance and
statement reads therefore report **Accounts & deposits**; transaction reads report
**Financial accounting**, and consent reads report **Open banking**. The local proposal
orchestration belongs to **Channels**; this label does not make the proposal executable.

Payment confirmation spans the SEPA and domestic rails. It retains a null single-service
identity and reports **Payments** only while both source owners agree. Missing, unknown
or conflicting owners report **Unclassified**. The canonical taxonomy is a required build
resource; an image missing it is invalid and must not be promoted.

Both MCP registries copy the same authoritative ownership inputs during resource
processing. CI and deployment equivalence include those inputs, so a governance change
rebuilds each reader. Inspect the reviewed source and packaged `business-domains/`
resources when investigating metadata drift; do not replace the image metadata with
another hand-written domain map. Redeploy or roll back through the normal signed-image
and GitOps workflow. No database migration, consent grant, event replay, capability
change or payment execution is part of this ownership correction.

## Conformance and fuzz test layers

The module carries two test layers for the MCP surface (issue #16617). Protocol
conformance (`McpSpecConformanceTest` in process, `McpHttpConformanceIT` over HTTP) checks
the JSON-RPC envelope, `initialize`, `tools/list` and tool error semantics against the MCP
specification. Fuzzing (`McpToolCallFuzzTest`, `McpDownstreamFuzzTest` and the wire-level
`McpToolCallWireFuzzIT`) replays a fixed hostile corpus over every `tools/call` argument:
NUL, lone surrogates, megabyte strings, deep nesting, wrong JSON types, huge numbers and
IBAN-shaped garbage. The invariants are no HTTP 500, no stack trace or internal class name,
a bounded response, and no downstream call for invalid input.

The test JVM heap is set to 2g in the module build because the corpus sends 1 MiB and
11 MiB bodies; the default worker heap runs out of memory inside the HTTP test client. This
affects test execution only, not the service image.

Known deviations from the specification are kept as named disabled tests rather than fixed
here. Input hardening (NUL handling, reflected input, argument bounds) is tracked in #16628;
specification revision 2026-07-28 and the HTTP authorization behaviour are tracked in #16629.

## Token audience on /mcp

`/mcp` accepts a bearer token only when its `aud` claim contains `openbank-mcp-service` (RFC 8707, ADR-0383 D1, #16622). A token issued for any other resource, or one with no audience, is refused with HTTP 401 and `WWW-Authenticate: Bearer error="invalid_token"` on every JSON-RPC method, before tools are listed or called. A refused `tools/call` is still audited as DENIED with reason `token audience mismatch`, and the `caller_identity` meter counts it as a resolution failure. A call with no token at all is unchanged: it is answered without tools, as before.

The check lives in `McpEndpoint` and again in `CallerContextResolver`, not in `quarkus.oidc.token.audience`. That setting applies to the whole tenant, and `/api/v1/mcp/sessions` is called with the operator's own token, whose audience is not this server.

The only caller in the tree today is admin-ui's OBO route, which exchanges for `audience=openbank-mcp-service` and so already carries it. No realm client mints `agent:` tokens yet; an issuer that starts to must add an audience mapper for `openbank-mcp-service`, or its tokens will get 401.

If the OIDC issuer cannot be reached, Quarkus OIDC fails a presented token with HTTP 500 rather than 401. That is fail-closed (nothing reaches the endpoint) and is left as is here.

## Throttling and request bounds (ADR-0383)

`McpRateLimiter` runs BEFORE the policy decision, so a flood of calls that would be denied anyway costs no PDP round trip.

- **Order for `tools/call`:** resolve the caller, spend the rate window, take an in-flight slot, then the PDP gate and the tool. A refusal returns a throttled tool error; the slot is released on every exit path, including a PDP or tool failure.
- **Resolved callers** are keyed by caller identity: a per-minute and a per-day window (`openbank.mcp.rate-limit.calls-per-minute`, default 60; `calls-per-day`, default 1000) plus a concurrent in-flight cap (`max-in-flight`, default 4).
- **Anonymous callers** (no resolvable identity) share ONE small bucket (`anonymous-calls-per-minute`, default 10), spent before any audit row or identity meter is written, so an unauthenticated flood is bounded too.
- **`tools/list`** goes through the same limiter before any PDP call (resolved caller: its own window; otherwise the anonymous bucket). A refusal is a JSON-RPC error with code -32029.
- **Observability:** every throttled request increments `openbank.mcp.throttled{surface,caller}` (and the tool-call meter as a DENY), but the audit trail gets at most one row per caller, or per anonymous bucket, per minute window. During a flood the meters therefore exceed the audit row count by design.
- **Request body cap:** `quarkus.http.limits.max-body-size` (`MCP_MAX_BODY_SIZE`, default 64K) rejects oversize bodies with HTTP 413 before parsing. It is service-wide.
- **Config keys** (env override in parentheses): `openbank.mcp.rate-limit.enabled` (`MCP_RATE_LIMIT_ENABLED`), `calls-per-minute` (`MCP_RATE_LIMIT_PER_MINUTE`), `calls-per-day` (`MCP_RATE_LIMIT_PER_DAY`), `anonymous-calls-per-minute` (`MCP_RATE_LIMIT_ANON_PER_MINUTE`), `max-in-flight` (`MCP_RATE_LIMIT_MAX_IN_FLIGHT`).
- **State is in-process** (replicas: 1), so the caps multiply by the replica count and reset on a pod roll until the shared-store slice lands.

## MCP client allowlist (ADR-0385 D1)

`src/main/resources/mcp-client-allowlist.yaml` is the one declaration of which third-party MCP clients may connect, and in which tier. The tier is assigned by how the client registers, never by product name, and never from a token claim or client-supplied metadata.

- **Tier H (hosted):** a confidential client with a fixed HTTPS redirect and client authentication at the token and refresh endpoints. The default is `private_key_jwt`, whose key set is committed under `mcp-clients/` and pinned by its sha256 in the declaration. Keycloak receives it inline, so it never fetches keys. A client secret is allowed only under a recorded `secret_exception` of at most six months, and it fails CI after its `expires` date.
- **Tier L (local):** a public client with loopback redirects (`http://127.0.0.1/…`, `http://[::1]/…`, any port; `localhost` is not accepted). Its tokens must be DPoP-bound.

The declaration currently drives the customers-realm client config. `.github/scripts/gen-mcp-realm-clients.py` writes each client into `customers-realm-template.json`. Every client gets PAR, PKCE S256, exact redirects, no direct, implicit, device or token-exchange grant, `offline_access` as its only role, the `openbank-mcp-service` audience, and the cookie-free fresh-passkey browser flow. The `mcp-client-realm-parity` gate fails CI on any mismatch between the declaration and the realm.

While the customer channel is on, this service also reads the file at boot and admits by `azp` against it (see *Customer channel admission* below). Every declared client stays `enabled: false` in line with ADR-0383's exposure order.

## Customer channel admission (ADR-0385)

The customer MCP channel is admitted only when `openbank.mcp.customer.enabled` is on and an
allowlisted client presents a customer-realm token bound to a DPoP proof. Discovery follows
RFC 9728: `GET /.well-known/oauth-protected-resource` and its path-suffixed form
`/.well-known/oauth-protected-resource/mcp` answer the resource metadata (issuer,
`authorization_servers`, DPoP signing algorithms) built from the allowlist and the configured
issuer only, never from request headers. While the channel is off both endpoints answer 404 —
nothing advertises a channel that does not admit anyone.

## Customer reads: grant lookup and token exchange (ADR-0383 D3/D4)

With the customer channel on, an admitted customer sees only the read tools its active `MCP_CLIENT` grants allow: `list_accounts` (accounts shared with this client), `get_balance` (`ACCOUNT_READ_BALANCES`) and `list_transactions` (`ACCOUNT_READ_TRANSACTIONS`). There are no write tools on this channel.

- **Grant lookup.** On every `tools/list` and `tools/call`, mcp-service asks delegation-service `POST /api/v1/delegations/mcp-client/resolve` for the grants of the triple (the token's `party_id`, its `azp`, its `cnf.jkt`), on its own `m2m` identity. Only positive answers are cached, for `mcp.customer.grant-cache-ttl` (`MCP_CUSTOMER_GRANT_CACHE_TTL`, default `PT15S`, at most `PT30S`), so a revoked grant stops working within that bound. A lookup failure is a refusal (`Authorization unavailable`), never a stale or empty answer reused.
- **Token exchange.** Each read exchanges the customer's own token at the customers realm (RFC 8693) for a token with one audience (`openbank-balance-service` or `openbank-transaction-service`) and one read scope, living at most 60 s. The requester client is `mcp.customer.exchange.client-id` (default `openbank-mcp-service`); its secret is read only from `MCP_CUSTOMER_EXCHANGE_CLIENT_SECRET` (OpenBao `keycloak/mcp-service-customer-exchange`). With no secret set, every customer read is refused. The realm side (requester, audience clients, scopes) is derived from the allowlist's `exchange:` block by `gen-mcp-realm-clients.py` and held to it by the `mcp-client-realm-parity` gate.
- **Audit.** Every customer call is audited under the customer's subject; an allowed read records `consent_id = delegation:<grantId>`. A downstream 401/403 drops the cached grant.
- **Not yet working end to end:** the downstream services accept only the staff realm today, so exchanged customer tokens are refused there until ADR-0383 P5 lands. Every failure on this path is a refusal.
