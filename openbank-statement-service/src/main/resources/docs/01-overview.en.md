# Overview

## What the service does

`openbank-statement-service` is the **account-statement authority** for the OpenBank multi-currency current account (ADR-0035). The unit of work is the **per-pocket statement** — one IBAN, one currency — each with its own independent legal sequence. It holds:

- **StatementPeriod** — the retained period-close record: legal/electronic sequence number, opening/closing balance anchors, entry count, status (CLOSED / SUPERSEDED), `closedAt`. This is the **only** persisted statement artefact.
- **StatementModel** — the canonical, immutable in-memory aggregate for one pocket over one period, from which every rendered format is a *pure projection*.
- **CloseRun / CloseFailure** — operational telemetry of the scheduled/manual close cadence (ADR-0069 D3): how many pockets closed, failed, or were skipped, and why.
- **AccountRegistry** — a read-only local projection of accounts (built from the account-service event stream) used to enumerate accounts for the scheduled monthly close.

The three renderers (camt.053.001.08, MT940, PDF) produce **deterministic, byte-identical projections on demand** and discard them — rendered files are never warehoused. This is legal under PSD2 Art. 58(2) ("provided *or made available* … at least monthly, reproducible unchanged"): we *make available*, we don't push files.

## What the service **does NOT** do

- ❌ Does not compute or own balances — the authoritative closing balance comes from `openbank-balance-service`; statement-service reconciles against it fail-closed.
- ❌ Does not own transactions — booked entries are replayed from `openbank-transaction-service`.
- ❌ Does not store rendered files — only the small `StatementPeriod` record is persisted; camt.053 / MT940 / PDF are rendered on demand.
- ❌ Does not net pockets — each currency pocket has its own statement and sequence; the consolidated PDF carries only an *informational* reference-currency total (ADR-0024).
- ❌ Does not produce the **PAD Art. 5 annual statement of fees** — that is a *push* obligation owned by the fee/billing domain.
- ❌ Does not move money — it is not a money-path service.

## Position in the domain

```
   ┌────────────────────────┐  AccountCreated   ┌──────────────────────┐
   │     account-service     │ ───────────────► │ statement-service    │
   └────────────────────────┘   (Kafka)         │  AccountRegistry     │
                                                 │  (enumeration)       │
   ┌────────────────────────┐  REST (M2M read)  │                      │
   │  transaction-service    │ ◄─────────────── │  period-close +      │
   │  balance-service        │ ◄─────────────── │  render-on-demand    │
   │  account / party        │ ◄─────────────── │                      │
   └────────────────────────┘                   └─────────┬────────────┘
                                                           │ outbox → Kafka
        admin UI / customer app                            ▼
        GET render (camt.053 / MT940 / PDF) ◄──────  openbank.statement.event
                                                  ( account.statement.period.closed.v1 )
                                                           │
                                                  PostgreSQL (openbank_statement)
```

## Key use cases

| Use case | API | Event |
|---|---|---|
| Close a month for every pocket of an account | `POST /api/v1/statements/{accountId}/close` | `account.statement.period.closed.v1` |
| List retained period-close records | `GET /api/v1/statements/{accountId}` | — |
| Render a closed statement on demand | `GET /api/v1/statements/{accountId}/{currency}/{legalSequence}` | — |
| Ad-hoc informational export (non-sequenced) | `GET /api/v1/statements/{accountId}/{currency}/export` | — |
| Inspect / trigger the close cadence | `GET`/`POST /api/v1/statements/close-runs` | `period.close_failed` (on per-pocket failure) |
| Scheduled monthly close (cron, self-healing) | — (cron `0 30 2 1 * ?`) | `account.statement.period.closed.v1` |

## Callers

- **admin-ui / customer app** (via Keycloak token) — render/list statements, inspect close runs.
- **operators / compliance** — trigger manual catch-up close runs, inspect failures.
- **scheduler (internal)** — fires the monthly period-close.

## Dependencies

- **PostgreSQL** (`openbank-postgres`, database `openbank_statement`)
- **Kafka** (`openbank-kafka`) — out: `openbank.statement.event`; in: `openbank.accounts.account.created`
- **balance-service** (REST, M2M) — authoritative closing balance for reconciliation
- **transaction-service** (REST, M2M) — booked entries replay
- **account-service / party-service** (REST, M2M) — pocket account info, holder name
- **Keycloak** — auth (inbound bearer + outbound client-credentials)
- **openbank-libs** — shared runtime plumbing (BuildInfo, DocsResource, outbox conventions)

## Business value

- **Regulatory-grade statements** — per-pocket camt.053 / MT940 / PDF with monotonic legal sequences, reproducible byte-for-byte (PSD2 Art. 58(2), ČNB).
- **Fail-closed integrity** — a period-close whose computed closing disagrees with balance-service fails (HTTP 409); a self-inconsistent legal document is never issued.
- **Store-the-record-not-the-file** — only the tiny period-close record is retained (10y), renders are deterministic projections; minimal storage, maximal reproducibility.
- **Self-healing cadence** — the monthly close catches up missed months automatically and records every run and failure for operators (ADR-0069 D3).
