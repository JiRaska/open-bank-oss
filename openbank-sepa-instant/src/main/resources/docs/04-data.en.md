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

### `sct_inst_outbox` — restored by the #12181 source candidate (V5)

The unused V2 outbox table and sequence were dropped in V4 (issue #5127). The additive V5
migration recreates them for the candidate's transactional event path. Event-producing payment
transitions insert a `PENDING` row in the same transaction as the payment change; the relay claims
it, retries a failed send, and marks it `SENT` or eventually `DEAD`. The row retains the existing
four-field Kafka payload plus delivery state (`event_id`, `aggregate_id`, `status`, attempts,
timestamps and error). A committed V5 migration in this branch does not prove it has run in any
deployed database. Check the deployed revision and Flyway history before relying on the table.

## Flyway migrations

| Version | File | Purpose | Rollback note |
|---|---|---|---|
| V1 | `V1__create_sct_inst_payments.sql` | payments table + 4 indexes | `DROP TABLE sct_inst_payments;` |
| V2 | `V2__create_sct_inst_outbox.sql` | outbox table + 2 indexes (removed, see V4) | `DROP TABLE sct_inst_outbox;` |
| V3 | `V3__hibernate_sequences.sql` | `sct_inst_outbox_seq` (removed, see V4) | `DROP SEQUENCE sct_inst_outbox_seq;` (stated in the migration) |
| V4 | `V4__drop_sct_inst_outbox.sql` | drops the vestigial `sct_inst_outbox` table + `sct_inst_outbox_seq` sequence left behind by PR #1364 | recreates the V2/V3 table + sequence (stated in the migration) |
| V5 | `V5__restore_sct_inst_outbox_v2.sql` | restores the durable event table, sequence and claim indexes | stop writers; retain a compatible relay and schema until recoverable rows have approved dispositions (migration note) |

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
