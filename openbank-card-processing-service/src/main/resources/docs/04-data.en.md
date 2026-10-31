# Data

Database: `openbank_card_processing` (PostgreSQL), owned solely by this service (`governance.yaml`). Data domain `payments`, classification `confidential`, retention **7 years** (accounting records, ADR-0118).

## Tables

### `card_authorizations`

One row per authorisation — approved or declined. It is also the hold.

| Column | Type | Notes |
|---|---|---|
| `id` | UUID PK | UUIDv7 (ADR-0106) |
| `card_id`, `account_id`, `party_id` | UUID | card reference from card-issuance — **no PAN** |
| `amount_minor_units` | BIGINT | `> 0` |
| `currency_code` | VARCHAR(3) | |
| `channel` | VARCHAR(16) | `CONTACTLESS` / `ONLINE` / `ATM` / `CHIP_AND_PIN` |
| `mcc`, `merchant_name`, `merchant_country` | | optional |
| `status` | VARCHAR(20) | `APPROVED`, `DECLINED`, `PARTIALLY_CLEARED`, `CLEARED`, `REVERSED`, `EXPIRED` |
| `category` | VARCHAR(40) | card-issuance's MCC category, kept so clearing never re-judges it |
| `decline_reason` | VARCHAR(40) | set iff `DECLINED` |
| `cleared_amount_minor_units` | BIGINT | cumulative presented amount |
| `network_reference` | VARCHAR(64) | acquirer's reference |
| `idempotency_key` | VARCHAR(128) | written once on insert |
| `authorized_at`, `expires_at`, `updated_at` | TIMESTAMPTZ | |
| `version` | BIGINT | optimistic lock (V4, Hibernate `@Version`) — serialises every concurrent write (clearing, reversal, expiry) |

Constraints and indexes:

- `card_authorizations_cleared_within_authorized` — `cleared ≤ amount` (restates `AuthorizationLifecycle.clear`).
- `card_authorizations_decline_reason_iff_declined`.
- UNIQUE `ux_card_authorizations_idempotency_key`; partial UNIQUE `ux_card_authorizations_network_reference` (non-null only).
- `ix_card_authorizations_card_authorized_at (card_id, authorized_at DESC)` — spend counting.
- Partial `ix_card_authorizations_expiring (expires_at)` where status is `APPROVED` or `PARTIALLY_CLEARED` — the expiry sweep.

The held amount (`amount − cleared` while holding) is **derived**, never stored.

### `card_network_tokens` (V3)

The bank's record that a network token exists — a mirror of the network's answer, never a vault. No PAN, token credential or cryptogram.

| Column | Type | Notes |
|---|---|---|
| `id` | UUID PK | |
| `card_id` | UUID | indexed (`ix_card_network_tokens_card`) |
| `token_reference` | VARCHAR(128) UNIQUE | the network's opaque handle |
| `requestor_id`, `requestor_label` | VARCHAR | wallet or merchant that asked |
| `last4` | VARCHAR(4) | the token's own display value, not the card's |
| `status` | VARCHAR(16) | `ACTIVE` / `SUSPENDED` / `DELETED` |
| `scheme` | VARCHAR(16) | `VISA` / `MASTERCARD` / `SIMULATOR` — which binding answered |
| `expiry` | DATE | optional |
| `idempotency_key` | VARCHAR(160) UNIQUE | provisioning key (`adopted:<token_reference>` for a token adopted from a network read); later updates keep it. A backstop only — idempotency is the reservation in `card_lifecycle_idempotency` |
| `provisioned_at`, `updated_at` | TIMESTAMPTZ | |

### `card_dispute_cases` (V3)

| Column | Type | Notes |
|---|---|---|
| `id` | UUID PK | |
| `authorization_id` | UUID | **FK → `card_authorizations(id)`** |
| `card_id` | UUID | indexed with `opened_at DESC` |
| `network_case_id` | VARCHAR(128) UNIQUE | assigned by the network; never null |
| `reason_code` | VARCHAR(32) | scheme reason code, untranslated |
| `amount_minor_units`, `currency_code` | BIGINT, CHAR(3) | as the network confirmed them |
| `status` | VARCHAR(24) | `OPEN`, `EVIDENCE_SUBMITTED`, `WON`, `LOST`, `WITHDRAWN` |
| `scheme`, `scheme_status` | VARCHAR | binding that answered; network status verbatim |
| `respond_by_date` | DATE | optional |
| `evidence_reference` | VARCHAR(256) | the LATEST document reference filed; the full history is `card_dispute_evidence` |
| `idempotency_key` | VARCHAR(128) UNIQUE | opening key |
| `opened_at`, `updated_at` | TIMESTAMPTZ | |

`ux_card_dispute_live_per_authorization` — partial UNIQUE on `authorization_id` where status is `OPEN` or `EVIDENCE_SUBMITTED`: at most one live case per authorisation, enforced by the database.

### `card_dispute_evidence` (V3)

Append-only history of evidence filings — one row per filing, never updated. `card_dispute_cases.evidence_reference` is only the latest.

| Column | Type | Notes |
|---|---|---|
| `id` | UUID PK | |
| `dispute_id` | UUID | **FK → `card_dispute_cases(id)`**, indexed with `submitted_at` |
| `document_reference` | VARCHAR(256) | a handle, never the document |
| `note` | VARCHAR(2000) | optional |
| `scheme_status` | VARCHAR(64) | the network's status as it answered this filing, verbatim |
| `submitted_at` | TIMESTAMPTZ | |

