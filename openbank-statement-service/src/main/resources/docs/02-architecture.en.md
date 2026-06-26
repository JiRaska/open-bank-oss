# Architecture

The service follows the OpenBank hexagonal architecture ([ADR 0002](../../../../docs/adr/0002-hexagonal-architecture-per-service.md)): a framework-free **domain**, an **application** layer of use cases and ports, and **infrastructure** adapters on the edges. The defining property of this service is **determinism**: the persisted record is small, and every rendered output is a pure, byte-identical projection of it.

## C4 — context

```
        ┌──────────────┐         ┌──────────────────────┐
        │  account-svc │──Kafka─►│                      │
        └──────────────┘         │  statement-service   │
        ┌──────────────┐  REST   │                      │──Kafka──► statement consumers
        │ transaction  │◄────────│  (close + render)    │           (audit, downstream)
        │ balance      │◄────────│                      │
        │ account/party│◄────────│                      │
        └──────────────┘         └──────────┬───────────┘
                                            │
                                       PostgreSQL
                                    (openbank_statement)
```

## Hexagonal layers

### Domain (`domain/`, zero framework imports)

- **`domain/model`** — `StatementModel` (canonical immutable aggregate, owns `netMovement`), `StatementEntry`, `BalanceAnchor`, `StatementPeriod` (retained record), `StatementFormat`, `CreditDebit`, `PeriodCloseStatus`, `CloseRun` / `CloseTrigger`.
- **`domain/reconcile`** — `ReconciliationPolicy`: pure, fail-closed. `closing = opening + netMovement` **must equal** balance-service's reported closing (compared via `BigDecimal.compareTo` so `100` vs `100.00` does not spuriously fail). A mismatch yields `Result.Mismatch`.
- **`domain/render`** — `Camt053Renderer`, `Mt940Renderer`, `PdfRenderer`, and `StatementRenderer` (dispatch). All timestamps come from `StatementModel.closedAt`, never the wall clock — re-render is byte-identical (guarded by the renderer tests).
- **`domain/close`** — `CloseCalendar`: derives the month windows still owed for a pocket (self-healing catch-up).

### Application (`application/`)

- **Inbound ports (`port/in`)** — `ClosePeriodUseCase`, `ClosePocketUseCase`, `RenderStatementUseCase`, `ListStatementsUseCase`, `AdHocExportUseCase`, `RunCloseUseCase`, `CloseRunQueryUseCase`.
- **Outbound ports (`port/out`)** — `AccountInfoPort`, `BookedEntryPort`, `BalancePort`, `StatementPeriodRepository`, `AccountRegistry`, `CloseRunPorts`, `CloseMetricsPort`.
- **Use cases** — `StatementService` (close / render / export / list wiring; orchestrates reconcile + sequence assignment + atomic save-with-outbox), `CloseOrchestrator` (the self-healing scheduled/manual close pass; isolates per-pocket failures, records the run outcome, emits `period.close_failed`).

### Infrastructure (`infrastructure/`)

- **`rest`** — `StatementResource` (`/api/v1/statements`), `CloseRunResource` (`/api/v1/statements/close-runs`). Driving adapters; translate `ReconciliationException` → 409, `StatementNotFoundException` → 404.
- **`client`** — `RestClients` + `RestPortAdapters`: MicroProfile rest-client adapters for transaction / balance / account / party reads, carrying an OIDC client-credentials bearer (`OidcClientRequestReactiveFilter`).
- **`kafka`** — `AccountRegistryConsumer`: consumes `openbank.accounts.account.created`, filters by event type (`AccountCreated`), idempotently upserts into the local `account_registry` (`auto.offset.reset=earliest` back-fills history on first deploy).
- **`scheduler`** — `PeriodCloseScheduler`: cron `0 30 2 1 * ?` (1st of month, 02:30), `concurrentExecution = SKIP`, gated by `openbank.statement.scheduled-close.enabled`.
- **`outbox`** — `StatementOutboxDispatcher` + `KafkaStatementOutboxEventPublisher` + `StatementOutboxRepository` (see below).
- **`persistence`** — Panache reactive entities, mappers, repositories for `statement_period`, `statement_outbox`, `account_registry`, `statement_close_run`, `statement_close_failure`.
- **`metrics`** — `CloseMetricsAdapter`: Micrometer/Prometheus counters for the close cadence.

## Period-close flow (fail-closed)

```
POST /{accountId}/close (from,to)
  │
  ▼
StatementService.closeMonth
  │  for each pocket currency (sequential, concatenate — race-free sequence)
  ▼
closePocket → findByPeriod  ──► exists? ── yes ──► return existing (idempotent)
  │                                  └─ no
  ▼
mintPeriod:
  • bookedEntries(account,ccy,from,to)         (transaction-service)
  • opening = prior close's closing, else balance the day before (balance-service)
  • reported closing                            (balance-service)
  • ReconciliationPolicy.reconcile(opening, netMovement, reported)
        ├─ Mismatch  → ReconciliationException → HTTP 409 (NO record, NO event)
        └─ Reconciled → nextLegalSequence → StatementPeriod
                       → saveWithOutbox (period record + period.closed event, one transaction)
```

The opening balance chains: a pocket's opening is the prior close's closing (continuity), falling back to balance-service's balance the day before the period when there is no prior close.

## Outbox → Kafka (ADR-0050)

`saveWithOutbox` writes the `statement_period` row and the `account.statement.period.closed.v1` outbox row in **one transaction**, so a crash can never leave a closed period whose event was never emitted.

`StatementOutboxDispatcher` (`@Scheduled every 5s`, `concurrentExecution = SKIP`, Deployment pinned to `replicas: 1` — single writer, ADR-0050 N4) drains PENDING/FAILED rows on the Vert.x event loop, sequentially (`transformToUniAndConcatenate`, preserving per-aggregate order). `KafkaStatementOutboxEventPublisher` publishes with:

- **Partition key = `aggregate_id`** (N2) — all events for one statement aggregate keep their order.
- **`ce-id` / `idempotency-key` headers = `event_id`** (N3) — at-least-once delivery is safely deduplicated downstream.
- **Fault tolerance** — `@Bulkhead`, `@CircuitBreaker`, `@Retry`, `@Timeout`.

Repeated publish failures increment `attempt_count`; at `MAX_ATTEMPTS = 10` the row is parked in the terminal **DEAD** state (N5) and excluded from the processable query, with a WARN log for alerting.

## Key ports (summary)

| Port | Direction | Adapter | Backing |
|---|---|---|---|
| `ClosePeriodUseCase` / `RenderStatementUseCase` / … | in | `StatementResource` | REST |
| `RunCloseUseCase` / `CloseRunQueryUseCase` | in | `CloseRunResource`, `PeriodCloseScheduler` | REST / cron |
| `BookedEntryPort` | out | `RestPortAdapters` | transaction-service |
| `BalancePort` | out | `RestPortAdapters` | balance-service |
| `AccountInfoPort` | out | `RestPortAdapters` | account / party |
| `StatementPeriodRepository` / `CloseRunPorts` | out | persistence | PostgreSQL |
| `AccountRegistry` | out | `AccountRegistryConsumer` + repo | Kafka + PostgreSQL |
| (outbox) | out | `StatementOutboxDispatcher` | PostgreSQL → Kafka |
