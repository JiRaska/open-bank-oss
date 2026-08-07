# Data

## Datastore

- **Engine:** PostgreSQL (reactive `pg-client` for the app path, JDBC for Flyway).
- **Database:** `openbank_audit`.
- **Schema:** tables live in the `public` schema (grants are issued `IN SCHEMA public`).
- **Hibernate:** `database.generation = none` — the schema is owned by Flyway, never auto-generated.

> Note: the curatorial `governance.yaml` declares `primaryDatastore: PostgreSQL` / `databaseName: openbank_audit`, matching the running code.

## Flyway migrations

| Migration | Purpose |
|---|---|
| `V1__create_audit.sql` | Creates `audit_entries` (BIGSERIAL `id` PK, unique `entry_id` UUID, event/aggregate/actor/payload columns) and lookup indexes on `aggregate_id`, `event_type`, `occurred_at DESC`, partial index on `actor_id`. Grants on `public`. |
| `V2__compliance_fields.sql` | EBA ICT + GDPR enrichment: adds `session_id`, `user_agent`, `ip_address`, `data_sensitivity` (default `INTERNAL`), `retention_until`, `is_security_event` (default `FALSE`), `risk_score`. Adds security/session/retention indexes. **Installs immutability:** `RULE no_update_audit DO INSTEAD NOTHING` and `RULE no_delete_audit DO INSTEAD NOTHING`. Backfills `retention_until` and installs `trg_audit_retention` BEFORE INSERT trigger (`occurred_at + 10 years`). |
| `V3__create_audit_outbox.sql` | Creates `audit_outbox` (BIGSERIAL `id` PK, unique `event_id` UUID, `aggregate_id`, `event_type`, `payload`, `status`, `attempt_count`, `sent_at`, `last_error`, timestamps) plus `(status, created_at)` and `aggregate_id` indexes. |
| `V4__hibernate_sequences.sql` | Creates `audit_entries_seq` and `audit_outbox_seq` (`INCREMENT BY 50`) required by PanacheEntity id allocation. Rollback: `DROP SEQUENCE audit_entries_seq, audit_outbox_seq;`. |

## Tables

### `audit_entries` (append-only, immutable)

| Column | Type | Notes |
|---|---|---|
| `id` | BIGSERIAL PK | Surrogate; ids allocated from `audit_entries_seq` |
| `entry_id` | UUID, unique, not null | Logical entry id (exposed in API) |
| `event_type` | VARCHAR(100) | Producer event name |
| `aggregate_type` | VARCHAR(50) | ACCOUNT / PARTY / TRANSACTION / CONSENT / KYC_CASE / UNKNOWN |
| `aggregate_id` | VARCHAR(100) | Indexed; primary query key |
| `actor_id` | VARCHAR(100), null | Who triggered the event |
| `actor_type` | VARCHAR(50), null | Actor classification |
| `payload` | TEXT, not null | Original event JSON, verbatim |
| `source_service` | VARCHAR(100) | Originating service |
| `correlation_id` | VARCHAR(100), null | Trace correlation |
| `occurred_at` | TIMESTAMPTZ | Business time — real only when `occurred_at_source = 'EVENT'` |
| `recorded_at` | TIMESTAMPTZ, default NOW() | Ingest time |
| `occurred_at_source` | VARCHAR(8), null | (V11) `EVENT` = producer sent `occurredAt`; `INGEST` = it did not, so `occurred_at` is ingest time (an upper bound); NULL = pre-V11 row, treat as `INGEST` |
| `session_id` | VARCHAR(100), null | (V2) |
| `user_agent` | VARCHAR(500), null | (V2) |
| `ip_address` | VARCHAR(45), null | (V2) — IPv4/IPv6 |
| `data_sensitivity` | VARCHAR(20), default INTERNAL | (V2) PUBLIC/INTERNAL/CONFIDENTIAL/RESTRICTED |
| `retention_until` | TIMESTAMPTZ | (V2) trigger-set to occurred_at + 10y |
| `is_security_event` | BOOLEAN, default FALSE | (V2) SIEM hint |
| `risk_score` | SMALLINT, null | (V2) |

**Immutability:** PostgreSQL `DO INSTEAD NOTHING` rules silently discard any `UPDATE`/`DELETE`. The trail is physically append-only; correction is by appending a new compensating entry, never by editing.

### `audit_outbox`

Transactional-outbox staging for re-emitting recorded events (`event_id`, `aggregate_id`, `event_type`, `payload`, `status` ∈ PENDING/SENT/FAILED, `attempt_count`, `sent_at`, `last_error`, `created_at`, `updated_at`). Drained by `AuditOutboxDispatcher`.

## PII & data classification

The audit trail stores the **verbatim payload** of every upstream event, so it transitively contains whatever PII the producers emit (account ids, IBANs, party ids, possibly transaction detail). Additional directly-personal fields captured at this layer:

| Field | Classification | Handling |
|---|---|---|
| `ip_address` | personal data (GDPR) | retained under the audit retention regime |
| `user_agent` | personal data (GDPR) | retained under the audit retention regime |
| `actor_id` / `session_id` | identifying | links action to a person |
| `payload` | mixed, up to RESTRICTED | classified per `data_sensitivity`; treat as the most sensitive field present |

See [06 — Compliance](./06-compliance.md) for the lawful-basis and erasure analysis (erasure is overridden by the AML/EBA retention obligation).

## Retention

- **10 years**, enforced two ways: a per-row `retention_until = occurred_at + INTERVAL '10 years'` set by the `trg_audit_retention` trigger, and the service property `openbank.gdpr.audit-retention-days: 3650`.
- Deletion before `retention_until` is not just policy — the DB-level delete rule blocks it entirely. Purge after expiry is an operational concern (a separate, audited maintenance job), not an ad-hoc `DELETE`.
