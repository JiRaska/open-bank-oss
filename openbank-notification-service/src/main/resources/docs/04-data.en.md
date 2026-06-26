# Data

## Datastore

- **Engine:** PostgreSQL 16 (reactive `vertx-pg-client` + JDBC for Flyway).
- **Database:** `openbank_notifications`.
- **Schema:** tables live in the `public` schema. *(The governance manifest declares `notifications_schema` as the owned logical schema name; the physical migrations create objects in `public`. Declared-vs-physical: treat `public` as authoritative for the running DB.)*
- **ORM:** Hibernate Reactive with Panache; `hibernate-orm.database.generation = none` (Flyway owns the schema).
- **Migrations:** Flyway, `migrate-at-start = true`. Hibernate Panache allocates IDs from `<table>_seq` sequences (allocationSize 50) — every table has a matching `CREATE SEQUENCE … INCREMENT BY 50`, enforced by `HibernateSequenceGuardTest`.

## Flyway migrations

| Version | File | Change |
|---|---|---|
| V1 | `V1__create_notifications.sql` | `notifications` table + indexes on `party_id`, `status` |
| V2 | `V2__create_notification_outbox.sql` | `notification_outbox` table + indexes on `(status, created_at)`, `aggregate_id` |
| V3 | `V3__create_dispatch_control.sql` | `dispatch_control_log` + `dispatch_resume_proposal` (ADR-0047 break-glass) |
| V4 | `V4__dispatch_control_sequences.sql` | `dispatch_control_log_seq`, `dispatch_resume_proposal_seq` (Hibernate sequence fix) |
| V5 | `V5__notification_sequences.sql` | `notifications_seq`, `notification_outbox_seq` (Hibernate sequence fix) |
| V6 | `V6__create_device_tokens.sql` | `device_tokens` table + unique `(platform, token)`, `(party_id, status)` index, `device_tokens_seq` |

Each migration file carries an inline **rollback note** (e.g. V6: `DROP TABLE device_tokens; DROP SEQUENCE device_tokens_seq;`). Never rewrite an applied migration (checksum-mismatch startup failure) — use `QUARKUS_FLYWAY_REPAIR_AT_START=true` if a live DB drifts.

## Tables

### `notifications`

| Column | Type | Notes |
|---|---|---|
| `id` | BIGSERIAL PK | surrogate |
| `notification_id` | UUID UNIQUE | business id (returned over REST) |
| `party_id` | UUID | recipient party — **pseudonymous identifier (PII-linked)** |
| `channel` | VARCHAR(10) | EMAIL / SMS / PUSH / IN_APP |
| `template` | VARCHAR(50) | template enum name |
| `recipient` | VARCHAR(255) | **PII** — email address / phone / device target |
| `subject` | VARCHAR(500) | rendered subject (may contain content) |
| `body` | TEXT | rendered body — **may contain PII** (names, masked amounts, OTP) |
| `status` | VARCHAR(10) | PENDING / SENT / FAILED / BOUNCED |
| `metadata` | JSONB | free-form, default `{}` |
| `sent_at` | TIMESTAMPTZ | delivery time |
| `created_at` | TIMESTAMPTZ | default `NOW()` |

### `notification_outbox`

Generic transactional outbox: `id`, `event_id` (UUID UNIQUE), `aggregate_id`, `event_type`, `payload` (TEXT), `status`, `attempt_count`, `sent_at`, `last_error`, `created_at`, `updated_at`. Polled by `NotificationOutboxDispatcher`.

### `device_tokens` (push registry)

| Column | Type | Notes |
|---|---|---|
| `id` | BIGSERIAL PK | |
| `device_id` | UUID UNIQUE | business id |
| `party_id` | UUID | owner party (PII-linked) |
| `app_instance` | VARCHAR(255) | stable per-install id |
| `platform` | VARCHAR(10) | FCM / APNS |
| `token` | TEXT | **provider push token — PII-adjacent**; masked in logs, never returned over REST |
| `app_version` / `os_version` | VARCHAR(40) | optional client metadata |
| `status` | VARCHAR(10) | ACTIVE / INACTIVE / INVALID |
| `last_used_at`, `created_at`, `updated_at` | TIMESTAMPTZ | |

Unique `(platform, token)` → re-registration upserts; index `(party_id, status)` is the fan-out lookup path.

### `dispatch_control_log` (append-only desired state)

`control_key`, `state` (ENABLED/HALTED), `version_no`, `reason`, `actor`, `effective_from`, `deferred_review_required`, `created_at`. Index `(control_key, version_no DESC)` — every replica reads the latest version per key. Append-only ⇒ point-in-time reconstructible (who/when/why).

### `dispatch_resume_proposal` (four-eyes)

`proposal_id` (UNIQUE), `control_key`, `reason`, `proposed_by`, `proposed_at`, `state` (PROPOSED/APPROVED/REJECTED/WITHDRAWN/EXECUTED), `decided_by`, `decided_at`, `decision_reason`, `executed_at`.

## PII inventory

| Field | Classification | Control |
|---|---|---|
| `notifications.recipient` | direct PII (email / phone) | masked in logs (PiiMask); not exposed in oversight signals |
| `notifications.body` / `subject` | possible PII / secret (OTP) | OTP/secret templates never egress to oversight |
| `notifications.party_id`, `device_tokens.party_id` | pseudonymous identifier | links to party-service |
| `device_tokens.token` | PII-adjacent provider token | write-only over REST, masked in logs |
| `dispatch_control_log.actor` / `dispatch_resume_proposal.*_by` | operator identity | audit-logged, internal only |

Data classification (governance manifest): **confidential**.

## Retention

- **Declared retention policy:** **2 years** (governance manifest `retentionPolicy: 2 years`).
- Notifications are communication records, not statutory accounting records — they are **not** subject to the 10-year AML retention that money-path services carry.
- Device tokens are retained while a device is registered; provider-rejected tokens are marked `INVALID` and drop out of fan-out (not auto-deleted by the service today — purge is **TBD** / operational).
- Dispatch-control logs are append-only and retained for the operational-evidence window (DORA Art. 17).

> A scheduled retention/purge job is **not yet implemented** in this service (TBD); retention is currently a declared policy enforced operationally.
