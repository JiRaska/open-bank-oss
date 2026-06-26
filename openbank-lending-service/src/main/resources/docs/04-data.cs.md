# Data

## Úložiště

- **Engine:** PostgreSQL 16, reaktivní Postgres klient + Hibernate Reactive (Panache).
- **Databáze:** `openbank_lending` (reaktivní URL `postgresql://…/openbank_lending`). Governance název schématu: `lending_schema` (`governance.yaml`).
- **Generování schématu:** `none` — schéma vlastní Flyway (`migrate-at-start: true`, `validate-on-migrate: false`).
- **Klasifikace dat:** confidential. Role v lineáži dat: both (producent + konzument).

## Tabulky

| Tabulka | Účel | Klíčové sloupce |
|---|---|---|
| `loan_application` | Origination — žádost ve čtyřoč toku | `id`, `party_id`, `requested_amount`+`currency`, `nominal_annual_rate`, `term_periods`, `method`, `status`, `proposed_by` (maker), `decided_by` (checker), `decision_reason`, `created_at`, `decided_at` |
| `loan` | Servicing — živý úvěr zaúčtovaný z načerpané žádosti | `id`, `application_id` → `loan_application`, `party_id`, `principal`+`currency`, `nominal_annual_rate`, `term_periods`, `method`, `status`, `disbursed_at`, `version` (optimistický zámek) |
| `installment` | Smluvní splátkový kalendář, jeden řádek na splátku | `id`, `loan_id` → `loan`, `number`, `due_date`, `currency`, `opening_balance`, `principal`, `interest`, `payment`, `closing_balance`, `paid`+`paid_at`, `interest_accrued`+`accrued_at`; `UNIQUE(loan_id, number)` |
| `collateral` | Zajištění evidované k úvěru (kategorie AnaCredit) | `id`, `loan_id` → `loan`, `type`, `description`, `market_value`+`currency`, `haircut` (`[0,1]`), `valued_at` |
| `lending_outbox` | Transakční outbox (ADR-0003) | `id` (BIGSERIAL), `event_id` (unikát), `aggregate_id`, `event_type`, `payload`, `status`, `attempt_count`, `sent_at`, `last_error`, `created_at`, `updated_at` |

### PostgreSQL enum typy (V1)

`amortization_method` (`ANNUITY`, `EQUAL_PRINCIPAL`, `BULLET`); `application_status` (`PROPOSED`, `APPROVED`, `REJECTED`, `DISBURSED`); `loan_status` (`ACTIVE`, `CLOSED`, `WRITTEN_OFF`); `collateral_type` (`REAL_ESTATE`, `VEHICLE`, `SECURITIES`, `CASH_DEPOSIT`, `GUARANTEE`, `OTHER`).

### Reprezentace peněz

Peněžní částky jsou `NUMERIC(20,2)` se samostatnou `CHAR(3)` ISO-4217 `currency`; sazby `NUMERIC(10,6)`, haircut `NUMERIC(5,4)`. Úvěry jsou jednoměnové.

### Indexy

`idx_loan_application_party`, `idx_loan_application_status`, `idx_loan_party`, `idx_loan_status`, `idx_installment_loan`, parciální `idx_installment_due (due_date) WHERE paid = FALSE`, parciální `idx_installment_accruable (due_date) WHERE paid = FALSE AND interest_accrued = FALSE` (řídí akruální průchod), `idx_collateral_loan`, `idx_lending_outbox_status (status, created_at)`, `idx_lending_outbox_aggregate`.

## Flyway migrace

| Verze | Soubor | Co dělá |
|---|---|---|
| **V1** | `V1__init_lending.sql` | Enum typy; `loan_application`, `loan`, `installment`, `collateral`, `lending_outbox`; všechny indexy |
| **V2** | `V2__installment_interest_accrual.sql` | Přidá `installment.interest_accrued` + `accrued_at`; parciální `idx_installment_accruable` (servicing smyčka, ADR-0028 Fáze 2) |
| **V3** | `V3__hibernate_sequences.sql` | `CREATE SEQUENCE lending_outbox_seq INCREMENT BY 50` — Panache alokace id potřebuje `<table>_seq`; CREATE TABLE vytvořilo jen `<table>_id_seq` a generování je `none`. Rollback: `DROP SEQUENCE lending_outbox_seq;` |

**Pravidlo (CLAUDE.md):** nikdy needituj migraci po jejím nasazení do živé DB — přidej novou verzovanou migraci. `validate-on-migrate` je vypnuto; `QUARKUS_FLYWAY_REPAIR_AT_START` použij jen jako dočasné zotavovací opatření.

## PII & citlivá pole

| Pole | Klasifikace | Poznámky |
|---|---|---|
| `party_id` (všechny tabulky) | Pseudonymní identifikátor | UUID reference na `party-service`; jméno/kontakt zde nejsou uloženy |
| `requested_amount` / `principal` / `interest` / `market_value` | Confidential finanční | Ekonomika úvěru |
| `proposed_by` / `decided_by` | Identita operátora | JWT subjekt jednajícího bankovního pracovníka (maker/checker) — interní identifikátor zaměstnance |
| `decision_reason` | Confidential | Volný text; může nést odůvodnění úvěrového rozhodnutí |
| `lending_outbox.payload` | Confidential | JSON události obsahující `loanId`, `partyId`, částky |

V této službě se neukládá žádné jméno klienta, adresa, IBAN ani rodné číslo — pouze pseudonymní `party_id`.

## Retence

- **Politika:** 7 let (`governance.yaml: retentionPolicy`), v souladu s povinnostmi uchovávat úvěrové smlouvy / účetní záznamy. AML hold může toto u označených případů prodloužit.
- Uzavřené (`CLOSED`) a odepsané (`WRITTEN_OFF`) úvěry se uchovávají po zákonnou dobu, nikoli mažou; GDPR výmaz je překryt zákonnými povinnostmi uchovávání záznamů (viz [06 — Compliance](./06-compliance.md)).
- `evidenceExported: false` — služba ještě není zapojena do centrální evidence-export pipeline.
