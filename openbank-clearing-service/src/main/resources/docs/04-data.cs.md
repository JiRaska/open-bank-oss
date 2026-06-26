# Data

## Datové úložiště

- **Engine:** PostgreSQL 16, reactive PG klient + JDBC (Flyway).
- **Databáze:** `openbank_clearing` (`quarkus.datasource.reactive.url`).
- **Logické schéma (governance):** `clearing_schema` (vlastněné), závislé schéma `transactions_schema` (dle `governance.yaml`).
- **Generování schématu:** `hibernate-orm.database.generation = none` — schéma plně vlastní Flyway. `flyway.migrate-at-start = true`, `validate-on-migrate = false`.
- **Klasifikace dat:** `confidential`. **Datová doména:** `payments`. **Role v lineage:** both (consumer + producer).

## Flyway migrace

| Migrace | Účel |
|---|---|
| `V1__init_clearing.sql` | enumy + jádrové tabulky: `clearing_batches`, `clearing_items`, `settlement_positions` + indexy |
| `V2__create_clearing_outbox.sql` | tabulka transakčního outboxu `clearing_outbox` + indexy |
| `V3__hibernate_sequences.sql` | `clearing_outbox_seq` (INCREMENT BY 50) — nutné, protože Hibernate Reactive alokuje id ze `<table>_seq`, zatímco `generation=none` |
| `V4__amount_check_constraints.sql` | CHECK constrainty na kladnou částku u položek a totalů dávek |

> **Disciplína migrací (CLAUDE.md):** nikdy needituj aplikovanou migraci. `V3` existuje právě proto, že chybějící `<table>_seq` by selhal každý INSERT za běhu — jeho rollback poznámka je `DROP SEQUENCE clearing_outbox_seq;`.

## Tabulky

### `clearing_batches`
Zúčtovací cyklus pro jeden rail.

| Sloupec | Typ | Pozn. |
|---|---|---|
| `id` | UUID PK | `gen_random_uuid()` |
| `batch_reference` | VARCHAR(64) UNIQUE | reference cyklu |
| `rail` | enum `payment_rail` | SEPA_SCT / SEPA_SCT_INST / SWIFT / DOMESTIC / INTERNAL |
| `settlement_type` | enum `settlement_type` | GROSS / NET / DEFERRED_NET, výchozí NET |
| `status` | enum `clearing_status` | výchozí PENDING |
| `total_debit` / `total_credit` / `net_position` | NUMERIC(20,4) | CHECK `total_debit >= 0`, `total_credit >= 0` (V4) |
| `currency` | CHAR(3) | výchozí EUR |
| `item_count` | INT | výchozí 0 |
| `cycle_id` | VARCHAR(32) | nullable |
| `settlement_date` | DATE | nullable |
| `settled_at` | TIMESTAMPTZ | nullable |
| `created_at` / `updated_at` | TIMESTAMPTZ | výchozí NOW() |

Indexy: `idx_clearing_batches_status`, `idx_clearing_batches_cycle`.

### `clearing_items`
Jednotlivá platba v clearingu.

| Sloupec | Typ | Pozn. |
|---|---|---|
| `id` | UUID PK | `gen_random_uuid()` |
| `batch_id` | UUID FK → `clearing_batches(id)` | placeholder do přiřazení cyklem |
| `payment_id` | UUID | id upstream platby |
| `payment_reference` | VARCHAR(64) | |
| `debtor_iban` / `creditor_iban` | VARCHAR(34) | **PII** |
| `debtor_bic` / `creditor_bic` | VARCHAR(11) | nullable |
| `amount` | NUMERIC(20,4) | CHECK `amount > 0` (V4) |
| `currency` | CHAR(3) | výchozí EUR |
| `status` | enum `clearing_status` | výchozí PENDING |
| `value_date` | DATE | nullable |
| `end_to_end_id` | VARCHAR(35) | nullable |
| `remittance_info` | VARCHAR(140) | nullable — volný text, může obsahovat PII |
| `error_code` / `error_message` | VARCHAR(16)/(256) | nullable |
| `created_at` / `updated_at` | TIMESTAMPTZ | výchozí NOW() |

Indexy: `idx_clearing_items_batch`, `idx_clearing_items_payment`, `idx_clearing_items_status`.

### `settlement_positions`
Čistá pozice za účastníka v cyklu.

| Sloupec | Typ | Pozn. |
|---|---|---|
| `id` | UUID PK | `gen_random_uuid()` |
| `participant_bic` | VARCHAR(11) | |
| `currency` | CHAR(3) | výchozí EUR |
| `cycle_id` | VARCHAR(32) | |
| `gross_debit` / `gross_credit` / `net_position` | NUMERIC(20,4) | výchozí 0 |
| `settled` | BOOLEAN | výchozí FALSE |
| `settled_at` | TIMESTAMPTZ | nullable |
| `created_at` | TIMESTAMPTZ | výchozí NOW() |
| — | UNIQUE | `(participant_bic, currency, cycle_id)` |

Index: `idx_settlement_positions_cycle`.

### `clearing_outbox`
Transakční outbox vyprazdňovaný do Kafky.

| Sloupec | Typ | Pozn. |
|---|---|---|
| `id` | BIGSERIAL PK | |
| `event_id` | UUID UNIQUE | |
| `aggregate_id` | UUID | id dávky/položky |
| `event_type` | VARCHAR(128) | |
| `payload` | TEXT | serializovaná událost |
| `status` | VARCHAR(16) | PENDING / SENT / FAILED |
| `attempt_count` | INTEGER | výchozí 0 |
| `sent_at` / `last_error` | TIMESTAMPTZ / TEXT | nullable |
| `created_at` / `updated_at` | TIMESTAMPTZ | výchozí NOW() |

Indexy: `idx_clearing_outbox_status_created_at`, `idx_clearing_outbox_aggregate_id`. Sekvence `clearing_outbox_seq` (V3).

## Inventář PII

| Pole | Tabulka | Klasifikace | Zacházení |
|---|---|---|---|
| `debtor_iban`, `creditor_iban` | `clearing_items` | PII (identifikátory účtů) | confidential; maskovat v logu |
| `remittance_info` | `clearing_items` | potenciálně PII (volný text) | confidential |
| `debtor_bic`, `creditor_bic` | `clearing_items` | nízká — identifikátory institucí | — |
| `participant_bic` | `settlement_positions` | nízká — identifikátor instituce | — |

Data zde jsou **transakční/platební**, ne kmenová zákaznická — citlivými prvky jsou IBANy a text remitance.

## Retence

- **Politika (governance.yaml):** `retentionPolicy: 7 years`.
- Důvod: platební/zúčtovací záznamy spadají pod AML a účetní uchovávání záznamů; clearingové položky a dávky se uchovávají po zákonnou dobu (viz [06 — Compliance](./06-compliance.md)).
- **Outbox řádky** jsou provozní, nikoli záznamy o účtu — po úspěšném doručení (status SENT) je lze prořezat dle platformového úklidu outboxu. (Explicitní purge migrace zatím není — TBD.)
