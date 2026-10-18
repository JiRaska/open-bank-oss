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

## Shared configuration defaults

This service's `application.yaml` does not declare the HTTP security response headers (`quarkus.http.header.*`) or the management interface (`quarkus.management.*`: enabled, port 8085, host 0.0.0.0, root path `/q`). These values come from the fleet defaults in `openbank-libs-runtime/src/main/resources/application.yaml`, which is on every service classpath at a lower precedence than the service's own file. The values are identical to what this service used to declare itself. To change one for this service only, set it in this service's `application.yaml`; the service file takes precedence.
