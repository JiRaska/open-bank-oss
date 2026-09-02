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
| `partition_lifecycle_audit` | immutable partition-action log | `parent_table`, `partition_name`, `action` (CREATE/DETACH/DROP/DEFAULT_NONEMPTY/NOOP), `reason`, `dry_run`, `executed_at` |

### Partitioning

`journal_entries` is `PARTITION BY RANGE (entry_date)` with one partition per calendar year (`journal_entries_2024..2028`) plus `journal_entries_default`. The primary key is composite `(id, entry_date)` and `entry_number` is unique per `(entry_number, entry_date)` — both because a partitioned table's PK must include the partition key. The `JournalPartitionMaintainer` keeps the horizon healthy at runtime (roll-forward 2 years, retention 10 years, DETACH-only/dry-run by default).

### Seeded GL accounts

V1 seeds cash/deposit/interest/fee accounts (1000, 1001, 2000, 2001, 2002, 3000, 4000, 4001). V3 adds well-known stable-UUID posting accounts (1100 cash clearing, 2100 deposit control CZK). V5 adds per-currency deposit-control (2101 EUR, 2102 USD, 2103 GBP), FX position accounts (1990–1993), exchange-rate differences P&L (5900), FX margin income (4002). V6 adds per-currency CZK counter-value accounts (1995–1997) for daily revaluation.

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
