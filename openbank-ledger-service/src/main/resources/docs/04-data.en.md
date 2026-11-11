# Data

## Datastore

Dedicated PostgreSQL database **`openbank_ledger`** (reactive PG client for the app; blocking JDBC for Flyway). `dataDomain: core`, `dataClassification: confidential`, `dataLineageRole: both` (see [governance.yaml](../../governance.yaml)). Schema is managed exclusively by Flyway (`migrate-at-start: true`).

## Tables

| Table | Purpose | Notable columns |
|---|---|---|
| `gl_accounts` | chart of accounts | `code` (unique), `type` (ASSET/LIABILITY/EQUITY/INCOME/EXPENSE), `currency_code`, `parent_id`, `is_leaf`, `is_enabled` |
| `journal_entries` | accounting documents (**RANGE-partitioned by `entry_date`**) | `entry_number` (BIGINT seq), `transaction_id`, `entry_date`, `value_date`, `status` (PENDING/POSTED/REVERSED), `created_by`, `version`, `reversal_of` |
| `journal_lines` | debit/credit legs | `gl_account_id`, `side` (D/C), `amount` NUMERIC(20,6) (>0), `currency_code`, `fx_rate` NUMERIC(20,10), `base_amount`, `base_currency`, `sequence`, `sub_account_id` (nullable, deposit-control only) |
| `ledger_outbox` | transactional outbox | `event_id` (unique), `aggregate_id`, `event_type`, `payload`, `status`, `attempt_count`, `sent_at`, `last_error` |
| `ledger_idempotency` | post-journal dedup | `idempotency_key` (PK), `journal_id`, `journal_entry_date` |
| `ledger_repair_audit` | append-only log of declared repairs to posted journal rows (ADR-0372) | `reason`, `table_name`, `operation` (UPDATE/DELETE/TRUNCATE), `journal_id`, `entry_date`, `line_id`, `changed_columns`, `database_role`, `transaction_xid`, `recorded_at` |
| `partition_lifecycle_audit` | immutable partition-action log | `parent_table`, `partition_name`, `action` (CREATE/DETACH/DROP/DEFAULT_NONEMPTY/NOOP), `reason`, `dry_run`, `executed_at` |

### Storage-enforced journal invariants (ADR-0372, V44)

The database enforces the two invariants the domain model already holds, for every writer on the connection, not only the service:

- **Balance at commit.** Deferred constraint triggers refuse the COMMIT of a transaction that leaves a posted journal (status `POSTED` or `REVERSED`) with fewer than two lines, without both a debit and a credit line, or not netting to zero per `base_currency` (`side` D/C over `base_amount`). SQLSTATE `23514`. There is no override.
- **Append-only once posted.** A posted journal row accepts exactly one update, `POSTED → REVERSED` with `version + 1` and no other column changed (the reversal marking). A line of a posted journal accepts neither UPDATE nor DELETE, and neither table accepts TRUNCATE. A line can be INSERTED only into a `PENDING` journal or a journal the same transaction wrote; a line for a journal that does not exist yet is refused. SQLSTATE `55000`. `PENDING` journals and their lines stay editable.
- **Declared repair.** A reviewed data repair calls `SELECT ledger_declare_repair('reason')` in the same transaction as the rewrite. The declaration is bound to that transaction (a session-level setting opens nothing) and every rewritten row is logged in `ledger_repair_audit`, which itself refuses UPDATE, DELETE and TRUNCATE. A repair still cannot commit an unbalanced journal.

Consequences for anyone writing these tables directly (migrations, fixtures, operator sessions): insert the journal row first and all of its lines in **one transaction**, because statement-by-statement autocommit fails; do not rely on `INSERT ... ON CONFLICT DO NOTHING` to replay lines (the guard fires before the conflict is resolved), key an idempotent seed on the journal row; and correct a posted journal with a reversal, not an update.

Limits: this guards against mistakes and unreviewed code. A superuser or the table owner can disable or drop a trigger; partition `DETACH`/`DROP` and a `TRUNCATE` of a single partition do not fire it; the runtime role can itself declare a repair (logged, not independently approved). The cost is one journal lookup per inserted line and one deferred check per written row at commit: linear in journals and in the lines of a journal inserted statement by statement; a wide journal inserted by one multi-row statement is still checked once per line.

### Partitioning

`journal_entries` is `PARTITION BY RANGE (entry_date)` with one partition per calendar year (`journal_entries_2024..2028`) plus `journal_entries_default`. The primary key is composite `(id, entry_date)` and `entry_number` is unique per `(entry_number, entry_date)` — both because a partitioned table's PK must include the partition key. The `JournalPartitionMaintainer` keeps the horizon healthy at runtime (roll-forward 2 years, retention 10 years, DETACH-only/dry-run by default).

### Seeded GL accounts

V1 seeds cash/deposit/interest/fee accounts (1000, 1001, 2000, 2001, 2002, 3000, 4000, 4001). V3 adds well-known stable-UUID posting accounts (1100 cash clearing, 2100 deposit control CZK). V5 adds per-currency deposit-control (2101 EUR, 2102 USD, 2103 GBP), FX position accounts (1990–1993), exchange-rate differences P&L (5900), FX margin income (4002). V6 adds per-currency CZK counter-value accounts (1995–1997) for daily revaluation. V15 adds stable fee income 4003 (CZK); V34 adds its per-currency siblings 4004 (EUR), 4005 (USD), 4006 (GBP) and maps `FEE_INCOME_ACCOUNT` to them, so fee income is booked in the fee's own currency (#13226).

## Flyway migrations

