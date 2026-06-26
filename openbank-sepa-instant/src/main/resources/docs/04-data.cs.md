# Data

## Úložiště

- **Engine:** PostgreSQL (reaktivní PG klient + JDBC pro Flyway).
- **Databáze:** `openbank_sepa_instant`.
- **Deklarované jméno schématu** (governance.yaml): `sepa_instant_schema`. Datová doména `payments`, klasifikace **confidential**, lineage role **both** (konzumuje upstream, produkuje události).
- **Generování schématu:** `hibernate-orm.database.generation = none` — schéma vlastní Flyway.

## Tabulky

### `sct_inst_payments` (V1)

Platební agregát. Jeden řádek na SCT Inst příkaz.

| Sloupec | Typ | Poznámka / PII |
|---|---|---|
| `id` | BIGSERIAL PK | interní surrogát (Hibernate seq, viz V3) |
| `payment_id` | UUID UNIQUE | veřejný identifikátor platby |
| `idempotency_key` | VARCHAR(128) UNIQUE | idempotenční pojistka |
| `status` | VARCHAR(32) | hodnota stavového automatu |
| `debtor_account_id` | UUID | reference na účet plátce |
| `debtor_iban` | VARCHAR(34) | **PII** — IBAN plátce |
| `debtor_name` | VARCHAR(255) | **PII** — prověřované jméno |
| `creditor_iban` | VARCHAR(34) | **PII** — IBAN příjemce |
| `creditor_name` | VARCHAR(255) | **PII** — prověřované jméno |
| `creditor_bic` | VARCHAR(11) | nullable |
| `amount` | NUMERIC(20,6) | **finanční** |
| `currency` | VARCHAR(3) | výchozí `EUR` |
| `remittance_info` | VARCHAR(280) | **volný text nesoucí PII** |
| `end_to_end_id` | VARCHAR(64) | reference dodaná plátcem |
| `execution_timeout_at` | TIMESTAMPTZ | deadline watchdogu (PROCESSING) |
| `settled_at` | TIMESTAMPTZ | nullable |
| `recalled_at` | TIMESTAMPTZ | nullable |
| `recall_reason` | VARCHAR(64) | nullable |
| `reject_reason` | VARCHAR(64) | např. `SANCTIONS_HIT` |
| `reject_detail` | TEXT | detail prověrky |
| `submitted_at` | TIMESTAMPTZ | nullable |
| `created_at` | TIMESTAMPTZ | výchozí `NOW()` |
| `updated_at` | TIMESTAMPTZ | výchozí `NOW()` |

Indexy: `idx_sct_inst_status(status)`, `idx_sct_inst_debtor(debtor_account_id)`, `idx_sct_inst_created(created_at DESC)` a parciální `idx_sct_inst_timeout(execution_timeout_at) WHERE status = 'PROCESSING'` (pohání watchdog provedení / `findTimedOut`).

### `sct_inst_outbox` (V2)

Transakční outbox pro at-least-once publikování událostí.

| Sloupec | Typ | Poznámka |
|---|---|---|
| `id` | BIGSERIAL PK | |
| `event_id` | UUID UNIQUE | dedup klíč |
| `aggregate_id` | UUID | `payment_id` |
| `event_type` | VARCHAR(128) | např. `SctInstPaymentSubmitted` |
| `payload` | TEXT | serializovaný JSON události |
| `status` | VARCHAR(16) | NEW / SENT / FAILED |
| `attempt_count` | INTEGER | čítač retry |
| `sent_at` | TIMESTAMPTZ | nullable |
| `last_error` | TEXT | nullable |
| `created_at` / `updated_at` | TIMESTAMPTZ | výchozí `NOW()` |

Indexy: `idx_sct_inst_outbox_status_created_at(status, created_at ASC)` (pořadí odeslání), `idx_sct_inst_outbox_aggregate_id(aggregate_id)`.

### Sekvence (V3)

`CREATE SEQUENCE IF NOT EXISTS sct_inst_outbox_seq INCREMENT BY 50` — Hibernate Reactive + Panache alokují id ze sekvence `<table>_seq` (allocationSize 50), kterou samotný BIGSERIAL neposkytuje. Bez ní inserty selžou za běhu s `relation "sct_inst_outbox_seq" does not exist`. Konvence repa: bez uvozovek, lowercase, `INCREMENT BY 50`.

## Flyway migrace

| Verze | Soubor | Účel | Poznámka k rollbacku |
|---|---|---|---|
| V1 | `V1__create_sct_inst_payments.sql` | tabulka plateb + 4 indexy | `DROP TABLE sct_inst_payments;` |
| V2 | `V2__create_sct_inst_outbox.sql` | tabulka outbox + 2 indexy | `DROP TABLE sct_inst_outbox;` |
| V3 | `V3__hibernate_sequences.sql` | `sct_inst_outbox_seq` | `DROP SEQUENCE sct_inst_outbox_seq;` (uvedeno v migraci) |

`flyway.migrate-at-start = true` s 10 connect retries (interval 2 s). **Nikdy nepřepisuj migraci poté, co byla aplikována na živou DB** (checksum mismatch → pád startu; gotcha repa).

## Inventář PII

| Pole | Kategorie | Zacházení |
|---|---|---|
| `debtor_iban`, `creditor_iban` | identifikátory účtu (PII) | uloženo v plain; maskováno v logu přes libs PII masking; nikdy nelogováno raw |
| `debtor_name`, `creditor_name` | osobní jména (PII) | synchronně posílána sanctions-service k prověrce; zahrnuta v `customerReference` AML případu při hold/reject |
| `remittance_info` | volný text (může nést PII) | uloženo tak, jak dodáno |
| `amount`, `currency` | finanční | — |

## Retence

- **Politika:** 7 let (governance.yaml `retentionPolicy`). `evidenceExported: true`.
- Platební záznamy jsou uchovávány po regulatorní dobu; AML/sankčně související záznamy následují AML retenční režim popsaný v [06 — Compliance](./06-compliance.md). Žádné automatické GDPR vymazání těchto dat (AML povinnost má přednost).
