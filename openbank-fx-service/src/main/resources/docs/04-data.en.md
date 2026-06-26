# Data

## Datastore

- **PostgreSQL 16**, database `openbank_fx` (reactive PG client + JDBC for Flyway).
- Logical schema name (governance): `fx_schema` (`governance.yaml`). Tables are created unqualified by the migrations below.
- `hibernate-orm.database.generation = none` — the schema is owned by Flyway, never by Hibernate.

## Tables

### `fx_rates` (V1)

| Column | Type | Notes |
|---|---|---|
| `id` | `UUID` | PK |
| `base_currency` | `CHAR(3)` | ISO 4217 |
| `quote_currency` | `CHAR(3)` | ISO 4217 |
| `bid_rate` | `NUMERIC(18,8)` | |
| `ask_rate` | `NUMERIC(18,8)` | applied to conversions |
| `rate_type` | `VARCHAR(20)` | SPOT / FORWARD / INDICATIVE / INTERBANK (default `SPOT`) |
| `source` | `VARCHAR(20)` | ECB / REUTERS / BLOOMBERG / INTERNAL / CNB (default `ECB`) |
| `valid_from` / `valid_to` | `TIMESTAMPTZ` | validity window; conversions check `isValid()` |
| `created_at` | `TIMESTAMPTZ` | default `NOW()` |

Index `idx_fx_rates_pair (base_currency, quote_currency, rate_type, valid_to)`. Seeded with ECB reference rates for EUR/USD/GBP/CHF vs CZK (V1).

### `fx_conversions` (V1)

| Column | Type | Notes |
|---|---|---|
| `id` | `UUID` | PK |
| `idempotency_key` | `VARCHAR(255)` | **UNIQUE** — the idempotency guard |
| `party_id` | `UUID` | converting party (PII linkage) |
| `account_id` | `UUID` | nullable |
| `from_currency` / `to_currency` | `CHAR(3)` | |
| `from_amount_minor_units` | `BIGINT` | |
| `to_amount_minor_units` | `BIGINT` | |
| `applied_rate` | `NUMERIC(18,8)` | pinned at execution (`= ask_rate`) |
| `fee_minor_units` | `BIGINT` | default 0; 0.5% of the source amount |
| `rate_id` | `UUID` | **FK → `fx_rates(id)`** — pins the rate used |
| `status` | `VARCHAR(20)` | PENDING / SETTLED / FAILED / REVERSED (default `SETTLED`) |
| `created_at` | `TIMESTAMPTZ` | default `NOW()` |
| `settled_at` | `TIMESTAMPTZ` | null until settled |

Index `idx_fx_conv_party (party_id)`.

### `fx_outbox` (V2)

Transactional outbox for domain events (see [02 — Architecture](./02-architecture.md)).

| Column | Type | Notes |
|---|---|---|
| `id` | `BIGSERIAL` | PK |
| `event_id` | `UUID` | **UNIQUE** |
| `aggregate_id` | `UUID` | the conversion id |
| `event_type` | `VARCHAR(128)` | e.g. `FxConversionExecuted` |
| `payload` | `TEXT` | serialized event |
| `status` | `VARCHAR(16)` | PENDING / SENT / FAILED |
| `attempt_count` | `INTEGER` | default 0 |
| `sent_at` | `TIMESTAMPTZ` | |
| `last_error` | `TEXT` | last publish failure |
| `created_at` / `updated_at` | `TIMESTAMPTZ` | default `NOW()` |

Indexes `idx_fx_outbox_status_created_at (status, created_at ASC)`, `idx_fx_outbox_aggregate_id (aggregate_id)`.

## Migrations (Flyway)

| Version | File | What |
|---|---|---|
| V1 | `V1__create_fx.sql` | `fx_rates`, `fx_conversions` + ECB seed rates |
| V2 | `V2__create_fx_outbox.sql` | `fx_outbox` + indexes |
| V3 | `V3__hibernate_sequences.sql` | `fx_outbox_seq` (INCREMENT BY 50) for Panache id allocation |

`migrate-at-start = true`, `validate-on-migrate = false`. **Never edit an applied migration** (checksum mismatch → startup fail; use `QUARKUS_FLYWAY_REPAIR_AT_START=true` as the recovery lever).

> **V3 rationale:** Hibernate Reactive + `PanacheEntity` allocate ids from a sequence `<table>_seq` (allocationSize 50). The `BIGSERIAL` PK only creates `<table>_id_seq`, and the schema is `generation:none`, so inserts would fail with *relation "fx_outbox_seq" does not exist*. V3 adds it. `HibernateSequenceGuardTest` guards against the regression. Rollback: `DROP SEQUENCE fx_outbox_seq;`.

## PII & classification

`governance.yaml`: `dataClassification: confidential`, `retentionPolicy: 5 years`.

| Field | Class | Handling |
|---|---|---|
| `party_id`, `account_id` | pseudonymous identifiers (PII linkage) | stored as UUIDs, not direct identifiers |
| `partyName` (request only) | **PII (name)** | sent to `sanctions-service` for screening; **not persisted** in `fx_conversions` — only in the AML case (`aml-service`) and screening audit |
| conversion amounts, rates, currencies | financial / confidential | retained per policy |

The converting party's **name is not stored** in this service's tables — it is screened in-flight and persisted only by `aml-service` when a case is opened. This minimises PII at rest here.

## Retention

| Data | Retention | Basis |
|---|---|---|
| `fx_conversions` | 5 years (`governance.yaml`); AML evidence may extend to 10 years where a case is opened | AML record-keeping (AMLD); `governance.yaml` |
| `fx_rates` (incl. ČNB fixing) | retained as long as referenced by conversions / for audit | rate provenance for dispute defence |
| `fx_outbox` | transient — pruned after `SENT` (operational) | not a system of record |

## Lineage (`governance.yaml`)

- `dataLineageRole: both`.
- **Downstream:** `transaction-service` consumes rates (`relationType: api`).
- Owned schema: `fx_schema`; dependent schema: `transactions_schema`.
