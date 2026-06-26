# Data

## Datastore

- **Engine:** PostgreSQL (reaktivní PG klient + JDBC pro Flyway), Hibernate Reactive / Panache.
- **Databáze:** `openbank_pid` (lokální dev URL `postgresql://localhost:5432/openbank_pid`). Tabulky se vytvářejí v defaultním schématu `public` níže uvedenými migracemi. (Governance manifest pro účely katalogu označuje logický název schématu `pid_schema`.)
- **Správa schématu:** Flyway, `migrate-at-start=true`, `baseline-on-migrate=true`, `connect-retries=10`. Hibernate `database.generation=none` — Flyway je jediný zdroj pravdy o DDL.

## Tabulky

### `parties` (V1) — kořen agregátu

| Skupina sloupců | Sloupce | Pozn. |
|---|---|---|
| Identita | `id` (UUID PK), `party_type`, `status`, `version` | check constrainty na type/status; `version` = optimistický zámek |
| Core | `given_name`, `family_name`, `birthdate`, `birth_number_encrypted`, `gender`, `birthplace`, `nationalities` (TEXT[]) | **PII** — viz níže |
| Verifikace | `verification_source`, `verified_at` | BANKID / BRANCH_MANUAL / API_UPLOAD / ROB |
| Kontakt | `email`, `email_verified_at`, `phone`, `phone_verified_at`, `preferred_language`, `data_box_id` | **PII** |
| KYC/AML | `kyc_level`, `kyc_completed_at`, `kyc_expires_at`, `aml_risk_score`, `pep_flag`, `sanctions_flag`, `ubo_verified_at`, `last_aml_review_at` | check constrainty na level/risk; částečné indexy na `pep_flag`/`sanctions_flag` kde TRUE |
| Adresa (embedded) | `permanent_address_*` (street, house_number, city, postal_code, country, ruian_code), `rob_synced_at` | **PII**; RUIAN kód ze sync ROB |
| Case (V3) | `case_id`, `case_type`, `case_status`, `case_last_actor`, `case_last_reason_code`, `case_last_transition_at`, `case_metadata` | životní cyklus PID verifikačního případu; check constrainty omezují type/status/reason |
| Audit | `created_at`, `updated_at` | default `NOW()` |

Indexy: `family_name`, `birthdate`, `email`, `status`, `kyc_level`, částečné `pep_flag`/`sanctions_flag`, `case_id`, částečný `case_status`.

### `party_external_ids` (V1)

`id` (BIGSERIAL PK), `party_id` (FK → parties, `ON DELETE CASCADE`), `id_type`, `id_value`, `verified_at`. **Unikátní `(id_type, id_value)`** vynucuje jeden externí identifikátor → jeden party (páteř deduplikace). Check `id_type` ∈ {KEYCLOAK_ID, BANKID_SUB, ROB_AIFO, ICO, PASSPORT_NUMBER, ID_CARD_NUMBER}. **`id_value` je PII** (národní identifikátory).

### `party_id_documents` (V1)

`id` (BIGSERIAL PK), `party_id` (FK, cascade), `doc_type` ∈ {NATIONAL_ID, PASSPORT, DRIVING_LICENSE, RESIDENCE_PERMIT}, `doc_number`, `issuing_country`, `issued_at`, `expires_at`. **`doc_number` je PII.**

### `party_relationships` (V1)

`id` (UUID PK), `party_id` (FK, cascade), `role` ∈ {CUSTOMER, EMPLOYEE, ADMIN, AGENT, GUARANTOR, AUTHORIZED_PERSON}, `status` ∈ {ACTIVE, SUSPENDED, TERMINATED}, `onboarded_at`, `onboarding_channel`, `terminated_at`, `termination_reason`. Unikátní `(party_id, role, status)` (deferrable) — jedna aktivní instance každé role.

### `pid_outbox` (V2) — transakční outbox

`id` (BIGSERIAL PK), `event_id` (UUID, UNIQUE), `aggregate_id` (UUID), `event_type`, `payload` (TEXT), `status`, `attempt_count`, `sent_at`, `last_error`, `created_at`, `updated_at`. Indexy na `(status, created_at ASC)` (poll dispatcheru) a `aggregate_id`.

## Flyway migrace

| Verze | Soubor | Změna | Poznámka k rollbacku |
|---|---|---|---|
| V1 | `V1__init_pid.sql` | extenze `uuid-ossp`, `pgcrypto`; tabulky `parties`, `party_external_ids`, `party_id_documents`, `party_relationships` + indexy | `DROP TABLE` (opačné pořadí FK); drop extenzí |
| V2 | `V2__create_pid_outbox.sql` | `pid_outbox` + dva indexy | `DROP TABLE pid_outbox` |
| V3 | `V3__add_pid_case_lifecycle.sql` | přidá sloupce `case_*` + check constrainty + 2 indexy k `parties` | `ALTER TABLE parties DROP COLUMN case_*`; drop obou indexů |
| V4 | `V4__hibernate_sequences.sql` | `CREATE SEQUENCE pid_outbox_seq INCREMENT BY 50` (alokace id Panache) | `DROP SEQUENCE pid_outbox_seq` |

> **Nikdy nepřepisuj aplikovanou migraci** (pravidlo projektu — checksum mismatch shodí Flyway při startu). Nové změny jdou do nové `Vn__*.sql`.

## Inventář PII

| Pole | Klasifikace | Ochrana |
|---|---|---|
| `birth_number_encrypted` (rodné číslo) | národní ID blízké zvláštní kategorii | **uloženo jen šifrovaně**; `pgcrypto` k dispozici; nikdy nevracíno v `PartyResponse` |
| `given_name`, `family_name`, `birthdate`, `birthplace`, `gender`, `nationalities` | osobní data | řízeno přístupem (role employee/admin); maskovat v logu |
| `email`, `phone`, `data_box_id` | kontaktní PII | řízeno přístupem; maskovat v logu |
| trvalá/korespondenční adresa + `ruian_code` | lokační PII | řízeno přístupem |
| `party_external_ids.id_value` (BANKID_SUB, ROB_AIFO, IČO, čísla pasů/OP) | národní identifikátory | unikátní constraint, řízeno přístupem |
| `party_id_documents.doc_number` | čísla dokladů totožnosti | řízeno přístupem |

`pep_flag` / `sanctions_flag` / `aml_risk_score` jsou citlivé compliance atributy — omezeny na employee/admin a KYC/AML služby.

## Retence

Dle `governance.yaml`: `dataClassification: restricted`, `retentionPolicy: 10 let`, `evidenceExported: true`.

| Stav záznamu | Retence |
|---|---|
| Party s aktivním vztahem | trvale |
| `status = TERMINATED` / `DECEASED` | drženo 10 let (AMLD 6 čl. 40 — přebíjí výmaz dle GDPR) |
| řádek `pid_outbox` `status = SENT` | prune po potvrzení odeslání (provozní; ne zákonný důkaz) |
| Audit důkazy (události) | drží `audit-service` po zákonnou dobu |

## Lineage (`governance.yaml`)

- `dataLineageRole: both` (konzumuje i produkuje identitní data).
- Vlastněné schéma: `pid_schema`; závislé schéma: `parties_schema` (legacy `party-service` upstream, typ vztahu `api`).
- Downstream konzumenti čtou identitu přes Kafka `party.events` a REST lookupy `by-external-id` / `{id}`.