| Version | Summary | Rollback |
|---|---|---|
| `V1__init_ledger` | `gl_accounts`, partitioned `journal_entries` (2024–2026 + default), `journal_lines`, indexes, seed chart of accounts | drop schema (greenfield) |
| `V25__regulatory_capital_accounts` | explicit CET1, deductions, AT1 and Tier 2 source accounts for COREP C 01.00 | delete only before any journal line references them |
| `V2__create_ledger_outbox` | `ledger_outbox` + status/aggregate indexes | drop table |
| `V3__ledger_governance` | `reversal_of` column, `ledger_idempotency` table, stable posting accounts (1100, 2100) | drop additions |
| `V4__hibernate_sequences` | `ledger_outbox_seq` (Hibernate pooled allocator) | drop sequence |
| `V5__fx_position_accounts` | per-currency deposit-control, FX position, exchange-diff & FX-margin accounts (ADR-0025) | delete seeded rows |
| `V6__fx_revaluation_counter_value_accounts` | CZK counter-value accounts 1995–1997 (ADR-0046) | delete seeded rows |
| `V7__add_sub_account_id_to_journal_lines` | nullable `sub_account_id` + partial index (ADR-0039 Phase B) | documented in-file: drop index + column (backward-compatible) |
| `V8__journal_partition_lifecycle` | pre-create 2027/2028 partitions, `partition_lifecycle_audit` table | drop partitions/table |
| `V44__storage_enforced_journal_invariants` | balance-at-commit constraint triggers, append-only guards on `journal_entries`/`journal_lines`, `ledger_declare_repair()`, `ledger_repair_audit` (ADR-0372); no existing row rewritten, already-unbalanced journals reported as a warning | drop the triggers and functions listed in the file; keep `ledger_repair_audit` |
| `V34__per_currency_fee_income_accounts` | fee income 4004 EUR / 4005 USD / 4006 GBP + `FEE_INCOME_ACCOUNT` map rows; aborts if fee-income and deposit-control currencies differ (#13226) | delete the map rows and accounts, only before any journal line references them |

**Never edit a migration after it is applied to a live DB** — Flyway checksum mismatch crashes startup (use `QUARKUS_FLYWAY_REPAIR_AT_START=true` as a temporary remedy, then remove).

## PII & sensitive fields

The ledger holds **financial/accounting data, not direct personal identifiers** — there is no name, IBAN, email or national ID in any table. The privacy-relevant fields are pseudonymous references:

| Field | Table | Nature |
|---|---|---|
| `transaction_id` | `journal_entries` | pseudonymous reference to a transaction (no PII) |
| `sub_account_id` | `journal_lines` | pseudonymous customer-account reference (sub-ledger dimension) |
| `created_by` / reversal actor | `journal_entries` | operator/system user UUID (staff identifier) |
| `amount` / `base_amount` | `journal_lines` | financial data (confidential) — money-path |

Re-identification (joining `sub_account_id`/`transaction_id` back to a customer) requires `account-service` / `transaction-service`. Data classification is **confidential**.

## Retention

- **`retentionPolicy: 10 years`** (governance.yaml) — driven by CZ accounting law (zákon 563/1991 Sb.) and AML record-keeping (AMLD 6 Art. 40 / 10 years).
- The append-only, year-partitioned journal makes retention a partition-lifecycle operation: partitions older than the retention horizon are DETACHed (DROP only under a deliberate, audited operator flag flip — `partition.drop-enabled` + clearing `dry-run`).
- `partition_lifecycle_audit` is itself immutable and retained for the same statutory period — it is the evidence trail of any detach/drop.
- `evidenceExported: true` — journal/trial-balance evidence is exportable for auditors.

## Outbox retention (SENT rows)

`ledger_outbox` is a delivery buffer, not a record. Rows that reached the broker (`status = 'SENT'`) are deleted once their `sent_at` is older than `openbank.outbox.retention.sent-days` (default **7**), by libs-runtime's shared `OutboxSentRetentionJob` (ADR-0329, ADR-0327 D8). The repository opts in by delegating `SentOutboxRetention` to `PanacheOutboxRetention`.

- Runs nightly (`openbank.outbox.retention.cron`, default `0 17 3 * * ?`) on every replica; each delete is bounded (`batch-size` 5 000, at most `max-batches` 200 per run), so a long-unpurged table drains over several nights.
- PENDING, FAILED, DISPATCHING and DEAD rows are never touched — DEAD rows are the producer-side DLQ.
- Replaying an event older than the window comes from the Kafka topic or audit-service, not from this table.
- Signals: `openbank_outbox_purged_total{service,status="SENT"}`, `openbank_outbox_purge_failed_total`, and workflow liveness `outbox-sent-retention`.
- Opt-out: `openbank.outbox.retention.enabled=false` (logs a WARN at boot). Don't lower `sent-days` below what any reader of SENT rows needs.

## Raw history read-cache lifecycle

V36 adds optional raw origin columns and immutable snapshot manifests, records and provenance. One repeatable-read transaction copies facts from one SQL/MVCC view; source sequence maxima are not commit watermarks. Provenance includes every opening-balance contributor. Source-row or source-line erasure and origin-evidence changes invalidate derived caches transactionally. Every snapshot read rejects missing attached sources; the existing partition-maintenance tick physically purges those unavailable caches after lifecycle actions and on later ticks. A source-free manifest is returned inline and never stored. No new independent TTL, source deletion, legal-hold release or financial retention duration is introduced. Cache retention follows availability of all its original sources; snapshots are read-caches, not new statutory close evidence.

V37 adds the atomic history-capture receipt after V36 creates its referenced snapshots. V35 remains the existing treasury-instrument migration, unchanged. These additions coexist with the preceding application, which does not use the snapshot endpoint or receipt tables. Roll back the application and disable the new endpoint first; preserve captured data and receipts pending retention review before any separately approved destructive schema rollback. No Flyway repair or checksum rewrite is part of this rollout.
