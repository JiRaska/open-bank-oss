# Data

## Datastore

- **Engine:** PostgreSQL, accessed reactively (`quarkus-reactive-pg-client` + Hibernate Reactive / Panache).
- **Database:** `openbank_sdd` (reactive URL `postgresql://…/openbank_sdd`; the IT profile uses `openbank_sdd_it`).
- **Schema generation:** none — the schema is owned by Flyway (`migrate-at-start: true`).
- **Schema name:** the `V1` migration creates tables in the default schema of the `openbank_sdd` database (no explicit `SET search_path`). The per-service governance manifest (`governance.yaml`) *declares* `sdd_schema`; treat the declared name as the logical owner and the unqualified Flyway DDL as the physical reality until the two are reconciled.

## Flyway migrations

| Migration | Purpose | Rollback note |
|---|---|---|
| `V1__init_sdd.sql` | Create `sdd_mandate` (mandate vault) and `sdd_outbox` (transactional outbox) with their indexes. | `DROP TABLE sdd_outbox; DROP TABLE sdd_mandate;` — no data dependencies elsewhere. |

> Per the GitOps rules: **never edit a migration after it has been applied to a live DB** (checksum mismatch → startup fail). Add a new `V2…` instead.

## Table `sdd_mandate`

The mandate aggregate — system of record for the debtor's standing direct-debit authorisation.

| Column | Type | Notes / classification |
|---|---|---|
| `id` | `UUID` PK | mandate id |
| `account_id` | `UUID` NOT NULL | the debtor account; indexed (`ix_sdd_mandate_account`) — **PII (links to a customer)** |
| `debtor_iban` | `VARCHAR(34)` NOT NULL | debtor IBAN — **PII** |
| `creditor_identifier` | `VARCHAR(35)` NOT NULL | SEPA Creditor Identifier (CID); part of the natural key |
| `umr` | `VARCHAR(35)` NOT NULL | Unique Mandate Reference; part of the natural key |
| `scheme` | `VARCHAR(8)` NOT NULL | `CORE` / `B2B` |
| `sequence_type` | `VARCHAR(8)` NOT NULL | `OOFF` / `FRST` / `RCUR` / `FNAL` |
| `creditor_name` | `VARCHAR(140)` NOT NULL | creditor display name |
| `debtor_name` | `VARCHAR(140)` NOT NULL | debtor name — **PII** |
| `signature_date` | `DATE` NOT NULL | mandate signature date; idle-expiry anchor when there is no collection yet |
| `status` | `VARCHAR(24)` NOT NULL | `PENDING_CONFIRMATION` / `ACTIVE` / `SUSPENDED` / `CANCELLED` / `EXPIRED`; indexed (`ix_sdd_mandate_status`) |
| `b2b_confirmed` | `BOOLEAN` NOT NULL DEFAULT FALSE | set true when a B2B mandate is confirmed |
| `last_collection_date` | `DATE` NULL | drives idle-expiry; advances `FRST → RCUR` on stamp |
| `last_pre_notification_date` | `DATE` NULL | tracked (not enforced) creditor pre-notification |
| `created_at` | `TIMESTAMPTZ` NOT NULL | creation timestamp |
| `amendments` | `TEXT` NOT NULL DEFAULT `'[]'` | JSON array of `{field, oldValue, newValue, at}` — **may contain PII** (e.g. an amended IBAN) |

**Indexes:**

- `uq_sdd_mandate_reference` — **UNIQUE** on `(creditor_identifier, umr)`. This is the rulebook identity and the basis of registration idempotency.
- `ix_sdd_mandate_account` on `(account_id)` — list-by-account.
- `ix_sdd_mandate_status` on `(status)` — the idle-expiry / live-mandate sweep.

## Table `sdd_outbox`

Transactional outbox for `sdd.*` events (ADR-0003 / ADR-0050), written in the same transaction as the mandate change.

| Column | Type | Notes |
|---|---|---|
| `id` | `UUID` PK | row id |
| `event_id` | `UUID` NOT NULL UNIQUE | the idempotency id carried to consumers as `ce-id` (`uq_sdd_outbox_event`) |
| `aggregate_id` | `UUID` NOT NULL | mandate id; used as the Kafka partition key |
| `event_type` | `VARCHAR(64)` NOT NULL | e.g. `sdd.mandate.registered.v1`, `sdd.collection.authorised.v1` |
| `payload` | `TEXT` NOT NULL | JSON event body — **may contain PII** (IBAN on `collection.authorised`) |
| `status` | `VARCHAR(16)` NOT NULL | `PENDING` → `SENT`, or `FAILED` → `DEAD` (poison cap) |
| `attempt_count` | `INTEGER` NOT NULL DEFAULT 0 | publish attempts; `DEAD` at `MAX_ATTEMPTS` (10) |
| `sent_at` | `TIMESTAMPTZ` NULL | set on `SENT` |
| `last_error` | `TEXT` NULL | last publish error (truncated to 4000 chars) |
| `created_at` / `updated_at` | `TIMESTAMPTZ` NOT NULL | lifecycle timestamps |

**Index:** `ix_sdd_outbox_status` on `(status, created_at)` — the dispatcher's processable query (`status IN (PENDING, FAILED) ORDER BY created_at`).

## Events emitted

| Event type | Trigger |
|---|---|
| `sdd.mandate.registered.v1` | new mandate registered |
| `sdd.mandate.confirmed.v1` | B2B mandate confirmed |
| `sdd.mandate.suspended.v1` / `…resumed.v1` / `…cancelled.v1` | lifecycle transitions |
| `sdd.mandate.amended.v1` | field amendment recorded |
| `sdd.collection.authorised.v1` | collection ACCEPTed — carries `debtorIban`, `amount`, `currency`, `dueDate` for the downstream posting path |

## PII fields

The mandate row and the `collection.authorised` event carry personal data: `debtor_iban`, `debtor_name`, `account_id`, and any IBAN/name inside `amendments`. See [06 — Compliance](./06-compliance.md) for the GDPR lawful-basis and retention mapping.

## Retention

The service governance manifest declares a **7-year retention policy** (`governance.yaml: retentionPolicy: 7 years`), consistent with payments/AML record-keeping. The `V1` schema does not itself implement a purge job; retention is enforced operationally / by a downstream archival policy. There is no built-in erasure path (AML/PSD2 record-keeping obligations take precedence — see compliance).
