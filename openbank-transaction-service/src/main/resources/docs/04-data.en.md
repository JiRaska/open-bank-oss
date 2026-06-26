# Data

## Datastore

- **Engine:** PostgreSQL (reactive PG client at runtime; JDBC for Flyway).
- **Database:** `openbank_transactions` (local dev URL `…/openbank_transactions`).
- **Owned logical schema:** `transactions_schema` (per `governance.yaml`); dependent schemas read via API: `accounts_schema`, `ledger_schema`.
- **Migrations:** Flyway, `migrate-at-start: true`, location `db/migration`, V1..V7.

## Tables

### `transactions` (range-partitioned by `booking_date`)

The core aggregate. Partitioned `PARTITION BY RANGE (booking_date)` with yearly partitions (`transactions_2025`, `transactions_2026`) plus a `transactions_default` catch-all.

Primary key `(id, booking_date)` (the partition key must be part of the PK). Key columns:

| Column | Type | Notes |
|---|---|---|
| `id` | UUID | `gen_random_uuid()` |
| `reference_number` | VARCHAR(50) | unique per booking date |
| `type` | VARCHAR(20) | CHECK ∈ DEBIT/CREDIT/TRANSFER/FEE/INTEREST/REVERSAL/ADJUSTMENT |
| `source_account_id` / `target_account_id` | UUID? | |
| `amount` | NUMERIC(20,6) | CHECK `> 0` |
| `currency_code` | CHAR(3) | ISO 4217 |
| `fx_rate` | NUMERIC(20,10) | null when no FX |
| `base_amount` / `base_currency_code` | NUMERIC(20,6) / CHAR(3) | settlement (booking) leg |
| `status` | VARCHAR(20) | CHECK ∈ PENDING/PROCESSING/COMPLETED/FAILED/REVERSED |
| `value_date` / `booking_date` | DATE | |
| `initiated_at` / `completed_at` / `failed_at` | TIMESTAMPTZ | |
| `failure_reason` | VARCHAR(500) | |
| `idempotency_key` | VARCHAR(100) | unique per booking date |
| `version` | BIGINT | optimistic lock |

Uniqueness: `uq_transactions_reference (reference_number, booking_date)`, `uq_transactions_idempotency (idempotency_key, booking_date)`.

**Compliance fields (V2):** `actor_id`, `actor_type`, `channel` (API/BRANCH/ATM/MOBILE/INTERNET), `ip_address`, `correlation_id`, `purpose_code` (ISO 20022), `regulatory_reporting_code` (CNB cross-border), `aml_screened` + `aml_screened_at`, `reversal_of`.

**BIAN / ISO 20022 search fields (V3):** `source_iban` / `target_iban` (ISO 13616), `source_bban` / `target_bban` (Czech format), `counterparty_name`, `counterparty_bank_bic`, `remittance_info`, `end_to_end_id`, `transaction_code`, `bank_transaction_code`, `proprietary_code`, `fee_amount` / `fee_currency`, `exchange_rate_type`, `instructed_amount` / `instructed_currency`, `batch_id`, `mandate_id`, `creditor_scheme_id`, `category_purpose`, `local_instrument`, `clearing_system_ref`, `settlement_date`, `is_reversal`, `is_fee_transaction`, `technical_account_id`.

Indexes cover account, status, dates, actor, channel, correlation, the unscreened-AML partial index, and every search field (IBAN/BBAN/counterparty/end-to-end/amount/fee/technical account).

### `transaction_outbox`

Transactional outbox for at-least-once event publishing.

| Column | Type | Notes |
|---|---|---|
| `id` | BIGSERIAL | PK (sequence `transaction_outbox_seq`, increment 50 — V6) |
| `event_id` | UUID | unique |
| `aggregate_id` | UUID | transaction id |
| `event_type` | VARCHAR(128) | `…transaction.initiated` / `.completed` / `.failed` |
| `payload` | TEXT | serialized event |
| `status` | VARCHAR(16) | PENDING → SENT / FAILED |
| `attempt_count` | INTEGER | |
| `sent_at` / `last_error` | TIMESTAMPTZ / TEXT | |
| `created_at` / `updated_at` | TIMESTAMPTZ | |

Indexes: `(status, created_at ASC)` for the dispatcher poll, `(aggregate_id)`.

### `payment_sagas`

Per-transaction saga orchestration state (V5, CHECK widened in V7).

| Column | Type | Notes |
|---|---|---|
| `id` | UUID | PK |
| `transaction_id` | UUID | unique (1 saga per transaction) |
| `state` | VARCHAR(32) | CHECK ∈ STARTED/PAYMENT_INITIATED/**FUNDS_RESERVED**/LEDGER_POSTING/**FUNDS_CAPTURED**/COMPLETED/COMPENSATING/COMPENSATED/FAILED |
| `idempotency_key` | VARCHAR(255) | unique |
| `failure_reason` / `compensation_reason` | TEXT | |
| `created_at` / `updated_at` | TIMESTAMPTZ | |
| `version` | BIGINT | |

Partial index `idx_payment_sagas_state … WHERE state NOT IN (COMPLETED, COMPENSATED, FAILED)` to find in-flight sagas cheaply.

## Migration list

| Version | What |
|---|---|
| V1 | `transactions` (partitioned) + base indexes; `uuid-ossp`, `pgcrypto` extensions |
| V2 | compliance / audit-trail columns (actor, channel, correlation, AML, purpose, reversal) |
| V3 | BIAN / ISO 20022 banking search columns + counterparty + IBAN/BBAN |
| V4 | `transaction_outbox` |
| V5 | `payment_sagas` |
| V6 | `transaction_outbox_seq` (Hibernate sequence, increment 50) |
| V7 | widen `chk_payment_sagas_state` to include FUNDS_RESERVED / FUNDS_CAPTURED (money-path fix; rollback note in the migration header) |

**Rule:** never edit an applied migration (checksum mismatch crashes Flyway). V7 deliberately leaves the released V5 immutable and drops/recreates the CHECK. Its rollback note is in the migration file.

## PII & classification

`governance.yaml`: `dataClassification: confidential`, `retentionPolicy: 7 years`, `evidenceExported: true`.

PII / sensitive fields:

| Field | Sensitivity |
|---|---|
| `source_iban` / `target_iban` / `source_bban` / `target_bban` | account identifiers — PII (GDPR) |
| `counterparty_name` | personal data of the counterparty |
| `ip_address` | personal data (PSD2/EBA audit) |
| `actor_id` / `correlation_id` | pseudonymous identifiers |
| `amount` / `base_amount` / `remittance_info` | financial data, confidential |

IBANs and counterparty data should be masked in logs (`libs.security.PiiMask`). See [06 — Compliance](./06-compliance.md) for retention and lawful basis.

## Retention

7-year retention (`governance.yaml`), aligned with CNB books-of-account and AMLD record-keeping. Partitioning by `booking_date` makes year-granularity retention / archival a partition-detach operation rather than a bulk delete.
