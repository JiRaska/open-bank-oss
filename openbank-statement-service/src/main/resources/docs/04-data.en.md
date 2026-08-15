# Data

## Datastore

- **Engine:** PostgreSQL 16 (reactive Vert.x PG client + Hibernate Reactive Panache).
- **Database:** `openbank_statement` (dedicated, ADR-0002 — no cross-service DB reads).
- **Migrations:** Flyway, `migrate-at-start: true`, `db/migration/V1..V3`. Hibernate schema generation is `none` — Flyway owns the schema.
- **Data domain / classification (governance.yaml):** `compliance`, `restricted`, retention **10 years**, lineage role `both`.

The defining data principle (ADR-0035): **persist the record, not the file.** Only the `statement_period` record is stored. camt.053 / MT940 / PDF are deterministic, byte-identical projections rendered on demand from that record — they are never warehoused.

Since #3986 the record carries the **frozen render inputs** (`model_snapshot`) alongside the anchors. Before that, a render replayed the booked entries and the account identity *live*, so a late entry booked into an already-closed window, or a holder rename, silently changed an already-issued legal statement page — the opposite of what "byte-identical" means. The principle is unchanged (no camt/MT/PDF bytes are stored); what is stored is the canonical **model**, which is what ADR-0035's own "Alternatives considered" chose.

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
| `model_snapshot` | TEXT (null) | **V7** — frozen render inputs as JSON (`iban`, `holderName`, `entries`), captured at close so a re-render is byte-identical (#3986). NULL for periods closed before V7, which still replay live data; deliberately not backfilled, since the live projections may already have drifted and freezing today's answer would make the drift canonical |

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
| V6 `statement_period_restatement` | narrows the window index to non-`SUPERSEDED` rows | `DROP INDEX ux_statement_period_window_active;` then delete the `SUPERSEDED` rows and recreate the strict `ux_statement_period_window` (the two cannot coexist) |
| V7 `statement_period_model_snapshot` | `statement_period.model_snapshot` | `ALTER TABLE statement_period DROP COLUMN model_snapshot;` — lossless for every other column; the render path falls back to live projections when the snapshot is absent, so dropping it restores pre-#3986 behaviour for **all** periods, not only pre-V7 ones |

Per the project rule: **never change a migration after it is applied to a live DB** (checksum mismatch → startup fail).

## PII & sensitive fields

| Field | Location | Classification | Handling |
|---|---|---|---|
| `account_id` | all tables | pseudonymous identifier | not a natural person directly |
| `party_id` | `account_registry` | pseudonymous identifier | links to party-service (which owns the natural-person data) |
| IBAN | `statement_period.model_snapshot` (**since V7**) | PII | was previously never stored; frozen at close as part of the render inputs (#3986), and also present in the outbox event payload |
| holder name | `statement_period.model_snapshot` (**since V7**) | PII | was previously resolved live at render time; frozen at close, because resolving it live is exactly what rewrote the header of already-issued statements |
| balances | `statement_period` (anchors) | financial | opening/closing anchors as before |
| line entries | `statement_period.model_snapshot` (**since V7**) | financial | line items (amount, dates, description, counterparty) are now retained for the closed period; previously replayed from transaction-service on every render |

**#3986 changed this section, and the change is not cosmetic.** Making a closed statement reproducible requires retaining what it said, so IBAN, holder name and line-item descriptions are now stored for **10 years** in `model_snapshot` rather than living only transiently during a render. The data is unchanged in kind (the same fields already appeared in the rendered output and in the `period.closed` outbox payload, same controller, intra-OpenBank) and the table's existing classification already covers it — `compliance` / `restricted` / 10y retention. What changed is the *location*: an erasure or export request against a closed statement period must now reach this column, not only the upstream owners. See [06 — Compliance](./06-compliance.md).

## Retention

10 years on the reproducible `statement_period` record (ČNB / AML). Because the record is the input to a deterministic render, retaining it satisfies PSD2 Art. 58(2) "made available, reproducible unchanged" without storing any file bytes.
