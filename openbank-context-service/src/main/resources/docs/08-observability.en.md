# Observability — openbank-context-service

## Tracing baseline

The service carries the fleet OpenTelemetry baseline (`quarkus-opentelemetry`, ADR-0008). Since
that extension was added, every HTTP request and every reactive Postgres statement the service
executes is instrumented by Quarkus itself — no hand-written span code, no test-only paths.

What an OTLP collector therefore receives for a normal request:

- an HTTP server span per request, named `METHOD /api/v1/context/...`, carrying
  `http.response.status_code` and standard HTTP attributes;
- a database client span per persisted statement, named after the operation and table
  (e.g. `INSERT context_assignment_proposals`), carrying `db.sql.table` and friends;
- child spans nested under the request span, because the reactive context — not a thread — is
  what OpenTelemetry propagates.

## Why propagation is the contract

The maker-checker flows write an audit row in the same transaction as the business row
(`INSERT context_assignment_change_audit` next to `INSERT context_assignment_proposals`). A
write that escaped the request's reactive context would still commit and the endpoint would
still answer 201 — but the audit row would land in a trace of its own, and a regulator piecing
together "who proposed this assignment" from traces would lose the linkage.

`ContextAssignmentTraceContractIT` holds that line: it asserts the request span and both INSERT
spans share one trace and that no span in the trace is an error. The test reads spans from an
in-memory SDK exporter — the same `SpanData` an OTLP collector would receive — so the contract
cannot be satisfied by anything the runtime does not actually emit.

## Background traffic to be aware of

Two commitment relays (`ContextAuditCommitmentRelay`, `ContextDisclosureCommitmentRelay`) run
on a 5-second scheduler and poll their outbox tables. Their polls and sends are real spans and
appear in production traces as background work detached from any request; when reading a trace
for an investigation, filter to the request span of interest rather than assuming everything in
the window belongs to one operation.

## Test isolation

The trace-contract IT runs under its own Quarkus test profile with the scheduler disabled and
automatic OTLP export switched off, so its evidence is a statement about the operation under
test, not about the whole module's traffic.
