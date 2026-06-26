# Data

## Úložiště

- **Engine:** PostgreSQL 16 (reaktivní `pg-client` za běhu, JDBC pro Flyway).
- **Databáze:** `openbank_dispute`.
- **Schéma:** tabulky se vytvářejí ve výchozím schématu spojení (`public`). Manifest `governance.yaml` deklaruje logický název schématu `disputes_schema` pro účely katalogu/lineage; žádná migrace nenastavuje explicitní `search_path`, takže fyzické schéma je `public` (rozpor deklarovaného vs fyzického názvu je známý — TBD).
- **Generace:** `hibernate-orm.database.generation: none` — schéma vlastní Flyway, nikdy Hibernate DDL.

## Tabulky

### `disputes` (kořen agregátu)

| Sloupec | Typ | Poznámky |
|---|---|---|
| `id` | `UUID` PK | `gen_random_uuid()` |
| `reference` | `VARCHAR(32)` UNIQUE | `DSP-<epochMillis>` |
| `transaction_id` | `UUID` | reklamovaná transakce (FK hodnotou, bez cross-DB constraintu) |
| `account_id` | `UUID` | indexováno |
| `party_id` | `UUID` | reklamující zákazník |
| `dispute_type` | enum `dispute_type` | |
| `status` | enum `dispute_status` | výchozí `OPEN`, indexováno |
| `resolution` | enum `dispute_resolution` | výchozí `PENDING` |
| `amount` | `NUMERIC(20,4)` | `CHECK amount > 0` (V4) |
| `currency` | `CHAR(3)` | výchozí `EUR` |
| `description` | `TEXT` | volný text (potenciální PII) |
| `merchant_name` / `merchant_id` | `VARCHAR(256)` / `VARCHAR(64)` | |
| `transaction_date` | `DATE` | |
| `filing_date` | `DATE` | výchozí `CURRENT_DATE`, indexováno |
| `resolution_deadline` | `DATE` | podání + 45d SLA |
| `resolved_at` / `resolved_by` | `TIMESTAMPTZ` / `VARCHAR(64)` | |
| `chargeback_amount` | `NUMERIC(20,4)` | `CHECK IS NULL OR > 0` (V4) |
| `created_at` / `updated_at` | `TIMESTAMPTZ` | |

### `dispute_evidence`

`id` (PK), `dispute_id` (FK → `disputes`), `submitted_by`, `evidence_type`, `description` (TEXT, potenciální PII), `file_reference` (ukazatel na externí blob — binárka zde není uložena), `submitted_at`.

### `dispute_timeline`

`id` (PK), `dispute_id` (FK → `disputes`), `event_type`, `description` (TEXT), `actor`, `created_at`. Append-only auditní stopa.

### `dispute_outbox`

Transakční outbox: `id` (BIGSERIAL), `event_id` (UUID UNIQUE), `aggregate_id`, `event_type`, `payload` (TEXT), `status`, `attempt_count`, `sent_at`, `last_error`, časová razítka. Indexováno na `(status, created_at)` a `aggregate_id`.

## Indexy

`idx_disputes_account`, `idx_disputes_transaction`, `idx_disputes_status`, `idx_disputes_filing_date`, `idx_evidence_dispute`, `idx_timeline_dispute`, `idx_dispute_outbox_status_created_at`, `idx_dispute_outbox_aggregate_id`.

## Flyway migrace

| Verze | Soubor | Účel | Rollback |
|---|---|---|---|
| V1 | `V1__init_dispute.sql` | enumy + `disputes`, `dispute_evidence`, `dispute_timeline`, indexy | DROP tabulek + typů |
| V2 | `V2__create_dispute_outbox.sql` | `dispute_outbox` + indexy | DROP tabulky |
| V3 | `V3__hibernate_sequences.sql` | `dispute_outbox_seq` (INCREMENT BY 50) pro alokaci id v Panache | `DROP SEQUENCE dispute_outbox_seq;` |
| V4 | `V4__amount_check_constraints.sql` | CHECK constrainty na kladné částky | DROP constraintů |

> V3 opravuje defekt alokace id Hibernate-Reactive/Panache: Panache alokuje id ze sekvence `<table>_seq` (allocationSize 50), ale `BIGSERIAL` vytvoří jen `<table>_id_seq`; bez explicitní sekvence selžou inserty do `dispute_outbox` s `relation "dispute_outbox_seq" does not exist`. **Nikdy nepřepisuj aplikovanou migraci** (CLAUDE.md) — při driftu checksumu použij `QUARKUS_FLYWAY_REPAIR_AT_START`. Pozor: `flyway.validate-on-migrate` je aktuálně `false`.

## Inventář PII

| Pole | Klasifikace | Zacházení |
|---|---|---|
| `party_id`, `account_id`, `transaction_id` | pseudonymní identifikátory | UUID reference, žádné přímé PII |
| `description` (reklamace & důkaz) | potenciálně PII (volný text) | zadáno operátorem; brát jako důvěrné |
| `merchant_name` / `merchant_id` | data protistrany | důvěrné |
| `file_reference` | ukazatel na externí důkaz | blob žije mimo tuto DB |
| `resolved_by` / `submitted_by` / `actor` | identifikátory pracovníků/aktérů | provozní auditní data |

Celková klasifikace dat dle `governance.yaml`: **confidential**.

## Retence

- **Retenční politika:** **7 let** (`governance.yaml: retentionPolicy`), v souladu s ochranou spotřebitele / vedením záznamů platebních služeb.
- **Role v lineage:** `both` (konzumuje i produkuje). Deklarovaný downstream: `card-issuance-service` (vztah `blocks`).
- `evidenceExported: true` — záznamy reklamací tvoří součást regulatorní sady důkazů.
