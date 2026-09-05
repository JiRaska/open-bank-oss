# Data

## Datastore

- **Engine:** PostgreSQL 16, accessed via Hibernate Reactive (Panache) + reactive PG client.
- **Database:** `openbank_swift` (reactive URL `postgresql://localhost:5432/openbank_swift`).
- **Logical schema name (declared):** `swift_schema` (per `governance.yaml`). Flyway DDL is written with unqualified table names, so tables physically land in the default schema of the `openbank_swift` database.
- **Schema generation:** `none` — the schema is owned entirely by Flyway migrations; Hibernate never generates DDL. Naming strategy: `CamelCaseToUnderscoresNamingStrategy`.

## Flyway migrations

| Migration | Purpose |
|---|---|
| `V1__create_swift.sql` | `swift_messages` table + indexes on status / sender_bic / receiver_bic |
| `V2__create_swift_outbox.sql` | `swift_outbox` table + indexes on (status, created_at) and aggregate_id |
| `V3__hibernate_sequences.sql` | `swift_outbox_seq` (INCREMENT BY 50) — required because Panache allocates ids from `<table>_seq` while DDL used `BIGSERIAL`; without it every outbox INSERT fails at runtime |

`migrate-at-start: true`, `validate-on-migrate: false`, connect-retries 10 × 2s. **Never rewrite an applied migration** — a checksum mismatch crashes startup (use `QUARKUS_FLYWAY_REPAIR_AT_START=true` to recover, then remove).

## Table — `swift_messages`

| Column | Type | Notes |
|---|---|---|
| `id` | `UUID` | PK |
| `idempotency_key` | `VARCHAR(255)` | `NOT NULL UNIQUE` — idempotency dedup |
| `message_type` | `VARCHAR(10)` | MT103 / MT202 / MT900 / MT910 / MT940 / MT950 / MT199 |
| `sender_bic` | `VARCHAR(11)` | **PII-adjacent** (institution routing) |
| `receiver_bic` | `VARCHAR(11)` | institution routing |
| `transaction_reference` | `VARCHAR(16)` | SWIFT field 20 |
| `related_reference` | `VARCHAR(16)` | field 21, nullable |
| `value_date` | `CHAR(8)` | YYYYMMDD |
| `currency` | `CHAR(3)` | ISO 4217 |
| `amount_minor_units` | `BIGINT` | **financial amount** |
| `ordering_customer_account` | `VARCHAR(34)` | **PII** — IBAN/account, nullable |
| `ordering_customer_name` | `VARCHAR(140)` | **PII** — natural-person name, nullable |
| `beneficiary_account` | `VARCHAR(34)` | **PII** — IBAN/account |
| `beneficiary_name` | `VARCHAR(140)` | **PII** — natural-person name |
| `remittance_info` | `VARCHAR(140)` | **PII-risk** — free text (field 70), nullable |
| `charge_code` | `CHAR(3)` | OUR/SHA/BEN, default `SHA` |
| `priority` | `VARCHAR(10)` | default `NORMAL` |
| `status` | `VARCHAR(20)` | default `PENDING` |
| `raw_mt` | `TEXT` | raw SWIFT MT message text, nullable |
| `ack_received_at` | `TIMESTAMPTZ` | nullable |
| `rejection_reason` | `TEXT` | nullable |
| `created_at` | `TIMESTAMPTZ` | default `NOW()` |
| `updated_at` | `TIMESTAMPTZ` | default `NOW()` |

Indexes: `idx_swift_status(status)`, `idx_swift_sender(sender_bic)`, `idx_swift_receiver(receiver_bic)`.

## Table — `swift_outbox`

Transactional outbox row (`SwiftOutboxEntity`, mapped to `swift_outbox`):

| Column | Type | Notes |
|---|---|---|
| `id` | `BIGSERIAL` | PK (ids allocated via `swift_outbox_seq`) |
| `event_id` | `UUID` | `NOT NULL UNIQUE` |
| `aggregate_id` | `UUID` | the `swift_messages.id` the event belongs to |
| `event_type` | `VARCHAR(128)` | domain event type label |
| `payload` | `TEXT` | serialized event payload (published verbatim to Kafka) |
| `status` | `VARCHAR(16)` | `PENDING` / `SENT` / `FAILED` (`SwiftOutboxStatus`) |
| `attempt_count` | `INTEGER` | default 0; incremented on failure |
| `sent_at` | `TIMESTAMPTZ` | nullable |
| `last_error` | `TEXT` | last dispatch error, nullable |
| `created_at` / `updated_at` | `TIMESTAMPTZ` | default `NOW()` |

Indexes: `idx_swift_outbox_status_created_at(status, created_at ASC)` (drain order), `idx_swift_outbox_aggregate_id(aggregate_id)`.

## PII fields

| Field | Why it is PII | Handling |
|---|---|---|
| `ordering_customer_name`, `beneficiary_name` | natural-person names | confidential; minimise in logs |
| `ordering_customer_account`, `beneficiary_account` | IBAN/account numbers | confidential; mask in logs |
| `remittance_info` | free-text may contain personal data | confidential |
| `raw_mt` | full MT message embeds all of the above | confidential at rest |

`dataClassification: confidential` (per `governance.yaml`).

## Retention

`retentionPolicy: 10 years` (`governance.yaml`) — aligned with AML/payment record-keeping (AMLD/CNB), which overrides GDPR erasure for completed wire instructions. See [06 — Compliance](./06-compliance.md). No automated purge job is implemented in this service code (TBD — retention is a policy declaration; enforcement is a platform/follow-up concern).

## Event payloads

Events are drained from `swift_outbox` to Kafka topic `openbank.payments.swift.event` (channel `swift-events-out`, String key + String value). The `payload` column holds the serialized event; `event_type` carries the type label. Every status transition writes its outbox row in the SAME transaction as the state change (`SwiftRepository.saveWithOutbox`): the scheme verdict and the settlement, and — since #8718 — the operator acknowledgement and rejection, which previously changed the status and published nothing. The `event_type` is always `swift.message.status-changed`, so the transition is read from the payload's `status` key; the payload shape is declared as `SwiftMessageEventPayload` in [`docs/asyncapi/openbank-events.yaml`](../../../../../docs/asyncapi/openbank-events.yaml). Event schemas must be versioned backward-compatibly.
