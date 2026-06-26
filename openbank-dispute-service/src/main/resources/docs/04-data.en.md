# Data

## Datastore

- **Engine:** PostgreSQL 16 (reactive `pg-client` for runtime, JDBC for Flyway).
- **Database:** `openbank_dispute`.
- **Schema:** tables are created in the connection's default schema (`public`). The `governance.yaml` manifest declares the logical schema name `disputes_schema` for catalog/lineage purposes; no migration sets an explicit `search_path`, so the physical schema is `public` (declared vs physical naming is a known discrepancy — TBD).
- **Generation:** `hibernate-orm.database.generation: none` — the schema is owned by Flyway, never by Hibernate DDL.

## Tables

### `disputes` (aggregate root)

| Column | Type | Notes |
|---|---|---|
| `id` | `UUID` PK | `gen_random_uuid()` |
| `reference` | `VARCHAR(32)` UNIQUE | `DSP-<epochMillis>` |
| `transaction_id` | `UUID` | disputed transaction (FK by value, no cross-DB constraint) |
| `account_id` | `UUID` | indexed |
| `party_id` | `UUID` | the disputing customer |
| `dispute_type` | enum `dispute_type` | |
| `status` | enum `dispute_status` | default `OPEN`, indexed |
| `resolution` | enum `dispute_resolution` | default `PENDING` |
| `amount` | `NUMERIC(20,4)` | `CHECK amount > 0` (V4) |
| `currency` | `CHAR(3)` | default `EUR` |
| `description` | `TEXT` | free text (potential PII) |
| `merchant_name` / `merchant_id` | `VARCHAR(256)` / `VARCHAR(64)` | |
| `transaction_date` | `DATE` | |
| `filing_date` | `DATE` | default `CURRENT_DATE`, indexed |
| `resolution_deadline` | `DATE` | filing + 45d SLA |
| `resolved_at` / `resolved_by` | `TIMESTAMPTZ` / `VARCHAR(64)` | |
| `chargeback_amount` | `NUMERIC(20,4)` | `CHECK IS NULL OR > 0` (V4) |
| `created_at` / `updated_at` | `TIMESTAMPTZ` | |

### `dispute_evidence`

`id` (PK), `dispute_id` (FK → `disputes`), `submitted_by`, `evidence_type`, `description` (TEXT, potential PII), `file_reference` (pointer to an external blob — no binary stored here), `submitted_at`.

### `dispute_timeline`

`id` (PK), `dispute_id` (FK → `disputes`), `event_type`, `description` (TEXT), `actor`, `created_at`. Append-only audit trail.

### `dispute_outbox`

Transactional outbox: `id` (BIGSERIAL), `event_id` (UUID UNIQUE), `aggregate_id`, `event_type`, `payload` (TEXT), `status`, `attempt_count`, `sent_at`, `last_error`, timestamps. Indexed on `(status, created_at)` and `aggregate_id`.

## Indexes

`idx_disputes_account`, `idx_disputes_transaction`, `idx_disputes_status`, `idx_disputes_filing_date`, `idx_evidence_dispute`, `idx_timeline_dispute`, `idx_dispute_outbox_status_created_at`, `idx_dispute_outbox_aggregate_id`.

## Flyway migrations

| Version | File | Purpose | Rollback |
|---|---|---|---|
| V1 | `V1__init_dispute.sql` | enums + `disputes`, `dispute_evidence`, `dispute_timeline`, indexes | DROP tables + types |
| V2 | `V2__create_dispute_outbox.sql` | `dispute_outbox` + indexes | DROP table |
| V3 | `V3__hibernate_sequences.sql` | `dispute_outbox_seq` (INCREMENT BY 50) for Panache id allocation | `DROP SEQUENCE dispute_outbox_seq;` |
| V4 | `V4__amount_check_constraints.sql` | positive-amount CHECK constraints | DROP constraints |

> V3 fixes the Hibernate-Reactive/Panache id-allocation defect: Panache allocates ids from `<table>_seq` (allocationSize 50) but `BIGSERIAL` only creates `<table>_id_seq`; without the explicit sequence, inserts into `dispute_outbox` fail with `relation "dispute_outbox_seq" does not exist`. **Never rewrite an applied migration** (CLAUDE.md) — use `QUARKUS_FLYWAY_REPAIR_AT_START` if a checksum drifts. Note `flyway.validate-on-migrate` is currently `false`.

## PII inventory

| Field | Classification | Handling |
|---|---|---|
| `party_id`, `account_id`, `transaction_id` | pseudonymous identifiers | UUID references, no direct PII |
| `description` (dispute & evidence) | potentially PII (free text) | operator-entered; treat as confidential |
| `merchant_name` / `merchant_id` | counterparty data | confidential |
| `file_reference` | pointer to external evidence | the blob lives outside this DB |
| `resolved_by` / `submitted_by` / `actor` | staff/actor identifiers | operational audit data |

Overall data classification per `governance.yaml`: **confidential**.

## Retention

- **Retention policy:** **7 years** (`governance.yaml: retentionPolicy`), consistent with consumer-protection / payment-services record-keeping.
- **Lineage role:** `both` (consumes and produces). Declared downstream: `card-issuance-service` (relation `blocks`).
- `evidenceExported: true` — dispute records form part of the regulatory evidence set.
