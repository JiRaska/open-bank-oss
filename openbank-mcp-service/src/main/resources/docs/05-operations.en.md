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

## Token audience on /mcp

`/mcp` accepts a bearer token only when its `aud` claim contains `openbank-mcp-service` (RFC 8707, ADR-0383 D1, #16622). A token issued for any other resource, or one with no audience, is refused with HTTP 401 and `WWW-Authenticate: Bearer error="invalid_token"` on every JSON-RPC method, before tools are listed or called. A refused `tools/call` is still audited as DENIED with reason `token audience mismatch`, and the `caller_identity` meter counts it as a resolution failure. A call with no token at all is unchanged: it is answered without tools, as before.

The check lives in `McpEndpoint` and again in `CallerContextResolver`, not in `quarkus.oidc.token.audience`. That setting applies to the whole tenant, and `/api/v1/mcp/sessions` is called with the operator's own token, whose audience is not this server.

The only caller in the tree today is admin-ui's OBO route, which exchanges for `audience=openbank-mcp-service` and so already carries it. No realm client mints `agent:` tokens yet; an issuer that starts to must add an audience mapper for `openbank-mcp-service`, or its tokens will get 401.

If the OIDC issuer cannot be reached, Quarkus OIDC fails a presented token with HTTP 500 rather than 401. That is fail-closed (nothing reaches the endpoint) and is left as is here.
