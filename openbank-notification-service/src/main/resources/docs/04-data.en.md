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
| V7 | `V7__device_token_lifecycle_columns.sql` | `registered_at`, `refreshed_at` on `device_tokens` (ADR-0135 §2 token TTL) |
| V8 | `V8__notification_read_state.sql` | `read_at` on `notifications` + partial index `idx_notifications_party_unread` |
| V9 | `V9__redact_secret_notification_bodies.sql` | one-off redaction of stored `OTP_CODE` / `PASSWORD_RESET` bodies (see *Secret-bearing templates* below) |

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
| `body` | TEXT | rendered body — **may contain PII** (names, amounts). Never an authentication secret: see *Secret-bearing templates* |
| `status` | VARCHAR(10) | PENDING / SENT / FAILED / BOUNCED |
| `metadata` | JSONB | free-form, default `{}` |
| `sent_at` | TIMESTAMPTZ | delivery time |
| `created_at` | TIMESTAMPTZ | default `NOW()` |

#### Secret-bearing templates

`OTP_CODE` renders an authentication secret into the message body. The
secret is delivered to the customer but **never persisted**: `NotificationConsumer` stores
`TemplateSensitivity.REDACTED_BODY` in its place, and `NotificationResource` redacts again on
read (two independent controls, the ADR-0059 D3 shape). V9 cleared the rows written before this.

Why: `body` is readable by any `ROLE_OPERATOR` — both via `@RolesAllowed` and via the shared
`rest.rego` `operator-read-any` rule, which grants `.read`/`.list` on any resource to every
operator. Staff able to read a customer's OTP can complete that customer's SCA (ADR-0021). The
secret is also spent on delivery, so keeping it fails GDPR Art. 5(1)(c) data minimisation.

Classification lives in `domain/model/TemplateSensitivity.kt` as a positive allow-list, pinned by
`TemplateSensitivityTest` so any edit to the set is deliberate. Adding a template whose render
embeds a secret **without** classifying it is not caught by a test — review `renderTemplate` and
this allow-list together.

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
| `notifications.body` / `subject` | possible PII | secret-bearing templates (OTP_CODE) are delivered but never stored — redacted on write and again on read; no template egresses to oversight |
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
