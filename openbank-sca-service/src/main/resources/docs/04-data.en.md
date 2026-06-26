# Data

## Persistence overview

- **Primary datastore:** PostgreSQL 16, dedicated database `openbank_sca` (reactive PG client; Flyway runs over the JDBC URL).
- **Schema generation:** `none` — Flyway is the single source of truth (`migrate-at-start: true`).
- **Transient state:** Redis (Valkey) holds OTPs, idempotency keys, and decoupled device decisions — none of it is durable; it is keyed by challenge id and bounded by the challenge TTL.

> Note: `governance.yaml` declares the logical schema name `sca_schema` (ADR-0071 curatorial metadata); the live migrations create tables in the default `public` schema of the `openbank_sca` database.

## Flyway migrations

| Version | File | Purpose |
|---|---|---|
| V1 | `V1__init_sca.sql` | `sca_challenges` table + indexes on `party_id`, `status`, `expires_at` |
| V2 | `V2__create_sca_outbox.sql` | `sca_outbox` table + indexes on `(status, created_at)` and `aggregate_id` |
| V3 | `V3__hibernate_sequences.sql` | `sca_outbox_seq` sequence (INCREMENT BY 50) — required by Panache id allocation under `generation:none`. Rollback: `DROP SEQUENCE sca_outbox_seq;` |
| V4 | `V4__enrolled_devices.sql` | `sca_enrolled_devices` table + index on `party_id` (ADR-0021). Rollback: `DROP TABLE IF EXISTS sca_enrolled_devices;` (credentials are re-enrollable from the device) |

**Never rewrite an applied migration** (CLAUDE.md / Flyway gotcha) — add a new versioned migration instead.

## Tables

### `sca_challenges`
| Column | Type | Notes |
|---|---|---|
| `id` | UUID PK | `gen_random_uuid()` |
| `party_id` | UUID | indexed; PII link (pseudonymous identifier) |
| `purpose`, `method`, `status` | VARCHAR(50) | enum-backed; `status` defaults `PENDING` |
| `expires_at`, `completed_at`, `failed_at` | TIMESTAMPTZ | lifecycle timestamps |
| `failure_reason` | TEXT | set only on terminal FAILED |
| `attempt_count`, `max_attempts` | INT | default 0 / 3 |
| `dynamic_amount` | VARCHAR(30) | dynamic linking (RTS Art. 5) |
| `dynamic_currency` | VARCHAR(3) | |
| `dynamic_creditor_iban` | VARCHAR(34) | **PII** — creditor IBAN |
| `dynamic_creditor_name` | VARCHAR(255) | **PII** — creditor name |
| `dynamic_reference` | VARCHAR(255) | payment reference |
| `redirect_url` | TEXT | |
| `created_at` | TIMESTAMPTZ | `NOW()` |

### `sca_enrolled_devices`
| Column | Type | Notes |
|---|---|---|
| `id` | UUID PK | |
| `party_id` | UUID | indexed; PII link |
| `credential_id` | TEXT UNIQUE | stable per-credential id the device presents |
| `public_key_spki` | TEXT | Base64 X.509 SubjectPublicKeyInfo (public key only — the **private key never leaves the device hardware keystore**) |
| `algorithm` | VARCHAR(16) | ES256 / ED25519 |
| `created_at` | TIMESTAMPTZ | |

### `sca_outbox`
Standard outbox table: `id BIGSERIAL`, `event_id UUID UNIQUE`, `aggregate_id UUID` (= partyId), `event_type` (e.g. `DEVICE_ENROLLED`), `payload TEXT`, `status`, `attempt_count`, `sent_at`, `last_error`, `created_at`, `updated_at`. Drained by `ScaOutboxDispatcher` to Kafka topic `openbank.sca.challenge.event`.

## PII inventory

| Field | Location | Classification | Handling |
|---|---|---|---|
| `party_id` | challenges, devices, outbox aggregate | pseudonymous identifier | not a direct identifier; resolves via `party-service` |
| `dynamic_creditor_iban` / `dynamic_creditor_name` | challenges | PII (payment context) | retained for dynamic-linking evidence; never logged in clear |
| `public_key_spki` | devices | non-secret (public key) | safe to persist; the matching private key stays on-device |
| OTP | Redis only | secret, transient | 300 s TTL, invalidated on success, never persisted to Postgres |
| device signature | Redis (decision store) | retained for audit, transient | bounded by challenge TTL |

Overall data classification (`governance.yaml`): **restricted**.

## Retention

| Data | Retention |
|---|---|
| `sca_challenges`, `sca_enrolled_devices`, `sca_outbox` | **5 years** (`governance.yaml: retentionPolicy`), consistent with PSD2/AMLD authentication-evidence keeping |
| OTPs / decisions (Redis) | seconds–minutes (challenge TTL, default 300 s) |

`evidenceExported: true` — authentication-evidence records are exported to the audit/evidence pipeline.
