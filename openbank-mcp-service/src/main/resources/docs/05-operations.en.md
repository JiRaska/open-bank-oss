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

## Tool-result sanitisation (ADR-0383 D7)

Every tool result passes three steps in `McpToolRegistry.call`, in this order: `McpPiiMasker`
(field-name and whole-value masking), `McpToolResultSanitiser` (D7), then `McpUntrustedData.wrap`.
The sanitiser walks every string value and every object key in the result. A key the sanitiser had
to change is dropped when its cleaned form names another entry, so it can never shadow one. It does not use a list
of free-text field names, so a field a new tool adds is covered without an edit here. For each
string it:

- removes invisible code points and cuts the input to 560 surviving code points (4 x the cap) in one
  linear pass, so padding cannot push a payload past the bound, and every pattern is linear;
- applies Unicode NFKC normalisation;
- removes control, format (zero-width, bidi, word joiner, BOM), Unicode tag, private-use and
  line/paragraph separator code points (a line break becomes a space);
- decodes percent-encoded ASCII and HTML entities to a fixpoint (at most 3 rounds), then repeats
  the step above;
- replaces markdown image and link syntax, reference definitions, HTML tags, autolinks, URLs
  (scheme, `www.` and protocol-relative `//host`) and `javascript:`/`data:`-style schemes with a
  placeholder, and again on the text with combining marks removed when markup only shows there;
- masks IBANs inside free text (country code with its ISO 13616 registry length, any case,
  separated by space, dot, hyphen or slash) and phone numbers (3-3-3 or 3-2-2-2, optional
  `+CC`/`00CC`, separated by space, dot or hyphen). When the input was cut, a trailing IBAN fragment
  is masked too. Masking is skipped only for an allow-listed system id field (`id`, `accountId`,
  `paymentId`, `consentId`, `transactionId`, `statementId`, `proposalId`, `partyId`, `fromAccountId`,
  `debtorAccountId`, `sessionId`) whose value is a UUID, and for an amount field whose value is a
  plain decimal. A payer-controlled field such as `endToEndId` is never exempt;
- neutralises look-alikes of the untrusted-data markers, matched on a skeleton (NFD, combining marks
  dropped, case folded, Cyrillic/Greek/Armenian/Cherokee/Lisu/small-capital confusables mapped to
  Latin, any run of non-letters between the words). The confusables table is vendored because
  ICU4J is not on the runtime classpath; a look-alike outside it is a known residual;
- caps the result at 140 characters (the SEPA unstructured remittance maximum) and ends a cut
  value with `…`.

IBANs are checked by country and length, not by the ISO 7064 mod-97 checksum. An RF creditor
reference passes mod-97 and is not an account, and a mistyped IBAN fails it and still identifies
one. `McpPiiMasker` uses the same check for whole values, so it no longer masks an RF reference.

A `logoUrl` field is the only URL kept. It survives only as `https` on a host in
`McpToolResultSanitiser.CDN_HOSTS`, with no query, fragment or userinfo. Any other value removes the
field. There is no configuration toggle.

The red-team pack (`src/test/resources/redteam/`) measures these properties, and `known-gaps.json`
lists what is still open. Today that is one item: a natural-person name inside free text, which has
no shape to match on.

## Account handles for tool chaining (#16678)

Tool results mask every IBAN to its last four characters (ADR-0383 D7), so a model cannot pass an
IBAN from `list_accounts` back as `accountId`. Every result object with an `iban` therefore also
carries `accountRef`: `acr_` followed by an unpadded base64url HMAC-SHA256 over the caller scope
(`agentId`, `consentId`, session `sid`) and the IBAN. The ref is attached after the masker and
sanitiser have run, so it needs no sanitiser exemption, and an upstream field named `accountRef`
is sanitised like any other (a ref-shaped string can carry an IBAN after a `-`).

A caller whose token has no `sid` gets no `accountRef` at all, and every ref it presents is refused.
Without a session the scope would be one agent under one consent, shared by all its sessions, so
the service fails closed rather than issue a weaker handle. Such a caller can still pass the full
IBAN as `accountId`. Each withheld or refused handle increments
`openbank_mcp_account_ref_unavailable_total{reason="no_session",op="issue"|"resolve"}`, so callers
that lose chaining this way are visible. `issue` counts only results that carried an account.

