# Data

## Datastore

- **Engine:** PostgreSQL (reactive PG client + JDBC for Flyway).
- **Database:** `openbank_sepa_instant`.
- **Declared schema name** (governance.yaml): `sepa_instant_schema`. Data domain `payments`, classification **confidential**, lineage role **both** (consumes upstream, produces events).
- **Schema generation:** `hibernate-orm.database.generation = none` — Flyway owns the schema.

## Tables

### `sct_inst_payments` (V1)

The payment aggregate. One row per SCT Inst instruction.

| Column | Type | Notes / PII |
|---|---|---|
| `id` | BIGSERIAL PK | internal surrogate (Hibernate seq, see V3) |
| `payment_id` | UUID UNIQUE | public payment identifier |
| `idempotency_key` | VARCHAR(128) UNIQUE | idempotency guard |
| `status` | VARCHAR(32) | state machine value |
| `debtor_account_id` | UUID | debtor account reference |
| `debtor_iban` | VARCHAR(34) | **PII** — debtor IBAN |
| `debtor_name` | VARCHAR(255) | **PII** — screened name |
| `creditor_iban` | VARCHAR(34) | **PII** — creditor IBAN |
| `creditor_name` | VARCHAR(255) | **PII** — screened name |
| `creditor_bic` | VARCHAR(11) | nullable |
| `amount` | NUMERIC(20,6) | **financial** |
| `currency` | VARCHAR(3) | default `EUR` |
| `remittance_info` | VARCHAR(280) | **PII-bearing free text** |
| `end_to_end_id` | VARCHAR(64) | payer-supplied reference |
| `execution_timeout_at` | TIMESTAMPTZ | watchdog deadline (PROCESSING) |
| `settled_at` | TIMESTAMPTZ | nullable |
| `recalled_at` | TIMESTAMPTZ | nullable |
| `recall_reason` | VARCHAR(64) | nullable |
| `reject_reason` | VARCHAR(64) | e.g. `SANCTIONS_HIT` |
| `reject_detail` | TEXT | screening detail |
| `submitted_at` | TIMESTAMPTZ | nullable |
| `created_at` | TIMESTAMPTZ | default `NOW()` |
| `updated_at` | TIMESTAMPTZ | default `NOW()` |

Indexes: `idx_sct_inst_status(status)`, `idx_sct_inst_debtor(debtor_account_id)`, `idx_sct_inst_created(created_at DESC)`, and a partial `idx_sct_inst_timeout(execution_timeout_at) WHERE status = 'PROCESSING'` (powers the execution watchdog / `findTimedOut`).

### `sct_inst_outbox` (V2)

Transactional outbox for at-least-once event publishing.

| Column | Type | Notes |
|---|---|---|
| `id` | BIGSERIAL PK | |
| `event_id` | UUID UNIQUE | dedup key |
| `aggregate_id` | UUID | the `payment_id` |
| `event_type` | VARCHAR(128) | e.g. `SctInstPaymentSubmitted` |
| `payload` | TEXT | serialized event JSON |
| `status` | VARCHAR(16) | NEW / SENT / FAILED |
| `attempt_count` | INTEGER | retry counter |
| `sent_at` | TIMESTAMPTZ | nullable |
| `last_error` | TEXT | nullable |
| `created_at` / `updated_at` | TIMESTAMPTZ | default `NOW()` |

Indexes: `idx_sct_inst_outbox_status_created_at(status, created_at ASC)` (dispatch order), `idx_sct_inst_outbox_aggregate_id(aggregate_id)`.

### Sequence (V3)

`CREATE SEQUENCE IF NOT EXISTS sct_inst_outbox_seq INCREMENT BY 50` — Hibernate Reactive + Panache allocate ids from a `<table>_seq` (allocationSize 50), which BIGSERIAL alone does not provide. Without this, inserts fail at runtime with `relation "sct_inst_outbox_seq" does not exist`. Repo convention: unquoted, lowercase, `INCREMENT BY 50`.

## Flyway migrations

| Version | File | Purpose | Rollback note |
|---|---|---|---|
| V1 | `V1__create_sct_inst_payments.sql` | payments table + 4 indexes | `DROP TABLE sct_inst_payments;` |
| V2 | `V2__create_sct_inst_outbox.sql` | outbox table + 2 indexes | `DROP TABLE sct_inst_outbox;` |
| V3 | `V3__hibernate_sequences.sql` | `sct_inst_outbox_seq` | `DROP SEQUENCE sct_inst_outbox_seq;` (stated in the migration) |

`flyway.migrate-at-start = true` with 10 connect retries (2 s interval). **Never rewrite a migration after it is applied to a live DB** (checksum mismatch → startup fail; repo gotcha).

## PII inventory

| Field | Category | Handling |
|---|---|---|
| `debtor_iban`, `creditor_iban` | account identifiers (PII) | stored in-clear; masked in logs via libs PII masking; never logged raw |
| `debtor_name`, `creditor_name` | personal names (PII) | sent synchronously to sanctions-service for screening; included in the AML case `customerReference` on a hold/reject |
| `remittance_info` | free text (may carry PII) | stored as supplied |
| `amount`, `currency` | financial | — |

## Retention

- **Policy:** 7 years (governance.yaml `retentionPolicy`). `evidenceExported: true`.
- Payment records are retained for the regulatory period; AML/sanctions-related records follow the AML retention regime described in [06 — Compliance](./06-compliance.md). No automated GDPR erasure on this data (AML obligation overrides).
