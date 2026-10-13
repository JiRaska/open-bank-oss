# Data

## Schema

PostgreSQL schema `balance` in the openbank cluster (schema-per-service).

```mermaid
erDiagram
  BALANCES ||--o{ BALANCE_HOLDS : "has many"
  BALANCES ||--o{ BALANCE_OUTBOX : "emits"

  BALANCES {
    bigserial id PK
    uuid account_id "FK ref to account-svc, no DB FK"
    char currency "ISO 4217"
    numeric booked_amount "settled"
    numeric available_amount "booked − reserved − pendingDebit + overdraft"
    numeric reserved_amount "sum of active holds"
    numeric pending_amount "settlement in flight"
    numeric arranged_overdraft_limit "≥ 0"
    timestamptz updated_at
    bigint version "optimistic lock"
  }

  BALANCE_HOLDS {
    bigserial id PK
    uuid hold_id UK
    uuid account_id
    numeric amount
    char currency
    text reason "CARD_AUTH | PAYMENT_RESERVE | MANUAL | COMPLIANCE"
    text reference_id
    timestamptz expires_at
    timestamptz created_at
    timestamptz released_at "NULL = active"
  }

  BALANCE_OUTBOX {
    bigserial id PK
    uuid event_id UK
    text aggregate_id "accountId|currency"
    text event_type
    text payload "JSONB"
    text status "PENDING|PUBLISHED|FAILED"
    integer attempts
    timestamptz created_at
    timestamptz published_at
  }
```

## Migrations

Flyway, forward-only:

| Script | What it does |
|---|---|
| `V1__init.sql` | Tables `balances` + `balance_holds` + indexes + UNIQUE(account_id, currency) |
| `V2__create_balance_outbox.sql` | Transactional outbox |
| `V3__arranged_overdraft_limit.sql` | Column `arranged_overdraft_limit NUMERIC(19,4) NOT NULL DEFAULT 0` |
| `V4__balance_reconciliation.sql` | Read-only reconciliation audit table (ADR-0039 Phase A) |
| `V5__hibernate_sequences.sql` | `<table>_seq` sequences PanacheEntity needs (`balances`, `balance_holds`, `balance_outbox`, `balance_reconciliation`) |

## Indexes

- `balances(account_id, currency)` — UNIQUE; primary access path
- `balances(account_id)` — list account across currencies
- `balance_holds(account_id)` — list active holds
- `balance_holds(reference_id)` — lookup for card auth ↔ capture
- `balance_holds(expires_at) WHERE released_at IS NULL` — partial for the expiry worker
- `balance_outbox(status, created_at) WHERE status='PENDING'` — dispatcher poll

## Retention

| Table | Retention | Reason |
|---|---|---|
| `balances` | forever | cannot delete an active account |
| `balance_holds` | forever (logical delete via `released_at`) | audit + dispute |
| `balance_outbox` | 30 days after PUBLISHED | troubleshooting + replay |

## PII

- `account_id` is a UUID, an indirect identifier. By itself it is not PII without a join to party-service.
- No IBAN, name, or email here.

## Consistency vs. ledger-service

`balance.booked` is updated **after a commit in the ledger**. No booked > ledger sum (modulo the eventually-consistent window of tens of ms). Check:

```sql
-- reconciliation query, daily 02:00 UTC in audit
SELECT b.account_id, b.currency,
       b.booked_amount,
       (SELECT sum(amount) FROM ledger.journal_lines WHERE account_id=b.account_id AND currency=b.currency) as ledger_sum
FROM balance.balances b
WHERE b.booked_amount != (SELECT sum(amount) FROM ledger.journal_lines …)
LIMIT 100;
```

If divergence > 0 → alert to PagerDuty.

## Size (1M active customers, ~1.2 currencies per account average)

| Table | Rows | Size |
|---|---|---|
| `balances` | ~1.2M × 250 B | **~300 MB** |
| `balance_holds` | ~3M (incl. released) × 200 B | **~600 MB** |
| `balance_outbox` (30d) | ~30M × 500 B | **~15 GB** |

## Outbox retention (SENT rows)

`balance_outbox` is a delivery buffer, not a record. Rows that reached the broker (`status = 'SENT'`) are deleted once their `sent_at` is older than `openbank.outbox.retention.sent-days` (default **7**), by libs-runtime's shared `OutboxSentRetentionJob` (ADR-0329, ADR-0327 D8). The repository opts in by delegating `SentOutboxRetention` to `PanacheOutboxRetention`.

- Runs nightly (`openbank.outbox.retention.cron`, default `0 17 3 * * ?`) on every replica; each delete is bounded (`batch-size` 5 000, at most `max-batches` 200 per run), so a long-unpurged table drains over several nights.
- PENDING, FAILED, DISPATCHING and DEAD rows are never touched — DEAD rows are the producer-side DLQ.
- Replaying an event older than the window comes from the Kafka topic or audit-service, not from this table.
- Signals: `openbank_outbox_purged_total{service,status="SENT"}`, `openbank_outbox_purge_failed_total`, and workflow liveness `outbox-sent-retention`.
- Opt-out: `openbank.outbox.retention.enabled=false` (logs a WARN at boot). Don't lower `sent-days` below what any reader of SENT rows needs.
