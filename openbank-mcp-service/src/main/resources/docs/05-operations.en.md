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

## Customer channel admission (ADR-0385)

The customer MCP channel is admitted only when `openbank.mcp.customer.enabled` is on and an
allowlisted client presents a customer-realm token bound to a DPoP proof. Discovery follows
RFC 9728: `GET /.well-known/oauth-protected-resource` and its path-suffixed form
`/.well-known/oauth-protected-resource/mcp` answer the resource metadata (issuer,
`authorization_servers`, DPoP signing algorithms) built from the allowlist and the configured
issuer only, never from request headers. While the channel is off both endpoints answer 404 —
nothing advertises a channel that does not admit anyone.

## Forwarded headers behind the edge (#16847)

Behind the edge two things need the external view of a request: the customers OIDC tenant's own
DPoP check compares the proof's `htu` with the request URL, and the tokenless audit window keys by
client address. Quarkus applies `X-Forwarded-Proto`/`-Host`/`-For` when `MCP_PROXY_FORWARDING=true`
(default `false`), and ONLY from peers listed in `QUARKUS_HTTP_PROXY_TRUSTED_PROXIES`.

- **Boot gate.** `ProxyForwardingGuard` is `@Startup`: forwarding on with no trusted-proxy list, or
  with a trust-all entry (`0.0.0.0/0`, `::/0`), stops the service. An unset list would make Quarkus
  trust forwarded headers from any peer.
- **Untrusted peers.** Their forwarded headers are ignored: the request keeps its direct URL, so a
  proof bound to the published resource fails the tenant's `htu` check with 401.
- **Audit key.** `ClientAddressResolver` never uses Quarkus' forwarded address: Quarkus takes the
  LEFTMOST `X-Forwarded-For` entry, which a client controls whenever the edge appends. The key is
  the raw TCP peer, or the ONE edge-set header named by `MCP_EDGE_CLIENT_ADDRESS_HEADER` (e.g.
  `X-Envoy-External-Address`) when the peer is in the trusted list and the value is a single IP.
  `ClientAddressKey` hashes IPv4 /32 or the IPv6 /64 prefix; a hostname keys as `ip:unknown`.
- **Deployed state: forwarding OFF.** No route to mcp-service exists. The route PR must, together:
  narrow `QUARKUS_HTTP_PROXY_TRUSTED_PROXIES` to the gateway's source (never the pod CIDR); make the
  edge authoritative (Envoy `ClientTrafficPolicy` client IP detection, client-supplied
  `X-Forwarded-*` overwritten); set `MCP_EDGE_CLIENT_ADDRESS_HEADER`; and admit only the gateway and
  known callers to `:8150` in the NetworkPolicy.
- **Symptom of a wrong list:** every real customer call refused 401 `invalid_dpop_proof` at the
  tenant (forwarding not applied), or every tokenless caller sharing one audit key (the gateway's).
