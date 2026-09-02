# Data

## Datastore

- **Engine:** PostgreSQL 16, accessed via Hibernate Reactive (Panache) over the reactive PG client.
- **Database:** `openbank_tpp_registry` (`governance.yaml` declares `databaseName: openbank_tpp_registry`, matching the runtime connection string; the tables live in its `public` schema).
- **Schema generation:** `none` — Flyway owns the schema; `migrate-at-start: true`.

## Tables

### `tpp_entries` (aggregate root)

| Column | Type | Notes |
|---|---|---|
| `id` | BIGSERIAL PK | surrogate key (Hibernate sequence `tpp_entries_seq`, INCREMENT 50) |
| `tpp_id` | VARCHAR(100) UNIQUE NOT NULL | EBA/CNB identifier, e.g. `CZ-CNB-123456` |
| `name` | VARCHAR(255) NOT NULL | legal/trade name of the TPP |
| `country_code` | CHAR(2) NOT NULL | ISO 3166-1 alpha-2 |
| `nca` | VARCHAR(20) NOT NULL | National Competent Authority (`CNB`, `BaFin`, …) |
| `roles` | VARCHAR(100) NOT NULL | comma-joined `TppRole` set (`AISP,PISP`) |
| `status` | VARCHAR(20) NOT NULL DEFAULT `ACTIVE` | column domain: ACTIVE / SUSPENDED / REVOKED / BLACKLISTED. Only ACTIVE and BLACKLISTED are ever written (#6489) |
| `qwac_subject_dn` | TEXT | eIDAS QWAC certificate Subject DN |
| `qseal_subject_dn` | TEXT | eIDAS QSeal certificate Subject DN |
| `qwac_expires_at` | DATE | QWAC expiry (checked in authorization) |
| `qseal_expires_at` | DATE | QSeal expiry |
| `registered_at` | TIMESTAMPTZ NOT NULL DEFAULT NOW() | |
| `updated_at` | TIMESTAMPTZ NOT NULL DEFAULT NOW() | |
| `blacklisted_at` | TIMESTAMPTZ | set on blacklist |
| `blacklist_reason` | TEXT | |

Indexes: `idx_tpp_entries_status(status)`, `idx_tpp_entries_country(country_code)`.

### `eba_sync_state`

| Column | Type | Notes |
|---|---|---|
| `id` | BIGSERIAL PK | sequence `eba_sync_state_seq` |
| `last_sync_at` | TIMESTAMPTZ | last sync attempt |
| `last_success_at` | TIMESTAMPTZ | last successful sync |
| `total_entries` | INT NOT NULL DEFAULT 0 | entries seen in the register |
| `error_message` | TEXT | last error / stub message |

Effectively a singleton row (the repository reads/updates the first row).

### `tpp_outbox`

| Column | Type | Notes |
|---|---|---|
| `id` | BIGSERIAL PK | sequence `tpp_outbox_seq` |
| `event_id` | UUID UNIQUE NOT NULL | dedup key for the dispatcher |
| `aggregate_id` | UUID NOT NULL | the TPP aggregate id |
| `event_type` | VARCHAR(128) NOT NULL | e.g. `TppRegistered` (no producers wired yet) |
| `payload` | TEXT NOT NULL | serialized event |
| `status` | VARCHAR(16) NOT NULL | PENDING / SENT / FAILED |
| `attempt_count` | INTEGER NOT NULL DEFAULT 0 | |
| `sent_at` | TIMESTAMPTZ | |
| `last_error` | TEXT | |
| `created_at` | TIMESTAMPTZ NOT NULL DEFAULT NOW() | |
| `updated_at` | TIMESTAMPTZ NOT NULL DEFAULT NOW() | |

Indexes: `idx_tpp_outbox_status_created_at(status, created_at ASC)` (drain order), `idx_tpp_outbox_aggregate_id`.

## Flyway migrations

| Version | File | What it does | Rollback |
|---|---|---|---|
| V1 | `V1__init.sql` | `tpp_entries`, `eba_sync_state`, indexes, 3 seed sandbox TPPs | `DROP TABLE tpp_entries, eba_sync_state;` |
| V3 | `V3__create_tpp_outbox.sql` | `tpp_outbox` + 2 indexes | `DROP TABLE tpp_outbox;` |
| V4 | `V4__hibernate_sequences.sql` | `*_seq` sequences (INCREMENT 50) required by Panache under `generation:none` | `DROP SEQUENCE eba_sync_state_seq, tpp_entries_seq, tpp_outbox_seq;` |
| V5 | `V5__tpp_outbox_claimed_at.sql` | `tpp_outbox.claimed_at` for the atomic `FOR UPDATE SKIP LOCKED` claim | `ALTER TABLE tpp_outbox DROP COLUMN claimed_at;` |
| V6 | `V6__tpp_entries_entry_uuid.sql` | `tpp_entries.entry_uuid` — the domain id, distinct from the internal BIGSERIAL PK (#2340) | `ALTER TABLE tpp_entries DROP COLUMN entry_uuid;` |
| V7 | `V7__tpp_entries_seq_past_seeded_rows.sql` | `setval` on `tpp_entries_seq` past V1's seeded ids — without it the first registration collides on `tpp_entries_pkey` and answers 500 (#4007) | `SELECT setval('tpp_entries_seq', 1, false);` |

> **Note:** there is no `V2` in the tree (migration history skips it). The V4 comment documents the cross-service pattern (same defect fixed for party V6 and notification V4/V5): `BIGSERIAL` alone only creates `<table>_id_seq`, but Panache expects `<table>_seq`.

### Seed data (V1)

Three sandbox/test TPPs are inserted for local/dev: `CZ-CNB-SANDBOX-001` (AISP,PISP), `CZ-CNB-TEST-AISP` (AISP), `CZ-CNB-TEST-PISP` (PISP) — all `ACTIVE`, country `CZ`, NCA `CNB`.

> These three take ids 1..3 from the implicit `tpp_entries_id_seq`, while Panache allocates from `tpp_entries_seq`, which V4 created starting at 1. Until V7 that made the FIRST registration through the API a guaranteed `duplicate key value violates unique constraint "tpp_entries_pkey"` → 500. Nobody had hit it: measured on the sandbox 2026-08-16, `tpp_entries_seq` read `last_value = 1, is_called = f` — never called, so no registration had ever been attempted in a deployed environment (#4007).

## PII & data classification

`governance.yaml` declares `dataClassification: internal`. The registry holds information about **legal entities (TPPs)**, not natural persons:

| Field | Sensitivity |
|---|---|
| `tpp_id`, `name`, `country_code`, `nca` | public/regulatory register data (mirrors EBA register) |
| `qwac_subject_dn`, `qseal_subject_dn` | corporate certificate identity — security-relevant, not personal PII |
| `roles`, `status`, `blacklist_reason` | operational/compliance state |

No customer PII (no party-id, IBAN, name of a natural person) is stored here. The blacklist reason should avoid embedding personal data.

## Retention

`governance.yaml: retentionPolicy: 5 years`. TPP registration and blacklist records are retained for **5 years**, aligning with PSD2 / record-keeping obligations for authorisation evidence. Entries are not hard-deleted on de-authorisation — the status transition to `BLACKLISTED` preserves the audit history. (`REVOKED` and `SUSPENDED` are declared in the enum but written by nothing today — see #6489.)

## Data lineage

`governance.yaml: dataLineageRole: producer`, `evidenceExported: false`. Upstream: `psd2-service` (api relation — validates against this registry). Owned schema: `tpp_schema`.