### `card_lifecycle_idempotency` (V3)

Idempotency reservations for the calls that reach a card network (token provisioning, dispute opening, evidence filing). Inserted (`ON CONFLICT DO NOTHING`) **before** the network is called; completed in the same transaction as the result row.

| Column | Type | Notes |
|---|---|---|
| `reservation_key` | VARCHAR(160) PK | `<operation>:<Idempotency-Key>` |
| `operation` | VARCHAR(32) | `TOKEN_PROVISION`, `DISPUTE_OPEN`, `DISPUTE_EVIDENCE` |
| `fingerprint` | CHAR(64) | SHA-256 of the request the key is bound to |
| `state` | VARCHAR(16) | `PENDING` or `COMPLETED` (CHECK) |
| `result_id` | UUID | the token, case or evidence row; set iff `COMPLETED` |
| `created_at`, `updated_at` | TIMESTAMPTZ | |

A `PENDING` row never expires: a request that died after the network acted must not be retried into a second action. `ix_card_lifecycle_idempotency_pending` (partial, `state = 'PENDING'`) is the operator's query for stuck keys.

### `card_clearings`

One row per **applied** clearing presentment (V4) — the idempotency record for its key. Insert-only.

| Column | Type | Notes |
|---|---|---|
| `id` | UUID PK | UUIDv7 |
| `authorization_id` | UUID FK → `card_authorizations.id` | |
| `idempotency_key` | VARCHAR(128) | the acquirer's clearing key |
| `request_fingerprint` | VARCHAR(64) | libs `RequestFingerprint` (SHA-256 hex) of path, amount and upper-cased currency |
| `amount_minor_units` | BIGINT | `> 0` |
| `currency_code` | VARCHAR(3) | |
| `applied_at` | TIMESTAMPTZ | |

UNIQUE `ux_card_clearings_authorization_key (authorization_id, idempotency_key)` — the guarantee that one clearing key applies once even under concurrent duplicates.

### `card_outbox`

Transactional outbox (ADR-0050): `event_id` (unique), `aggregate_id`, `event_type`, `payload`, `status` (default `PENDING`), `attempt_count`, `last_error`, `created_at`, `updated_at`, `sent_at`, `claimed_at`, `synthetic` (V2). Hibernate uses sequence `card_outbox_seq` (increment 50), created explicitly in V1.

## Migrations

| Version | Content | Rollback |
|---|---|---|
| V1 `init_card_processing` | both tables, indexes, `card_outbox_seq` | `DROP TABLE card_outbox; DROP TABLE card_authorizations;` — safe only before any authorisation exists |
| V2 `synthetic_outbox_taint` | `card_outbox.synthetic BOOLEAN NOT NULL DEFAULT FALSE` (ADR-0252) | `ALTER TABLE card_outbox DROP COLUMN synthetic;` — safe only before synthetic traffic was dispatched |
| V3 `token_and_dispute_lifecycle` | `card_network_tokens`, `card_dispute_cases`, `card_dispute_evidence`, `card_lifecycle_idempotency`, their indexes | `DROP TABLE card_dispute_evidence; DROP TABLE card_lifecycle_idempotency; DROP TABLE card_dispute_cases; DROP TABLE card_network_tokens;` — all are new and nothing outside V3 references them; the drop loses only rows written since V3 |
| V4 `card_clearing_idempotency` | `card_clearings` + UNIQUE `(authorization_id, idempotency_key)`; `card_authorizations.version` | `DROP TABLE card_clearings; ALTER TABLE card_authorizations DROP COLUMN version;` — schema-safe, but re-opens double presentment and drops the record of applied clearing keys |

`migrate-at-start: true`; `validate-on-migrate: false`.

## Personal and sensitive data

- **No cardholder data** in the PCI sense: no PAN, CVV, expiry or cardholder name.
- `party_id` / `account_id` link to a natural person through other services; merchant name and country describe where the customer spent money. Treated as confidential financial data.
- Token rows link a card to a wallet or merchant requestor; dispute rows link a transaction to a chargeback reason. Both are confidential, and neither stores a card or token credential.
- Events carry the same fields as the row minus `idempotency_key`; no card credential is ever emitted.

## Outbox retention (SENT rows)

`card_outbox` is a delivery buffer, not a record. Rows that reached the broker (`status = 'SENT'`) are deleted once their `sent_at` is older than `openbank.outbox.retention.sent-days` (default **7**), by libs-runtime's shared `OutboxSentRetentionJob` (ADR-0329, ADR-0327 D8). The repository opts in by delegating `SentOutboxRetention` to `PanacheOutboxRetention`.

- Runs nightly (`openbank.outbox.retention.cron`, default `0 17 3 * * ?`) on every replica; each delete is bounded (`batch-size` 5 000, at most `max-batches` 200 per run), so a long-unpurged table drains over several nights.
- PENDING, FAILED, DISPATCHING and DEAD rows are never touched — DEAD rows are the producer-side DLQ.
- Replaying an event older than the window comes from the Kafka topic or audit-service, not from this table.
- Signals: `openbank_outbox_purged_total{service,status="SENT"}`, `openbank_outbox_purge_failed_total`, and workflow liveness `outbox-sent-retention`.
- Opt-out: `openbank.outbox.retention.enabled=false` (logs a WARN at boot). Don't lower `sent-days` below what any reader of SENT rows needs.
