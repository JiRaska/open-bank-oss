# Data

## Datastore

PostgreSQL 16, database **`openbank_kyc`** (reactive PG client + JDBC for Flyway). `hibernate-orm.database.generation = none` — the schema is owned exclusively by Flyway, which migrates at start (`migrate-at-start: true`).

> The migration SQL targets the **`public`** schema (default search path). The per-service governance manifest declares the logical schema name `kyc_schema`; treat `kyc_schema` as the logical/governance name and `public` in `openbank_kyc` as the physical location.

## Tables

### `kyc_cases` (V1, extended V2)

| Column | Type | Notes |
|---|---|---|
| `id` | BIGSERIAL PK | internal id; sequence `kyc_cases_seq` (V4) |
| `case_id` | UUID UNIQUE | business id (the aggregate `KycCase.id`) |
| `party_id` | UUID | the party under review — see PII below |
| `status` | VARCHAR(30) | OPEN / DOCUMENTS_REQUIRED / UNDER_REVIEW / APPROVED / REJECTED / EXPIRED — DOCUMENTS_REQUIRED is unreachable (#8535) |
| `risk_level` | VARCHAR(20) | LOW / MEDIUM / HIGH / VERY_HIGH |
| `assigned_to` | VARCHAR(100) | reviewer assignment |
| `checks_json` | TEXT | serialized list of `KycCheck` |
| `notes` | TEXT | reviewer / rejection reason |
| `reviewed_by`, `reviewed_at` | VARCHAR(100), TIMESTAMPTZ | four-eyes decision metadata |
| `expires_at` | TIMESTAMPTZ | 30 days from open |
| `created_at`, `updated_at` | TIMESTAMPTZ | |
| **V2 compliance fields** | | |
| `due_diligence_level` | VARCHAR(10) | SDD / CDD / EDD (CHECK constraint) — EBA AML |
| `source_of_funds`, `source_of_wealth` | VARCHAR(100) | FATF R.10 declarations |
| `business_purpose` | VARCHAR(200) | purpose of the relationship |
| `expected_turnover`, `expected_turnover_currency` | NUMERIC(20,2), CHAR(3) | |
| `pep_declaration` | BOOLEAN | 5AMLD customer PEP self-declaration |
| `beneficial_owner_id` | UUID | UBO link |
| `screening_provider`, `screening_ref` | VARCHAR | screening provider + reference |
| `next_review_date` | TIMESTAMPTZ | EBA periodic review (HIGH 1yr / MEDIUM 2yr / LOW 3yr) |
| `escalated_to`, `escalated_at`, `escalation_reason` | | escalation trail |

Indexes: `idx_kyc_cases_party_id`, `idx_kyc_cases_status`, `idx_kyc_due_diligence`, partial `idx_kyc_review_date`, partial `idx_kyc_pep`, and the partial unique index **`uq_kyc_cases_active_party`** (V5) enforcing at most one active case per party.

### `kyc_outbox` (V3)

Transactional outbox: `id`, `event_id` (UUID UNIQUE), `aggregate_id`, `event_type`, `payload` (TEXT), `status`, `attempt_count`, `sent_at`, `last_error`, `created_at`, `updated_at`. Indexes `idx_kyc_outbox_status_created_at`, `idx_kyc_outbox_aggregate_id`. Sequence `kyc_outbox_seq` (V4).

## Flyway migrations

| Version | File | What |
|---|---|---|
| V1 | `V1__create_kyc.sql` | `kyc_cases` + indexes + grants |
| V2 | `V2__compliance_fields.sql` | EBA AML / FATF CDD enrichment columns + constraints |
| V3 | `V3__create_kyc_outbox.sql` | transactional outbox table |
| V4 | `V4__hibernate_sequences.sql` | `kyc_cases_seq`, `kyc_outbox_seq` (Hibernate Reactive `INCREMENT BY 50`) |
| V5 | `V5__unique_active_kyc_case_per_party.sql` | partial unique index `uq_kyc_cases_active_party` (ADR-0068 idempotency) |

Each migration carries a rollback note in-file (e.g. `DROP SEQUENCE …`, `DROP INDEX uq_kyc_cases_active_party`). **Never edit an applied migration** (checksum mismatch) — see the repo GitOps notes.

## Events

- **Topic (out):** `openbank.kyc.events` — JSON `{ eventType, kycCaseId, partyId, status, riskLevel, occurredAt }`. Event types: `KYC_CASE_OPENED`, `KYC_CASE_STATUS_CHANGED`, `KYC_CASE_APPROVED`, `KYC_CASE_REJECTED`.
- **Topic (in):** `openbank.party.events` — consumes `PARTY_CREATED` to auto-open a case.

## PII & data classification

Classification: **restricted** (governance.yaml). KYC is among the most sensitive data domains in the platform.

| Field | Sensitivity | Handling |
|---|---|---|
| `party_id` | pseudonymous identifier | links to `party-service` master data; no direct identity stored here |
| `checks_json`, `notes`, `escalation_reason` | special-category-adjacent (AML findings, PEP, adverse media) | restricted access; only KYC/compliance/admin roles |
| `source_of_funds`, `source_of_wealth`, `expected_turnover` | financial profile | EDD / FATF data |
| `pep_declaration`, `beneficial_owner_id` | PEP / UBO | high sensitivity |

## Retention

**10 years** (governance.yaml `retentionPolicy: 10 years`), driven by AMLD record-keeping obligations — this overrides GDPR erasure for the statutory period after the relationship ends. See [06 — Compliance](./06-compliance.md).
