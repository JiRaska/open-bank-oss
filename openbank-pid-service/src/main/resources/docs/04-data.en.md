# Data

## Datastore

- **Engine:** PostgreSQL (reactive PG client + JDBC for Flyway), Hibernate Reactive / Panache.
- **Database:** `openbank_pid` (local dev URL `postgresql://localhost:5432/openbank_pid`). Tables are created in the default `public` schema by the migrations below. (The governance manifest labels the logical schema name `pid_schema` for catalog purposes.)
- **Schema management:** Flyway, `migrate-at-start=true`, `baseline-on-migrate=true`, `connect-retries=10`. Hibernate `database.generation=none` — Flyway is the single source of DDL truth.

## Tables

### `parties` (V1) — the aggregate root

| Column group | Columns | Notes |
|---|---|---|
| Identity | `id` (UUID PK), `party_type`, `status`, `version` | check constraints on type/status; `version` = optimistic lock |
| Core | `given_name`, `family_name`, `birthdate`, `birth_number_encrypted`, `gender`, `birthplace`, `nationalities` (TEXT[]) | **PII** — see below |
| Verification | `verification_source`, `verified_at` | BANKID / BRANCH_MANUAL / API_UPLOAD / ROB |
| Contact | `email`, `email_verified_at`, `phone`, `phone_verified_at`, `preferred_language`, `data_box_id` | **PII** |
| KYC/AML | `kyc_level`, `kyc_completed_at`, `kyc_expires_at`, `aml_risk_score`, `pep_flag`, `sanctions_flag`, `ubo_verified_at`, `last_aml_review_at` | check constraints on level/risk; partial indexes on `pep_flag`/`sanctions_flag` where TRUE |
| Address (embedded) | `permanent_address_*` (street, house_number, city, postal_code, country, ruian_code), `rob_synced_at` | **PII**; RUIAN code from ROB sync |
| Case (V3) | `case_id`, `case_type`, `case_status`, `case_last_actor`, `case_last_reason_code`, `case_last_transition_at`, `case_metadata` | PID verification case lifecycle; check constraints bound type/status/reason |
| Audit | `created_at`, `updated_at` | default `NOW()` |

Indexes: `family_name`, `birthdate`, `email`, `status`, `kyc_level`, partial `pep_flag`/`sanctions_flag`, `case_id`, partial `case_status`.

### `party_external_ids` (V1)

`id` (BIGSERIAL PK), `party_id` (FK → parties, `ON DELETE CASCADE`), `id_type`, `id_value`, `verified_at`. **Unique `(id_type, id_value)`** enforces one external identifier → one party (the dedup backbone). `id_type` check ∈ {KEYCLOAK_ID, BANKID_SUB, ROB_AIFO, ICO, PASSPORT_NUMBER, ID_CARD_NUMBER}. **`id_value` is PII** (national identifiers).

### `party_id_documents` (V1)

`id` (BIGSERIAL PK), `party_id` (FK, cascade), `doc_type` ∈ {NATIONAL_ID, PASSPORT, DRIVING_LICENSE, RESIDENCE_PERMIT}, `doc_number`, `issuing_country`, `issued_at`, `expires_at`. **`doc_number` is PII.**

### `party_relationships` (V1)

`id` (UUID PK), `party_id` (FK, cascade), `role` ∈ {CUSTOMER, EMPLOYEE, ADMIN, AGENT, GUARANTOR, AUTHORIZED_PERSON}, `status` ∈ {ACTIVE, SUSPENDED, TERMINATED}, `onboarded_at`, `onboarding_channel`, `terminated_at`, `termination_reason`. Unique `(party_id, role, status)` (deferrable) — one active instance of each role.

### `pid_outbox` (V2) — transactional outbox

`id` (BIGSERIAL PK), `event_id` (UUID, UNIQUE), `aggregate_id` (UUID), `event_type`, `payload` (TEXT), `status`, `attempt_count`, `sent_at`, `last_error`, `created_at`, `updated_at`. Indexes on `(status, created_at ASC)` (dispatcher poll) and `aggregate_id`.

## Flyway migrations

| Version | File | Change | Rollback note |
|---|---|---|---|
| V1 | `V1__init_pid.sql` | extensions `uuid-ossp`, `pgcrypto`; tables `parties`, `party_external_ids`, `party_id_documents`, `party_relationships` + indexes | `DROP TABLE` (reverse FK order); drop extensions |
| V2 | `V2__create_pid_outbox.sql` | `pid_outbox` + two indexes | `DROP TABLE pid_outbox` |
| V3 | `V3__add_pid_case_lifecycle.sql` | adds `case_*` columns + check constraints + 2 indexes to `parties` | `ALTER TABLE parties DROP COLUMN case_*`; drop the two indexes |
| V4 | `V4__hibernate_sequences.sql` | `CREATE SEQUENCE pid_outbox_seq INCREMENT BY 50` (Panache id allocation) | `DROP SEQUENCE pid_outbox_seq` |

> **Never rewrite an applied migration** (project rule — checksum mismatch crashes Flyway at start). New changes go in a new `Vn__*.sql`.

## PII inventory

| Field | Classification | Protection |
|---|---|---|
| `birth_number_encrypted` (rodné číslo) | special-category-adjacent national ID | **stored encrypted only**; `pgcrypto` available; never returned in `PartyResponse` |
| `given_name`, `family_name`, `birthdate`, `birthplace`, `gender`, `nationalities` | personal data | access-controlled (employee/admin roles); mask in logs |
| `email`, `phone`, `data_box_id` | contact PII | access-controlled; mask in logs |
| permanent/mailing address + `ruian_code` | location PII | access-controlled |
| `party_external_ids.id_value` (BANKID_SUB, ROB_AIFO, IČO, passport/ID numbers) | national identifiers | unique-constrained, access-controlled |
| `party_id_documents.doc_number` | ID document numbers | access-controlled |

`pep_flag` / `sanctions_flag` / `aml_risk_score` are sensitive compliance attributes — restricted to employee/admin and KYC/AML services.

## Retention

Per `governance.yaml`: `dataClassification: restricted`, `retentionPolicy: 10 years`, `evidenceExported: true`.

| Record state | Retention |
|---|---|
| Party with active relationship | ongoing |
| `status = TERMINATED` / `DECEASED` | retained 10 years (AMLD 6 Art. 40 — overrides GDPR erasure) |
| `pid_outbox` row `status = SENT` | prune after dispatch confirmation (operational; not statutory evidence) |
| Audit evidence (events) | held by `audit-service` for the statutory period |

## Lineage (`governance.yaml`)

- `dataLineageRole: both` (consumes and produces identity data).
- Owned schema: `pid_schema`; dependent schema: `parties_schema` (legacy `party-service` upstream, relation type `api`).
- Downstream consumers read identity via Kafka `party.events` and the REST `by-external-id` / `{id}` lookups.
