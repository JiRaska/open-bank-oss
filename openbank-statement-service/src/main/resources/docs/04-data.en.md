# Data

## Datastore

- **Engine:** PostgreSQL 16 (reactive Vert.x PG client + Hibernate Reactive Panache).
- **Database:** `openbank_statement` (dedicated, ADR-0002 — no cross-service DB reads).
- **Migrations:** Flyway, `migrate-at-start: true`, `db/migration/V1..V3`. Hibernate schema generation is `none` — Flyway owns the schema.
- **Data domain / classification (governance.yaml):** `compliance`, `restricted`, retention **10 years**, lineage role `both`.

The defining data principle (ADR-0035): **persist the record, not the file.** Only the small `statement_period` record is stored. camt.053 / MT940 / PDF are deterministic, byte-identical projections rendered on demand from this record plus booked entries replayed from transaction-service — they are never warehoused.

## Tables

### `statement_period` (V1) — the retained legal record
| Column | Type | Notes |
|---|---|---|
| `id` | UUID PK | |
| `account_id` | UUID | |
| `pocket_currency` | VARCHAR(3) | ISO-4217 |
| `period_from` / `period_to` | DATE | |
| `legal_sequence_number` | BIGINT | monotonic per pocket |
| `electronic_sequence_number` | BIGINT | |
| `opening_balance` / `closing_balance` | NUMERIC(23,4) | |
| `entry_count` | INTEGER | default 0 |
| `status` | VARCHAR(16) | `CLOSED` (default) / `SUPERSEDED` |
| `supersedes_sequence` | BIGINT (null) | a correction supersedes a prior close |
| `closed_at` | TIMESTAMPTZ | stamped at close, drives deterministic renders |

Indexes: `ux_statement_period_window` (UNIQUE `account_id, pocket_currency, period_from, period_to` — the idempotency key), `ux_statement_period_legal_seq` (UNIQUE `account_id, pocket_currency, legal_sequence_number` — monotonic legal sequence), `ix_statement_period_account` (`account_id, period_to DESC`).

### `statement_outbox` (V1) — transactional outbox (ADR-0050)
`id` PK, `event_id` (UNIQUE), `aggregate_id`, `event_type`, `payload` (TEXT), `status` (`PENDING`/`SENT`/`FAILED`/`DEAD`), `attempt_count`, `sent_at`, `last_error`, `created_at`, `updated_at`. Index `ix_statement_outbox_status` (`status, created_at`).

### `account_registry` (V2) — read-only enumeration projection
`account_id` PK, `party_id`, `currency` (VARCHAR(3)), `registered_at`. Built by consuming the account-service `AccountCreated` stream (no "all accounts" endpoint exists; cross-service DB reads are forbidden). Used by the scheduled close to enumerate accounts; eventually consistent, fine for a monthly batch.

### `statement_close_run` (V3) — cadence telemetry (ADR-0069 D3)
`id` PK, `trigger` (`SCHEDULED`/`MANUAL`), `status` (`RUNNING`/`COMPLETED`/`COMPLETED_WITH_FAILURES`), `period_from`/`period_to`, `accounts_enumerated`, `pockets_closed`, `pockets_failed`, `pockets_skipped`, `started_at`, `finished_at`. Index `ix_statement_close_run_started` (`started_at DESC`). Operational outcome of a run — not statement content.

### `statement_close_failure` (V3) — per-pocket failures
`id` PK, `run_id` (FK → `statement_close_run` ON DELETE CASCADE), `account_id`, `pocket_currency`, `period_from`/`period_to`, `reason` (`RECONCILIATION`/`UPSTREAM`/`UNKNOWN`), `detail` (TEXT), `failed_at`. Indexes `ix_statement_close_failure_run`, `ix_statement_close_failure_pocket` (`account_id, pocket_currency, failed_at DESC`). A FAILED pocket is **not** a `statement_period` row — a period exists only when it closes cleanly; the failure is recorded here so the catch-up run can retry it.

## Migration list & rollback

| Version | What | Rollback |
|---|---|---|
| V1 `init_statement` | `statement_period`, `statement_outbox` | `DROP TABLE statement_outbox; DROP TABLE statement_period;` |
| V2 `account_registry` | `account_registry` projection | `DROP TABLE account_registry;` |
| V3 `close_run` | `statement_close_run`, `statement_close_failure` | `DROP TABLE statement_close_failure; DROP TABLE statement_close_run;` |

Per the project rule: **never change a migration after it is applied to a live DB** (checksum mismatch → startup fail).

## PII & sensitive fields

| Field | Location | Classification | Handling |
|---|---|---|---|
| `account_id` | all tables | pseudonymous identifier | not a natural person directly |
| `party_id` | `account_registry` | pseudonymous identifier | links to party-service (which owns the natural-person data) |
| IBAN | **not persisted** | PII | present only in the in-memory `StatementModel` and the rendered output / outbox event payload; never stored as a row |
| holder name | **not persisted** | PII | resolved at render time from account/party; never stored |
| balances / entries | `statement_period` (anchors only); entries replayed from transaction-service | financial | only opening/closing anchors stored; line entries are not warehoused |

Because rendered statements are not stored, the personal data on a statement (IBAN, holder name, line-item descriptions) lives only transiently during a render and in the upstream services that own it. The outbox event payload does carry IBAN and balances — same data controller, intra-OpenBank (see [06 — Compliance](./06-compliance.md)).

## Retention

10 years on the reproducible `statement_period` record (ČNB / AML). Because the record is the input to a deterministic render, retaining it satisfies PSD2 Art. 58(2) "made available, reproducible unchanged" without storing any file bytes.
