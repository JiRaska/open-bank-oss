# Data

## Úložiště

Dedikovaná PostgreSQL databáze **`openbank_ledger`** (reaktivní PG klient pro aplikaci; blokující JDBC pro Flyway). `dataDomain: core`, `dataClassification: confidential`, `dataLineageRole: both` (viz [governance.yaml](../../governance.yaml)). Schéma spravuje výhradně Flyway (`migrate-at-start: true`).

## Tabulky

| Tabulka | Účel | Pozoruhodné sloupce |
|---|---|---|
| `gl_accounts` | účtová osnova | `code` (unique), `type` (ASSET/LIABILITY/EQUITY/INCOME/EXPENSE), `currency_code`, `parent_id`, `is_leaf`, `is_enabled` |
| `journal_entries` | účetní doklady (**RANGE-partitionováno dle `entry_date`**) | `entry_number` (BIGINT seq), `transaction_id`, `entry_date`, `value_date`, `status` (PENDING/POSTED/REVERSED), `created_by`, `version`, `reversal_of` |
| `journal_lines` | debetní/kreditní řádky | `gl_account_id`, `side` (D/C), `amount` NUMERIC(20,6) (>0), `currency_code`, `fx_rate` NUMERIC(20,10), `base_amount`, `base_currency`, `sequence`, `sub_account_id` (nullable, jen deposit-control) |
| `ledger_outbox` | transakční outbox | `event_id` (unique), `aggregate_id`, `event_type`, `payload`, `status`, `attempt_count`, `sent_at`, `last_error` |
| `ledger_idempotency` | dedup zaúčtování | `idempotency_key` (PK), `journal_id`, `journal_entry_date` |
| `partition_lifecycle_audit` | neměnný log akcí partitionů | `parent_table`, `partition_name`, `action` (CREATE/DETACH/DROP/DEFAULT_NONEMPTY/NOOP), `reason`, `dry_run`, `executed_at` |

### Partitioning

`journal_entries` je `PARTITION BY RANGE (entry_date)` s jedním partitionem na kalendářní rok (`journal_entries_2024..2028`) plus `journal_entries_default`. Primární klíč je složený `(id, entry_date)` a `entry_number` je unique per `(entry_number, entry_date)` — obojí proto, že PK partitionované tabulky musí obsahovat partition klíč. `JournalPartitionMaintainer` udržuje horizont zdravý za běhu (roll-forward 2 roky, retence 10 let, DETACH-only/dry-run jako výchozí).

### Naseedované účty HK

V1 seeduje hotovostní/vkladové/úrokové/poplatkové účty (1000, 1001, 2000, 2001, 2002, 3000, 4000, 4001). V3 přidává well-known účty se stabilním UUID (1100 cash clearing, 2100 deposit control CZK). V5 přidává per-měnové deposit-control (2101 EUR, 2102 USD, 2103 GBP), FX position účty (1990–1993), výsledkový účet kurzových rozdílů (5900) a FX margin income (4002). V6 přidává per-měnové CZK counter-value účty (1995–1997) pro denní revalvaci.

## Flyway migrace

| Verze | Shrnutí | Rollback |
|---|---|---|
| `V1__init_ledger` | `gl_accounts`, partitionované `journal_entries` (2024–2026 + default), `journal_lines`, indexy, seed osnovy | drop schématu (greenfield) |
| `V25__regulatory_capital_accounts` | explicitní zdrojové účty CET1, odpočtů, AT1 a Tier 2 pro COREP C 01.00 | smazat jen před prvním odkazem z účetního řádku |
| `V2__create_ledger_outbox` | `ledger_outbox` + indexy status/aggregate | drop tabulky |
| `V3__ledger_governance` | sloupec `reversal_of`, tabulka `ledger_idempotency`, stabilní účty (1100, 2100) | drop přídavků |
| `V4__hibernate_sequences` | `ledger_outbox_seq` (Hibernate pooled allocator) | drop sekvence |
| `V5__fx_position_accounts` | per-měnové deposit-control, FX position, kurzové rozdíly & FX-margin účty (ADR-0025) | smazat naseedované řádky |
| `V6__fx_revaluation_counter_value_accounts` | CZK counter-value účty 1995–1997 (ADR-0046) | smazat naseedované řádky |
| `V7__add_sub_account_id_to_journal_lines` | nullable `sub_account_id` + partial index (ADR-0039 fáze B) | dokumentováno v souboru: drop indexu + sloupce (zpětně kompatibilní) |
| `V8__journal_partition_lifecycle` | pre-create partitionů 2027/2028, tabulka `partition_lifecycle_audit` | drop partitionů/tabulky |

**Nikdy needituj migraci po aplikaci na živou DB** — Flyway checksum mismatch shodí startup (dočasná náprava `QUARKUS_FLYWAY_REPAIR_AT_START=true`, poté odstranit).

## PII a citlivá pole

Hlavní kniha drží **finanční/účetní data, ne přímé osobní identifikátory** — v žádné tabulce není jméno, IBAN, e-mail ani rodné číslo. Pole relevantní pro soukromí jsou pseudonymní reference:

| Pole | Tabulka | Povaha |
|---|---|---|
| `transaction_id` | `journal_entries` | pseudonymní reference na transakci (bez PII) |
| `sub_account_id` | `journal_lines` | pseudonymní reference na zákaznický účet (analytická dimenze) |
| `created_by` / aktér storna | `journal_entries` | UUID operátora/systémového uživatele (identifikátor zaměstnance) |
| `amount` / `base_amount` | `journal_lines` | finanční data (confidential) — money-path |

Re-identifikace (spojení `sub_account_id`/`transaction_id` zpět na zákazníka) vyžaduje `account-service` / `transaction-service`. Klasifikace dat je **confidential**.

## Retence

- **`retentionPolicy: 10 let`** (governance.yaml) — daná zákonem o účetnictví (563/1991 Sb.) a AML uchováváním záznamů (AMLD 6 čl. 40 / 10 let).
- Append-only, ročně partitionovaná kniha dělá z retence operaci životního cyklu partitionů: partitiony starší než retenční horizont jsou DETACHovány (DROP jen za vědomého, auditovaného přepnutí flagu — `partition.drop-enabled` + zrušení `dry-run`).
- `partition_lifecycle_audit` je sama neměnná a uchovávaná po stejnou zákonnou dobu — je to důkazní stopa každého detach/drop.
- `evidenceExported: true` — důkazy z knihy/předvahy lze exportovat pro auditory.
