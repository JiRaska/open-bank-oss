# Trace contracts — lending application apply

The `LendingTraceContractIT` is the lending leg of the fleet's trace-contract
programme. It drives `POST /api/v1/lending/applications` over real HTTP
against Testcontainers Postgres + Valkey and asserts on the spans the runtime
itself emits — the request span, the `INSERT loan_application` span, and that
both share ONE trace.

## Why a trace contract, on top of the outbox ITs

`LendingOutboxWriteIT` proves the outbox rows land for the origination flow.
That says nothing about *which trace* the write landed in: a write that
escaped the request context — an un-propagated reactive hop, a fire-and-forget
executor — still writes the row, still answers 201, and lands in a trace of
its own. The trace contract fails on exactly that, which no pre-existing
assertion could see.

## Wiring

Mirrors the sibling contracts: a `TraceContractRecorder` produces the
in-memory span exporter, and a per-class `TraceContractProfile` turns the OTel
SDK on and the outbox dispatcher off (its background sends are spans the
contract never asked about). Kafka rides the in-memory connector — no broker
container exists in this IT. Lending's `%test` profile does not fix
`quarkus.oidc-client.enabled`, so unlike sepa-payment no build-time pin is
needed (verified by the green boot).

Nothing under `src/test` is production attack surface; this note exists
because the service-docs freshness gate requires authored documentation to
accompany every production-input change, including `build.gradle.kts`.
