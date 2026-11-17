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
