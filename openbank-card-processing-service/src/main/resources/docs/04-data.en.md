# Data

Database: `openbank_card_processing` (PostgreSQL), owned solely by this service (`governance.yaml`). Data domain `payments`, classification `confidential`, retention **7 years** (accounting records, ADR-0118).

## Tables

### `card_authorizations`

One row per authorisation — approved or declined. It is also the hold.

| Column | Type | Notes |
|---|---|---|
| `id` | UUID PK | UUIDv7 (ADR-0106) |
| `card_id`, `account_id`, `party_id` | UUID | card reference from card-issuance — **no PAN** |
| `amount_minor_units` | BIGINT | `> 0` |
| `currency_code` | VARCHAR(3) | |
| `channel` | VARCHAR(16) | `CONTACTLESS` / `ONLINE` / `ATM` / `CHIP_AND_PIN` |
| `mcc`, `merchant_name`, `merchant_country` | | optional |
| `status` | VARCHAR(20) | `APPROVED`, `DECLINED`, `PARTIALLY_CLEARED`, `CLEARED`, `REVERSED`, `EXPIRED` |
| `category` | VARCHAR(40) | card-issuance's MCC category, kept so clearing never re-judges it |
| `decline_reason` | VARCHAR(40) | set iff `DECLINED` |
| `cleared_amount_minor_units` | BIGINT | cumulative presented amount |
| `network_reference` | VARCHAR(64) | acquirer's reference |
| `idempotency_key` | VARCHAR(128) | written once on insert |
| `authorized_at`, `expires_at`, `updated_at` | TIMESTAMPTZ | |

Constraints and indexes:

- `card_authorizations_cleared_within_authorized` — `cleared ≤ amount` (restates `AuthorizationLifecycle.clear`).
- `card_authorizations_decline_reason_iff_declined`.
- UNIQUE `ux_card_authorizations_idempotency_key`; partial UNIQUE `ux_card_authorizations_network_reference` (non-null only).
- `ix_card_authorizations_card_authorized_at (card_id, authorized_at DESC)` — spend counting.
- Partial `ix_card_authorizations_expiring (expires_at)` where status is `APPROVED` or `PARTIALLY_CLEARED` — the expiry sweep.

The held amount (`amount − cleared` while holding) is **derived**, never stored.

### `card_outbox`

Transactional outbox (ADR-0050): `event_id` (unique), `aggregate_id`, `event_type`, `payload`, `status` (default `PENDING`), `attempt_count`, `last_error`, `created_at`, `updated_at`, `sent_at`, `claimed_at`, `synthetic` (V2). Hibernate uses sequence `card_outbox_seq` (increment 50), created explicitly in V1.

## Migrations

| Version | Content | Rollback |
|---|---|---|
| V1 `init_card_processing` | both tables, indexes, `card_outbox_seq` | `DROP TABLE card_outbox; DROP TABLE card_authorizations;` — safe only before any authorisation exists |
| V2 `synthetic_outbox_taint` | `card_outbox.synthetic BOOLEAN NOT NULL DEFAULT FALSE` (ADR-0252) | `ALTER TABLE card_outbox DROP COLUMN synthetic;` — safe only before synthetic traffic was dispatched |

`migrate-at-start: true`; `validate-on-migrate: false`.

## Personal and sensitive data

- **No cardholder data** in the PCI sense: no PAN, CVV, expiry or cardholder name.
- `party_id` / `account_id` link to a natural person through other services; merchant name and country describe where the customer spent money. Treated as confidential financial data.
- Events carry the same fields as the row minus `idempotency_key`; no card credential is ever emitted.

## Outbox retention (SENT rows)

`card_outbox` is a delivery buffer, not a record. Rows that reached the broker (`status = 'SENT'`) are deleted once their `sent_at` is older than `openbank.outbox.retention.sent-days` (default **7**), by libs-runtime's shared `OutboxSentRetentionJob` (ADR-0329, ADR-0327 D8). The repository opts in by delegating `SentOutboxRetention` to `PanacheOutboxRetention`.

- Runs nightly (`openbank.outbox.retention.cron`, default `0 17 3 * * ?`) on every replica; each delete is bounded (`batch-size` 5 000, at most `max-batches` 200 per run), so a long-unpurged table drains over several nights.
- PENDING, FAILED, DISPATCHING and DEAD rows are never touched — DEAD rows are the producer-side DLQ.
- Replaying an event older than the window comes from the Kafka topic or audit-service, not from this table.
- Signals: `openbank_outbox_purged_total{service,status="SENT"}`, `openbank_outbox_purge_failed_total`, and workflow liveness `outbox-sent-retention`.
- Opt-out: `openbank.outbox.retention.enabled=false` (logs a WARN at boot). Don't lower `sent-days` below what any reader of SENT rows needs.
