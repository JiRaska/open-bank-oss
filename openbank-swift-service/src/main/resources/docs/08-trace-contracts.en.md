# Trace contracts — swift message send

The `SwiftTraceContractIT` is the swift leg of the fleet's trace-contract
programme. It drives `POST /api/v1/swift` over real HTTP against
Testcontainers Postgres + Valkey and asserts on the spans the runtime itself
emits — the request span, the `INSERT swift_messages` span, and that both
share ONE trace.

## Why a trace contract, on top of the outbox atomicity IT

`SwiftOutboxAtomicityIT` already proves the message row and its outbox row
commit in one transaction (same Postgres `xmin`). That says nothing about
*which trace* the write landed in: a write that escaped the request context
— an un-propagated reactive hop, a fire-and-forget executor — still writes
the row, still answers 201, and lands in a trace of its own. The trace
contract fails on exactly that, which no pre-existing assertion could see.

## Test wiring that is intentional, not incidental

- The ADR-0104 D4 scheme-submission pilot flag is ON and the
  `AcceptingSchemeGateway` / `AccountIdDrivenSettlement` alternatives stand in
  for the clearing simulator and settlement leg — otherwise the real adapters
  attempt outbound HTTP whose error spans would trip `hasNoErrorSpan`.
- The message is sent without `orderingCustomerAccountId`, so settlement
  no-ops and the flow ends at `SENT` with the smallest write set that
  persists the message.
- `quarkus.oidc-client.enabled` is pinned to `false` in the trace profile:
  `%test` fixes it at build time, and a profiled boot otherwise reads the
  extension default (`true`) and `ConfigRecorder` refuses to start (measured
  on sepa-payment, identical `%test` shape).

Nothing under `src/test` is production attack surface; this note exists
because the service-docs freshness gate requires authored documentation to
accompany every production-input change, including `build.gradle.kts`.