Which callers have a session id today: the staff OBO path sets it to the exchanged token's `jti`,
which is bound to a live `agent_session` row (ADR-0224), so OBO callers always get refs. Neither realm
template in this repo issues the `agent:` subject or the `consent_id` claim that the agent path
requires, so no agent token from them reaches this path yet. Whoever issues those tokens must also
emit `sid`, or those callers fall back to full IBANs and the counter above shows it. `get_balance`, `list_transactions` and
`get_statement` take `accountRef` (preferred) or a full `accountId` IBAN, but not both.

A ref is never decoded. The server reads the caller's own consent-scoped `list_accounts`, computes
the ref of each IBAN for the caller's own scope and compares them in constant time. A ref from
another agent, consent or session, or a tampered one, matches nothing and is refused with JSON-RPC
`-32602` (`accountRef is not valid for this caller`) before the read port is called. Resolving a ref
costs one extra `list_accounts` read.

The key is `openbank.mcp.account-ref.secret` (env `OPENBANK_MCP_ACCOUNT_REF_SECRET`, from the
`mcp-service-account-ref` ExternalSecret, `secretKeyRef` optional). Every replica must share it, and
the value is never logged. A value that is set but shorter than 32 bytes refuses boot. When it is
absent the service generates a per-process random key, logs one WARN and reports
`openbank_mcp_account_ref_ephemeral_key` = 1. Refs then stop working across a restart or between
pods, and a ref that does not resolve is refused, so the only cost is an extra `list_accounts`.

Nobody seeds the key. An ESO `Password` generator (`mcp-service-account-ref-key`, 48 characters,
`refreshPolicy: CreatedOnce`) draws it in-cluster, and it is declared under `generated:` in
`.github/config-inventory.yaml`. After the first sync, restart the pods and confirm the gauge reads
0; alert `McpAccountRefEphemeralKey` (warning) fires when a pod reports 1 for 15m. Rotation: delete Secret `platform/mcp-service-account-ref` (ESO regenerates it) and restart the
pods; outstanding refs are then refused and a model re-reads `list_accounts`. Only the `%dev` and
`%test` profiles carry a fixed non-secret value. Rotating the key invalidates every outstanding ref,
and a model recovers by calling `list_accounts` again.

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

## Staff-root Authorization allowlist (#16590, #17099)

On the staff root `/mcp`, an `Authorization` header is optional, but if it is present it must be exactly
**one** header whose whole value is `Bearer <b64token>` (RFC 6750 §2.1). The scheme is case-insensitive,
followed by a single space and `[A-Za-z0-9-._~+/]+=*`, with nothing else. Staff Keycloak JWTs fit this.

| Request | Answer |
|---|---|
| No `Authorization` header, or one valid `Bearer <b64token>` | Normal handling (audience check, then dispatch) |
| A value starting with the `DPoP` scheme (any case, any whitespace), or a valid Bearer issued by the customers realm | 401 `invalid_token`, pointing the client at `/customer/mcp` |
| Anything else: comma lists, tabs, extra or non-ASCII spaces, other schemes such as `Basic`, an empty token | 400 `invalid_request` |
| More than one `Authorization` header, an unreadable header, or no injected request | 400 `invalid_request` (fail closed) |

The check runs before the audience check and the rate limiter. A 400 here means the client sent a malformed
credential, not that the token was rejected.

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

## Customer-channel Valkey (`mcp-valkey`)

The customer MCP channel's shared DPoP replay store and D5.3 budgets live in `mcp-valkey`
(namespace `platform`, `openbank-infra/gitops/components/mcp/valkey.yaml`). It is deliberately
ephemeral, so:

- **A restart, reschedule or OOM-kill empties it.** For up to 365 s afterwards a proof that was
  already used can be accepted once more, and every customer budget restarts from zero. Do not roll
  it during customer traffic; if it restarts unexpectedly, treat the next 365 s as a reduced-assurance
  window and check `mcp-valkey` for OOM (`kubectl -n platform describe pod -l app.kubernetes.io/name=mcp-valkey`).
