# Data

## Datastore

- **Engine:** PostgreSQL 16, reactive PG client + JDBC (Flyway).
- **Database:** `openbank_clearing` (`quarkus.datasource.reactive.url`).
- **Logical schema (governance):** `clearing_schema` (owned), dependent schema `transactions_schema` (per `governance.yaml`).
- **Schema generation:** `hibernate-orm.database.generation = none` — the schema is owned entirely by Flyway. `flyway.migrate-at-start = true`, `validate-on-migrate = false`.
- **Data classification:** `confidential`. **Data domain:** `payments`. **Lineage role:** both (consumer + producer).

## Flyway migrations

| Migration | Purpose |
|---|---|
| `V1__init_clearing.sql` | enums + core tables: `clearing_batches`, `clearing_items`, `settlement_positions`, plus indexes |
| `V2__create_clearing_outbox.sql` | transactional outbox table `clearing_outbox` + indexes |
| `V3__hibernate_sequences.sql` | `clearing_outbox_seq` (INCREMENT BY 50) — required because Hibernate Reactive allocates ids from `<table>_seq` while `generation=none` |
| `V4__amount_check_constraints.sql` | positive-amount CHECK constraints on items and batch totals |

> **Migration discipline (CLAUDE.md):** never edit an applied migration. `V3` exists precisely because a missing `<table>_seq` would fail every INSERT at runtime — its rollback note is `DROP SEQUENCE clearing_outbox_seq;`.

## Tables

### `clearing_batches`
A settlement cycle for one rail.

| Column | Type | Notes |
|---|---|---|
| `id` | UUID PK | `gen_random_uuid()` |
| `batch_reference` | VARCHAR(64) UNIQUE | cycle reference |
| `rail` | `payment_rail` enum | SEPA_SCT / SEPA_SCT_INST / SWIFT / DOMESTIC / INTERNAL |
| `settlement_type` | `settlement_type` enum | GROSS / NET / DEFERRED_NET, default NET |
| `status` | `clearing_status` enum | default PENDING |
| `total_debit` / `total_credit` / `net_position` | NUMERIC(20,4) | CHECK `total_debit >= 0`, `total_credit >= 0` (V4) |
| `currency` | CHAR(3) | default EUR |
| `item_count` | INT | default 0 |
| `cycle_id` | VARCHAR(32) | nullable |
| `settlement_date` | DATE | nullable |
| `settled_at` | TIMESTAMPTZ | nullable |
| `created_at` / `updated_at` | TIMESTAMPTZ | default NOW() |

Indexes: `idx_clearing_batches_status`, `idx_clearing_batches_cycle`.

### `clearing_items`
A single payment in clearing.

| Column | Type | Notes |
|---|---|---|
| `id` | UUID PK | `gen_random_uuid()` |
| `batch_id` | UUID FK → `clearing_batches(id)` | placeholder until cycle assignment |
| `payment_id` | UUID | upstream payment id |
| `payment_reference` | VARCHAR(64) | |
| `debtor_iban` / `creditor_iban` | VARCHAR(34) | **PII** |
| `debtor_bic` / `creditor_bic` | VARCHAR(11) | nullable |
| `amount` | NUMERIC(20,4) | CHECK `amount > 0` (V4) |
| `currency` | CHAR(3) | default EUR |
| `status` | `clearing_status` enum | default PENDING |
| `value_date` | DATE | nullable |
| `end_to_end_id` | VARCHAR(35) | nullable |
| `remittance_info` | VARCHAR(140) | nullable — free text, may contain PII |
| `error_code` / `error_message` | VARCHAR(16)/(256) | nullable |
| `created_at` / `updated_at` | TIMESTAMPTZ | default NOW() |

Indexes: `idx_clearing_items_batch`, `idx_clearing_items_payment`, `idx_clearing_items_status`.

### `settlement_positions`
Per-participant net position in a cycle.

| Column | Type | Notes |
|---|---|---|
| `id` | UUID PK | `gen_random_uuid()` |
| `participant_bic` | VARCHAR(11) | |
| `currency` | CHAR(3) | default EUR |
| `cycle_id` | VARCHAR(32) | |
| `gross_debit` / `gross_credit` / `net_position` | NUMERIC(20,4) | default 0 |
| `settled` | BOOLEAN | default FALSE |
| `settled_at` | TIMESTAMPTZ | nullable |
| `created_at` | TIMESTAMPTZ | default NOW() |
| — | UNIQUE | `(participant_bic, currency, cycle_id)` |

Index: `idx_settlement_positions_cycle`.

### `clearing_outbox`
Transactional outbox drained to Kafka.

| Column | Type | Notes |
|---|---|---|
| `id` | BIGSERIAL PK | |
| `event_id` | UUID UNIQUE | |
| `aggregate_id` | UUID | batch/item id |
| `event_type` | VARCHAR(128) | |
| `payload` | TEXT | serialized event |
| `status` | VARCHAR(16) | PENDING / SENT / FAILED |
| `attempt_count` | INTEGER | default 0 |
| `sent_at` / `last_error` | TIMESTAMPTZ / TEXT | nullable |
| `created_at` / `updated_at` | TIMESTAMPTZ | default NOW() |

Indexes: `idx_clearing_outbox_status_created_at`, `idx_clearing_outbox_aggregate_id`. Sequence `clearing_outbox_seq` (V3).

## PII inventory

| Field | Table | Classification | Handling |
|---|---|---|---|
| `debtor_iban`, `creditor_iban` | `clearing_items` | PII (account identifiers) | confidential; mask in logs |
| `remittance_info` | `clearing_items` | potentially PII (free text) | confidential |
| `debtor_bic`, `creditor_bic` | `clearing_items` | low — institution identifiers | — |
| `participant_bic` | `settlement_positions` | low — institution identifier | — |

The data here is **transaction/payment** data, not customer-master data — IBANs and remittance text are the sensitive elements.

## Retention

- **Policy (governance.yaml):** `retentionPolicy: 7 years`.
- Rationale: payment/settlement records fall under AML and accounting record-keeping; clearing items and batches are retained for the statutory period (see [06 — Compliance](./06-compliance.md)).
- **Outbox rows** are operational, not records of account — they may be pruned after successful delivery (status SENT) per platform outbox housekeeping. (No explicit purge migration present yet — TBD.)
