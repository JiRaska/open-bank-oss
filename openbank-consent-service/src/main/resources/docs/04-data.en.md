# Data

## Datastore

- **Engine:** PostgreSQL 16, accessed reactively (Vert.x PG client + Hibernate Reactive Panache).
- **Database:** `openbank_consents`. Tables live in the `public` schema (the per-service governance manifest names the logical schema `consents_schema`; physically the tables are in `public`).
- **Schema generation:** `none` — the schema is owned by Flyway, applied at start (`migrate-at-start: true`).

## Tables

### `consents` — the aggregate

| Column | Type | Notes |
|---|---|---|
| `id` | `UUID` PK | `gen_random_uuid()` |
| `party_id` | `UUID` | customer who granted — **PII (pseudonymous identifier)** |
| `grantee_id` | `VARCHAR(255)` | TPP eIDAS org id or agent id |
| `grantee_type` | `VARCHAR(50)` | `TPP` / `BANK_AGENT` / `CUSTOMER_AGENT` / `INTERNAL_SERVICE` |
| `grantee_name` | `VARCHAR(255)` | human-readable name |
| `status` | `VARCHAR(50)` | default `PENDING_SCA` |
| `valid_from`, `valid_to` | `TIMESTAMPTZ` | `CHECK (valid_to > valid_from)`; PSD2 90-day cap enforced in domain |
| `sca_session_id` | `UUID` | reference to the SCA challenge that activated it |
| `redirect_uri` | `TEXT` / `VARCHAR(500)` | TPP redirect — **PII-adjacent** |
| `tpp_transaction_id` | `VARCHAR(255)` | TPP's own reference (idempotency input) |
| `ip_address` | `VARCHAR(45)` | client IP at creation — **PII** |
| `user_agent` | `TEXT` / `VARCHAR(500)` | client UA at creation — **PII** |
| `created_at`, `updated_at` | `TIMESTAMPTZ` | defaults `NOW()` |
| `revoked_at` | `TIMESTAMPTZ` | set on revoke |
| `revoked_reason` | `TEXT` | revoke reason |
| *(V2 compliance fields)* | | `tpp_name`, `tpp_roles`, `sca_method`, `sca_reference`, `frequency_per_day` (`CHECK 1..4`, default 4), `combined_service_flag`, `last_action_date`, `revoked_by`, `revocation_reason` |

Indexes: `party_id`, `grantee_id`, `status`, `valid_to`, `(party_id, grantee_id)`, partial indexes on `tpp_name` and `sca_reference`.

### `consent_scopes` — scope set (1‑to‑many)

| Column | Type | Notes |
|---|---|---|
| `consent_id` | `UUID` | FK → `consents(id)` `ON DELETE CASCADE` |
| `scope` | `VARCHAR(100)` | PK `(consent_id, scope)` |

### `consent_accounts` — covered IBANs (1‑to‑many, optional)

| Column | Type | Notes |
|---|---|---|
| `consent_id` | `UUID` | FK → `consents(id)` `ON DELETE CASCADE` |
| `iban` | `VARCHAR(34)` | PK `(consent_id, iban)` — **PII** |

Absence of rows ⇒ consent covers all accounts.

### `consent_outbox` — transactional outbox

| Column | Type | Notes |
|---|---|---|
| `id` | `BIGSERIAL` PK | (ids allocated from `consent_outbox_seq`, see V4) |
| `event_id` | `UUID UNIQUE` | dedup key |
| `aggregate_id` | `UUID` | the consent id |
| `event_type` | `VARCHAR(128)` | `ConsentGranted` / `ConsentRevoked` / `ConsentRejected` / `ConsentExpired` |
| `payload` | `TEXT` | serialized event JSON |
| `status` | `VARCHAR(16)` | `PENDING` / sent state |
| `attempt_count` | `INTEGER` | default 0 |
| `sent_at` | `TIMESTAMPTZ` | set on success |
| `last_error` | `TEXT` | last dispatch error |
| `created_at`, `updated_at` | `TIMESTAMPTZ` | |

Indexes: `(status, created_at ASC)` for the dispatcher poll, `aggregate_id`.

## Flyway migrations

| Version | File | What it does |
|---|---|---|
| V1 | `V1__init_consent.sql` | `consents`, `consent_scopes`, `consent_accounts` + indexes |
| V2 | `V2__compliance_fields.sql` | PSD2/RTS compliance columns (TPP details, SCA method, frequency, audit trail) + frequency `CHECK` |
| V3 | `V3__create_consent_outbox.sql` | `consent_outbox` + indexes |
| V4 | `V4__hibernate_sequences.sql` | `CREATE SEQUENCE consent_outbox_seq INCREMENT BY 50` — required by Panache id allocation (rollback: `DROP SEQUENCE consent_outbox_seq;`) |

> **Migration discipline (CLAUDE.md):** never edit an applied migration — it triggers a Flyway checksum mismatch on startup. Add a new `V{n}` instead. Each migration carries a rollback note.

## PII inventory

| Field | Classification | Handling |
|---|---|---|
| `party_id` | pseudonymous identifier | not a direct identifier; resolved only via party-service |
| `account_iban` | financial PII | masked in logs (PiiMask); access gated by the consent itself |
| `ip_address`, `user_agent` | PSD2 SCA evidence / PII | retained as fraud/audit evidence, not exposed in API responses |
| `redirect_uri` | PII-adjacent | TPP-controlled URL |

Overall data classification: **confidential** (governance manifest). The `ConsentResponse` DTO deliberately omits `ipAddress`, `userAgent`, `redirectUri` and `tppTransactionId` from read responses.

## Retention

- **Policy:** 5 years (governance manifest `retentionPolicy: 5 years`), aligned with PSD2/AML evidence retention for consent records.
- Revoked/expired consents are retained for the evidence window, not erased on revocation, because they are the auditable proof that access was (and is no longer) authorised.

See [06 — Compliance](./06-compliance.md) for the GDPR lawful-basis and retention rationale.