- **Full store:** `--maxmemory 128mb` with `noeviction` makes writes fail rather than evict; the channel
  then answers 503 `replay_store_unavailable`. Never change the policy to an evicting one — eviction
  silently reopens replay.
- **Secrets:** generated in-cluster, nothing to provision. Three ESO `Password` generators
  (`refreshPolicy: CreatedOnce`, 48 letters/digits each) feed three Secrets: `mcp-customer-store-valkey`
  (`valkey_password`), `mcp-customer-store-hmac` (`key_hmac_secret`) and `mcp-customer-store-metrics`
  (`metrics_password`, the exporter's ACL user). No copy exists outside the
  cluster. Until ESO creates them the Valkey pod waits in `CreateContainerConfigError` (by design: it
  never starts unauthenticated) and mcp-service runs with the customer channel off; enabling the
  channel without them refuses to boot.
- **Auth:** the Valkey container reads its passwords from files mounted from the Secrets (tmpfs,
  mode 0440), validates them (`[A-Za-z0-9]+`, non-empty), renders them into a tmpfs config file and
  `exec`s `valkey-server`: they are in neither the process arguments, the container environment, nor
  the node's disk. The exporter's least-privilege `metrics` password is an env var in its own
  container (redis_exporter has no single-target password file).
- **Rollout:** merging this restarts the live mcp-service pod once (its env gains the Valkey host,
  password and HMAC refs; terminate-first, ~160 s unavailable). mcp-valkey is created fresh.
- **Exporter:** the `redis_exporter` sidecar uses the `metrics` ACL user (own generated password in
  `mcp-customer-store-metrics`, introspection commands only, no `CONFIG GET`), its `/scrape`
  multi-target endpoint is disabled, and port 9121 admits only the Prometheus pods.
- **Alerts:** `MCPValkeyMemoryNearMax` (> 80% of maxmemory for 5m, warning) and
  `MCPValkeyMemoryFull` (> 95% for 2m, critical) from the redis_exporter sidecar.
- **Rotation** (customer channel drained):
  - Valkey password: delete Secret `platform/mcp-customer-store-valkey` (ESO regenerates it), then
    restart `mcp-valkey` and every mcp-service pod. The Valkey restart empties the store: live replay
    entries and budgets reset.
  - Metrics password: delete Secret `platform/mcp-customer-store-metrics`, then restart `mcp-valkey`
    (same store-emptying effect).
  - Key HMAC secret: delete Secret `platform/mcp-customer-store-hmac`, then restart every mcp-service
    pod. Keys hashed with the old secret are orphaned until they expire (at most a day).

## The edge hop: mTLS identity, not IP (#16847)

The edge is trusted by its client-certificate IDENTITY, never by source IP. No
`quarkus.http.proxy.*` switch and no trusted-proxies list is ever set; `ProxyForwardingGuard`
(`@Startup`) refuses to boot with any of them, so no listener rewrites scheme, host or address
from a peer header.

- **Listeners.** `:8443` is TLS 1.3 with client auth REQUIRED (`%prod` build-time
  `quarkus.http.ssl.client-auth=required`, certificate from `mcp-service-internal-tls`); `:8150`
  stays plain HTTP for in-cluster callers and probes. The customer root `/customer/mcp` (DPoP-only,
  `customers` tenant) and its RFC 9728 metadata are served ONLY on `:8443`; on `:8150` they answer
  404. Staff `/mcp` keeps Bearer on both listeners.
- **Customer credentials on the staff root.** `/mcp` answers 401 (`WWW-Authenticate: Bearer
  error="invalid_token"`, description naming `/customer/mcp`) to any customers-realm token or any
  `Authorization: DPoP` credential, whatever the channel state, before the audience check and the
  limiter. Counted as `openbank_mcp_customer_rejected_total{layer="token"}` with
  `reason="customer_channel_off"` (channel off) or `reason="customer_root_required"` (channel on);
  a refused `tools/call` is audited DENIED within the shared anonymous budget (beyond it, once per
  window). More than one `Authorization` header on `/mcp` answers 400 `invalid_request`. A steady rate of `customer_root_required` means a client
  is configured with the staff URL.
- **DPoP `htu`.** quarkus-oidc compares the proof's `htu` with `absoluteURI()`. On `:8443`, with the
  external Host passed through by the edge, that is `https://<published host>/customer/mcp`, so no
  forwarding is needed; a proof for another host, scheme or path answers 401 at the tenant.
- **Audit key.** `ClientAddressResolver` honours the ONE edge-set header named by
  `MCP_EDGE_CLIENT_ADDRESS_HEADER` (e.g. `X-Envoy-External-Address`) only over TLS, from a verified
  leaf whose DNS SAN equals `MCP_EDGE_GATEWAY_IDENTITY` (the CN is never consulted), and only when it
  occurs once as a single IP literal. Every other peer, and plain HTTP, keys on the raw TCP peer;
  `X-Forwarded-For` is never read. `ClientAddressKey` is an HMAC-SHA256 (per-boot random key) of
  IPv4 /32 or the IPv6 /64 prefix (`IpLiteral`, no DNS); anything else keys as `ip:unknown`.
  Distinct subject-less keys are capped at 4096 per minute, then share `ip:overflow`. The cap is
  tiered, because one party cheaply holds many networks: one allocation (IPv6 /48, IPv4 /24) may
  hold 64 distinct keys per minute, after which its further networks share one `net:` allocation
  key; one region (IPv6 /32) may hold 256 keys, allocation keys included, after which they share
  one `region:` key. So filling the 4096 budget, which would push other callers into `ip:overflow`
  and suppress their audit rows, needs at least 16 distinct /32s or a spread of IPv4 /24s. The
  first overflow of each minute logs one WARN, so that state is never silent. The guard refuses
  the header without the SAN pin or without client-auth REQUIRED.
- **Canonical paths only.** `McpPathCanonicalFilter` answers 400 to any raw path with `;`, `%`,
  `\`, an empty, `.` or `..` segment, before authentication, on both listeners — the HTTP
  permissions, the OIDC `tenant-paths` and the JAX-RS match must all see the same spelling.
- **Go-live blocker: who can mint the pinned SAN.** `openbank-ca` is a ClusterIssuer and the repo
  carries no cert-manager approver policy, so any namespace able to create a `Certificate` can obtain
  the `MCP_EDGE_GATEWAY_IDENTITY` SAN. The header it unlocks only feeds the subject-less audit /
  throttle window key (never authentication or authorization), so this is accepted until go-live;
  constrain issuance of that SAN (approver policy or a dedicated issuer) before the customer route
  is enabled.
- **Deployed state.** No route to mcp-service exists. The route PR must target `:8443`, add the
  BackendTLSPolicy (CA bundle + hostname `mcp-service.platform.svc`), set
  `MCP_EDGE_CLIENT_ADDRESS_HEADER` + `MCP_EDGE_GATEWAY_IDENTITY`, and admit only the gateway to
  `:8443` in the NetworkPolicy.
- **Symptoms:** every customer call 401 `invalid_dpop_proof` at the tenant means the edge did not
  pass the external Host through (or routed to `:8150`); every tokenless caller sharing one audit
  key means the SAN pin does not match the gateway certificate.

## Customer channel metrics and alerts (ADR-0384 slice 1)

`McpMetricsAdapter` records the customer MCP channel's admission and tool-call outcomes
(per allowlisted client, per tool, with the DPoP/replay-refusal reason where one applies)
through the `McpMetricsPort`; the staff channel's counters are unchanged. The admin UI
reads the same series through the delegations MCP-channel view. Alert thresholds for the
channel live with the fleet alerting rules, not in this service.

### Why a tool call failed: `openbank_mcp_tool_call_failures_total`

The tool-call counter records `result="FAILURE"` without a reason, so a client's bad arguments
and a downstream fault look the same there. Every failed `tools/call` (and a PDP outage,
`decision=UNAVAILABLE`) also increments `openbank_mcp_tool_call_failures_total{tool,kind}`,
where `kind` is a closed set: `invalid_params` (client error), `unavailable` (an advertised tool
with nothing behind it yet) and `error` (a server-side fault, also the default for any unmapped
reason, and what a PDP outage maps to). The customer-channel availability SLO
(`pyrra-slo-mcp-customer.yaml`) counts `kind="error"` only.
