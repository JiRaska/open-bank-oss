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
| V15 | `V15__party_erasure_erased_at.sql` | nullable `erased_at` on `parties` and `identity_verification_case` + partial index (GDPR Art. 17 marker) | drop the index and both columns; safe at any time |

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
| `status = TERMINATED` / `DECEASED` | retained 10 years (AMLD 6 Art. 40) |
| Party erased by `PARTY_ERASED` | personal columns anonymised **immediately**; the anonymised shell row is kept, stamped `erased_at` (see Erasure) |
| `pid_outbox` row `status = SENT` | prune after dispatch confirmation (operational; not statutory evidence) |
| Audit evidence (events) | held by `audit-service` for the statutory period |

## Erasure (GDPR Art. 17, ADR-0118)

pid-service consumes `PARTY_ERASED` from party-service's `openbank.party.events` (channel `party-events-in`, consumer `PartyErasureConsumer`, DLQ `openbank.dlq.pid.party-events-in`). It follows the rule kyc-service and party-service apply: **anonymise in place now, keep the shell row stamped `erased_at`** — the identifying data pid holds is a derived index of data whose authoritative AML record is retained elsewhere, so nothing identifying has to stay here.

| Data | Action |
|---|---|
| `parties.birth_number_encrypted` | set to NULL |
| `party_external_ids` (RČ and EUDI blind indices, BankID/Keycloak subs, ROB AIFO, IČO, passport/ID numbers) | **rows deleted** |
| `party_id_documents` | **rows deleted** |
| `given_name`, `family_name` | `GDPR_ERASED`; `birthdate` = 1900-01-01 (NOT NULL sentinel) |
| `gender`, `birthplace`, `nationalities`, `email`, `phone`, `data_box_id`, address columns, `rob_synced_at`, `case_metadata` | NULL / empty |
| `party_relationships.termination_reason` (free text) | NULL |
| `parties.status` | `TERMINATED`; `erased_at` stamped |
| `identity_verification_case` rows the party was linked to (`first_link_party_id` / `final_link_party_id`) | applicant name, birthdate, birthplace, nationalities, `blind_index`, `first_notes` blanked; `dedup_key` replaced by `ERASED:<case id>`; `erased_at` stamped. Cases where the party was only a candidate describe someone else and are untouched |
| `kyc_level`, `pep_flag`, `sanctions_flag`, timestamps, operator names on cases | kept: no longer attributable to a person once the row above is anonymised |

The erase is idempotent: `erased_at` is the marker and is never overwritten, and a re-delivery re-runs the full overwrite and deletes, so anything written to the row after the first erase is cleaned again. Writes to an erased party (register-identity, contact/KYC/status updates, external-id link, relationships, linking a verification case to it) are refused with 409 (`PartyErasedException`, backstopped inside the repository's write transaction). Failures are retried then dead-lettered. Not covered, and why: the short-TTL EUDI offer/presentation tables are keyed by single-use codes, not by party id, and expire by themselves; `pid_outbox` has no writer in the current code. A scheduled hard-delete of shell rows after the retention period (as kyc-service's retention scheduler does for its cases) is a tracked follow-up; the shell holds no personal data.

## Lineage (`governance.yaml`)

- `dataLineageRole: both` (consumes and produces identity data).
- Owned schema: `pid_schema`; dependent schema: `parties_schema` (legacy `party-service` upstream, relation type `api`).
- Downstream consumers read identity via Kafka `party.events` and the REST `by-external-id` / `{id}` lookups.
