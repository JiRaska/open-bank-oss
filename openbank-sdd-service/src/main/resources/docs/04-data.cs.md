# Data

## Datastore

- **Engine:** PostgreSQL, přístup reaktivně (`quarkus-reactive-pg-client` + Hibernate Reactive / Panache).
- **Databáze:** `openbank_sdd` (reaktivní URL `postgresql://…/openbank_sdd`; IT profil používá `openbank_sdd_it`).
- **Generování schématu:** žádné — schéma vlastní Flyway (`migrate-at-start: true`).
- **Název schématu:** migrace `V1` vytváří tabulky v defaultním schématu databáze `openbank_sdd` (žádný explicitní `SET search_path`). Per-service governance manifest (`governance.yaml`) *deklaruje* `sdd_schema`; deklarovaný název ber jako logického vlastníka a nekvalifikované Flyway DDL jako fyzickou realitu, dokud nebudou obě sjednoceny.

## Flyway migrace

| Migrace | Účel | Poznámka k rollbacku |
|---|---|---|
| `V1__init_sdd.sql` | Vytvoří `sdd_mandate` (trezor mandátů) a `sdd_outbox` (transakční outbox) s indexy. | `DROP TABLE sdd_outbox; DROP TABLE sdd_mandate;` — žádné datové závislosti jinde. |

> Podle GitOps pravidel: **nikdy needituj migraci po jejím nasazení na živou DB** (checksum mismatch → pád startu). Přidej místo toho novou `V2…`.

## Tabulka `sdd_mandate`

Agregát mandátu — systém záznamu trvalé inkasní autorizace plátce.

| Sloupec | Typ | Poznámky / klasifikace |
|---|---|---|
| `id` | `UUID` PK | id mandátu |
| `account_id` | `UUID` NOT NULL | účet plátce; indexováno (`ix_sdd_mandate_account`) — **PII (vazba na zákazníka)** |
| `debtor_iban` | `VARCHAR(34)` NOT NULL | IBAN plátce — **PII** |
| `creditor_identifier` | `VARCHAR(35)` NOT NULL | SEPA Creditor Identifier (CID); součást přirozeného klíče |
| `umr` | `VARCHAR(35)` NOT NULL | Unique Mandate Reference; součást přirozeného klíče |
| `scheme` | `VARCHAR(8)` NOT NULL | `CORE` / `B2B` |
| `sequence_type` | `VARCHAR(8)` NOT NULL | `OOFF` / `FRST` / `RCUR` / `FNAL` |
| `creditor_name` | `VARCHAR(140)` NOT NULL | zobrazované jméno creditora |
| `debtor_name` | `VARCHAR(140)` NOT NULL | jméno plátce — **PII** |
| `signature_date` | `DATE` NOT NULL | datum podpisu mandátu; kotva idle-expiry, dokud není inkaso |
| `status` | `VARCHAR(24)` NOT NULL | `PENDING_CONFIRMATION` / `ACTIVE` / `SUSPENDED` / `CANCELLED` / `EXPIRED`; indexováno (`ix_sdd_mandate_status`) |
| `b2b_confirmed` | `BOOLEAN` NOT NULL DEFAULT FALSE | true při potvrzení B2B mandátu |
| `last_collection_date` | `DATE` NULL | řídí idle-expiry; při orazítkování posune `FRST → RCUR` |
| `last_pre_notification_date` | `DATE` NULL | evidovaná (nevynucovaná) pre-notifikace creditora |
| `created_at` | `TIMESTAMPTZ` NOT NULL | čas vytvoření |
| `amendments` | `TEXT` NOT NULL DEFAULT `'[]'` | JSON pole `{field, oldValue, newValue, at}` — **může obsahovat PII** (např. změněný IBAN) |

**Indexy:**

- `uq_sdd_mandate_reference` — **UNIQUE** na `(creditor_identifier, umr)`. To je rulebooková identita a základ idempotence registrace.
- `ix_sdd_mandate_account` na `(account_id)` — výpis podle účtu.
- `ix_sdd_mandate_status` na `(status)` — sweep idle-expiry / živých mandátů.

## Tabulka `sdd_outbox`

Transakční outbox pro `sdd.*` události (ADR-0003 / ADR-0050), zapisovaný ve stejné transakci jako změna mandátu.

| Sloupec | Typ | Poznámky |
|---|---|---|
| `id` | `UUID` PK | id řádku |
| `event_id` | `UUID` NOT NULL UNIQUE | idempotenční id nesené konzumentům jako `ce-id` (`uq_sdd_outbox_event`) |
| `aggregate_id` | `UUID` NOT NULL | id mandátu; použito jako Kafka partition key |
| `event_type` | `VARCHAR(64)` NOT NULL | např. `sdd.mandate.registered.v1`, `sdd.collection.authorised.v1` |
| `payload` | `TEXT` NOT NULL | JSON tělo události — **může obsahovat PII** (IBAN u `collection.authorised`) |
| `status` | `VARCHAR(16)` NOT NULL | `PENDING` → `SENT`, nebo `FAILED` → `DEAD` (poison cap) |
| `attempt_count` | `INTEGER` NOT NULL DEFAULT 0 | pokusy o publish; `DEAD` při `MAX_ATTEMPTS` (10) |
| `sent_at` | `TIMESTAMPTZ` NULL | nastaveno při `SENT` |
| `last_error` | `TEXT` NULL | poslední chyba publish (zkráceno na 4000 znaků) |
| `created_at` / `updated_at` | `TIMESTAMPTZ` NOT NULL | časové značky životního cyklu |

**Index:** `ix_sdd_outbox_status` na `(status, created_at)` — dotaz dispatcheru na zpracovatelné řádky (`status IN (PENDING, FAILED) ORDER BY created_at`).

## Emitované události

| Typ události | Spouštěč |
|---|---|
| `sdd.mandate.registered.v1` | registrace nového mandátu |
| `sdd.mandate.confirmed.v1` | potvrzení B2B mandátu |
| `sdd.mandate.suspended.v1` / `…resumed.v1` / `…cancelled.v1` | přechody životního cyklu |
| `sdd.mandate.amended.v1` | zaznamenaná změna pole |
| `sdd.collection.authorised.v1` | inkaso ACCEPTováno — nese `debtorIban`, `amount`, `currency`, `dueDate` pro navazující zaúčtovací cestu |

## PII pole

Řádek mandátu a událost `collection.authorised` nesou osobní údaje: `debtor_iban`, `debtor_name`, `account_id` a jakýkoli IBAN/jméno uvnitř `amendments`. Viz [06 — Compliance](./06-compliance.md) pro mapování právního základu GDPR a retence.

## Retence

Governance manifest služby deklaruje **retenci 7 let** (`governance.yaml: retentionPolicy: 7 years`), konzistentní s vedením záznamů pro platby/AML. Schéma `V1` samo neimplementuje purge úlohu; retence je vynucena provozně / navazující archivační politikou. Není zabudovaná cesta pro výmaz (povinnosti vedení záznamů AML/PSD2 mají přednost — viz compliance).
