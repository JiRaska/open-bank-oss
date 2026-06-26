# Data

## Datastore

- **Engine:** PostgreSQL (reaktivní PG klient za běhu; JDBC pro Flyway).
- **Databáze:** `openbank_transactions` (lokální dev URL `…/openbank_transactions`).
- **Vlastní logické schéma:** `transactions_schema` (dle `governance.yaml`); závislá schémata čtená přes API: `accounts_schema`, `ledger_schema`.
- **Migrace:** Flyway, `migrate-at-start: true`, location `db/migration`, V1..V7.

## Tabulky

### `transactions` (range-partitionovaná podle `booking_date`)

Jádrový agregát. Partitionováno `PARTITION BY RANGE (booking_date)` s ročními partitionami (`transactions_2025`, `transactions_2026`) plus záchytná `transactions_default`.

Primární klíč `(id, booking_date)` (partitioning klíč musí být součástí PK). Klíčové sloupce:

| Sloupec | Typ | Poznámky |
|---|---|---|
| `id` | UUID | `gen_random_uuid()` |
| `reference_number` | VARCHAR(50) | unikátní per booking date |
| `type` | VARCHAR(20) | CHECK ∈ DEBIT/CREDIT/TRANSFER/FEE/INTEREST/REVERSAL/ADJUSTMENT |
| `source_account_id` / `target_account_id` | UUID? | |
| `amount` | NUMERIC(20,6) | CHECK `> 0` |
| `currency_code` | CHAR(3) | ISO 4217 |
| `fx_rate` | NUMERIC(20,10) | null bez FX |
| `base_amount` / `base_currency_code` | NUMERIC(20,6) / CHAR(3) | zúčtovací (booking) leg |
| `status` | VARCHAR(20) | CHECK ∈ PENDING/PROCESSING/COMPLETED/FAILED/REVERSED |
| `value_date` / `booking_date` | DATE | |
| `initiated_at` / `completed_at` / `failed_at` | TIMESTAMPTZ | |
| `failure_reason` | VARCHAR(500) | |
| `idempotency_key` | VARCHAR(100) | unikátní per booking date |
| `version` | BIGINT | optimistický zámek |

Unikátnost: `uq_transactions_reference (reference_number, booking_date)`, `uq_transactions_idempotency (idempotency_key, booking_date)`.

**Compliance pole (V2):** `actor_id`, `actor_type`, `channel` (API/BRANCH/ATM/MOBILE/INTERNET), `ip_address`, `correlation_id`, `purpose_code` (ISO 20022), `regulatory_reporting_code` (ČNB cross-border), `aml_screened` + `aml_screened_at`, `reversal_of`.

**Vyhledávací pole BIAN / ISO 20022 (V3):** `source_iban` / `target_iban` (ISO 13616), `source_bban` / `target_bban` (český formát), `counterparty_name`, `counterparty_bank_bic`, `remittance_info`, `end_to_end_id`, `transaction_code`, `bank_transaction_code`, `proprietary_code`, `fee_amount` / `fee_currency`, `exchange_rate_type`, `instructed_amount` / `instructed_currency`, `batch_id`, `mandate_id`, `creditor_scheme_id`, `category_purpose`, `local_instrument`, `clearing_system_ref`, `settlement_date`, `is_reversal`, `is_fee_transaction`, `technical_account_id`.

Indexy pokrývají účet, stav, datumy, actora, kanál, korelaci, partial index na neproscreenovaný AML a každé vyhledávací pole (IBAN/BBAN/protistrana/end-to-end/částka/poplatek/technický účet).

### `transaction_outbox`

Transakční outbox pro at-least-once publikaci událostí.

| Sloupec | Typ | Poznámky |
|---|---|---|
| `id` | BIGSERIAL | PK (sekvence `transaction_outbox_seq`, increment 50 — V6) |
| `event_id` | UUID | unikátní |
| `aggregate_id` | UUID | id transakce |
| `event_type` | VARCHAR(128) | `…transaction.initiated` / `.completed` / `.failed` |
| `payload` | TEXT | serializovaná událost |
| `status` | VARCHAR(16) | PENDING → SENT / FAILED |
| `attempt_count` | INTEGER | |
| `sent_at` / `last_error` | TIMESTAMPTZ / TEXT | |
| `created_at` / `updated_at` | TIMESTAMPTZ | |

Indexy: `(status, created_at ASC)` pro poll dispatcheru, `(aggregate_id)`.

### `payment_sagas`

Stav orchestrace ságy pro každou transakci (V5, CHECK rozšířen v V7).

| Sloupec | Typ | Poznámky |
|---|---|---|
| `id` | UUID | PK |
| `transaction_id` | UUID | unikátní (1 sága na transakci) |
| `state` | VARCHAR(32) | CHECK ∈ STARTED/PAYMENT_INITIATED/**FUNDS_RESERVED**/LEDGER_POSTING/**FUNDS_CAPTURED**/COMPLETED/COMPENSATING/COMPENSATED/FAILED |
| `idempotency_key` | VARCHAR(255) | unikátní |
| `failure_reason` / `compensation_reason` | TEXT | |
| `created_at` / `updated_at` | TIMESTAMPTZ | |
| `version` | BIGINT | |

Partial index `idx_payment_sagas_state … WHERE state NOT IN (COMPLETED, COMPENSATED, FAILED)` pro levné dohledání ság za běhu.

## Seznam migrací

| Verze | Co |
|---|---|
| V1 | `transactions` (partitionovaná) + základní indexy; rozšíření `uuid-ossp`, `pgcrypto` |
| V2 | compliance / audit-trail sloupce (actor, kanál, korelace, AML, purpose, reverze) |
| V3 | bankovní vyhledávací sloupce BIAN / ISO 20022 + protistrana + IBAN/BBAN |
| V4 | `transaction_outbox` |
| V5 | `payment_sagas` |
| V6 | `transaction_outbox_seq` (Hibernate sekvence, increment 50) |
| V7 | rozšíření `chk_payment_sagas_state` o FUNDS_RESERVED / FUNDS_CAPTURED (money-path oprava; poznámka o rollbacku v hlavičce migrace) |

**Pravidlo:** nikdy needituj aplikovanou migraci (checksum mismatch shodí Flyway). V7 záměrně ponechává vydanou V5 neměnnou a dropuje/recreatuje CHECK. Poznámka o rollbacku je v souboru migrace.

## PII & klasifikace

`governance.yaml`: `dataClassification: confidential`, `retentionPolicy: 7 years`, `evidenceExported: true`.

PII / citlivá pole:

| Pole | Citlivost |
|---|---|
| `source_iban` / `target_iban` / `source_bban` / `target_bban` | identifikátory účtu — PII (GDPR) |
| `counterparty_name` | osobní údaj protistrany |
| `ip_address` | osobní údaj (PSD2/EBA audit) |
| `actor_id` / `correlation_id` | pseudonymní identifikátory |
| `amount` / `base_amount` / `remittance_info` | finanční data, důvěrné |

IBANy a data protistran by měly být maskovány v logech (`libs.security.PiiMask`). Viz [06 — Compliance](./06-compliance.md) pro retenci a právní základ.

## Retence

7letá retence (`governance.yaml`), v souladu s ČNB knihami účetnictví a uchováváním záznamů dle AMLD. Partitionování podle `booking_date` činí z retence/archivace v roční granularitě operaci odpojení partitiony, nikoli hromadné mazání.
