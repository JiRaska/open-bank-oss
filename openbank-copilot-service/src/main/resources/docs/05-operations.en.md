# Operations

## Build & run

```bash
./gradlew :openbank-copilot-service:build
```

HTTP port **8131**. The service is gated off by the `copilot-assistant` feature flag / `copilot.enabled`.

## Logging of proposal tokens

A proposal token id (`ProposalToken.id`, the `{tokenId}` of
`POST /api/v1/copilot/actions/{tokenId}/confirm`) is a one-time capability, so it is never written to
a log in the clear. The token stores (in-memory and Redis), the confirm endpoint and its policy-deny
path log a stable, non-reversible reference instead:

```
token_ref=ptk_<first 12 hex chars of SHA-256(token id)>
```

The same token always yields the same `token_ref`, so the store, confirm and deny lines of one
proposal still correlate. To find the lines for a token id you hold, compute its reference with
`ProposalToken.logRef(id)` and search for that. `ProposalTokenLogRefTest` fails the build if a
production log format reintroduces a raw `token=` / `tokenId=` field.

Note: nothing in `src/main` issues a proposal token today (#5900), so these lines appear only in
tests until the confirm path is wired.

Operational proposal and policy diagnostic logs contain outcomes and the hashed token reference, never customer subjects, tool names, policy reasons or deserialisation payloads. Attributed audit events remain a separate record: they retain actor/resource attribution and the audit payload fields required to explain the decision. Exception messages and stack traces are omitted from these diagnostic paths because they can contain request data. HTTP status, token TTL and generated action ids remain available for diagnosis.

The same rule covers the model/tool degradation path and the `party-events-in`
consumer: a failed model or tool call logs only the exception class (its message
can echo tool-call arguments or model output), and an unparseable or invalid
party event logs only its length and the exception class — never the payload,
and never the Jackson exception itself, whose message embeds a payload excerpt.

## Shared build dependencies

This service's `build.gradle.kts` does not list the dependencies every Quarkus service shares: the Quarkus BOM, quarkus-kotlin, RESTEasy Reactive with Jackson, SmallRye Health and OpenAPI, OIDC, the Micrometer Prometheus registry and config-yaml, plus the quarkus-junit5, AssertJ and REST Assured test libraries. The `openbank.quarkus-service` convention plugin in `build-logic` declares them. The resolved classpath is unchanged. `build.gradle.kts` lists only what this service adds.
