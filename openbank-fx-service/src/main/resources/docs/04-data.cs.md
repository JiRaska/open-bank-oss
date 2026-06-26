# Data

## Datastore

- **PostgreSQL 16**, databáze `openbank_fx` (reaktivní PG klient + JDBC pro Flyway).
- Logický název schématu (governance): `fx_schema` (`governance.yaml`). Tabulky jsou migracemi níže vytvářeny bez kvalifikace.
- `hibernate-orm.database.generation = none` — schéma vlastní Flyway, nikdy Hibernate.

## Tabulky

### `fx_rates` (V1)

| Sloupec | Typ | Pozn. |
|---|---|---|
| `id` | `UUID` | PK |
| `base_currency` | `CHAR(3)` | ISO 4217 |
| `quote_currency` | `CHAR(3)` | ISO 4217 |
| `bid_rate` | `NUMERIC(18,8)` | |
| `ask_rate` | `NUMERIC(18,8)` | použit pro konverze |
| `rate_type` | `VARCHAR(20)` | SPOT / FORWARD / INDICATIVE / INTERBANK (default `SPOT`) |
| `source` | `VARCHAR(20)` | ECB / REUTERS / BLOOMBERG / INTERNAL / CNB (default `ECB`) |
| `valid_from` / `valid_to` | `TIMESTAMPTZ` | okno platnosti; konverze kontrolují `isValid()` |
| `created_at` | `TIMESTAMPTZ` | default `NOW()` |

Index `idx_fx_rates_pair (base_currency, quote_currency, rate_type, valid_to)`. Seedováno ECB referenčními kurzy pro EUR/USD/GBP/CHF vůči CZK (V1).

### `fx_conversions` (V1)

| Sloupec | Typ | Pozn. |
|---|---|---|
| `id` | `UUID` | PK |
| `idempotency_key` | `VARCHAR(255)` | **UNIQUE** — pojistka idempotence |
| `party_id` | `UUID` | konvertující klient (vazba na PII) |
| `account_id` | `UUID` | nullable |
| `from_currency` / `to_currency` | `CHAR(3)` | |
| `from_amount_minor_units` | `BIGINT` | |
| `to_amount_minor_units` | `BIGINT` | |
| `applied_rate` | `NUMERIC(18,8)` | zafixováno při provedení (`= ask_rate`) |
| `fee_minor_units` | `BIGINT` | default 0; 0,5 % ze zdrojové částky |
| `rate_id` | `UUID` | **FK → `fx_rates(id)`** — fixuje použitý kurz |
| `status` | `VARCHAR(20)` | PENDING / SETTLED / FAILED / REVERSED (default `SETTLED`) |
| `created_at` | `TIMESTAMPTZ` | default `NOW()` |
| `settled_at` | `TIMESTAMPTZ` | null do vypořádání |

Index `idx_fx_conv_party (party_id)`.

### `fx_outbox` (V2)

Transakční outbox pro doménové události (viz [02 — Architektura](./02-architecture.md)).

| Sloupec | Typ | Pozn. |
|---|---|---|
| `id` | `BIGSERIAL` | PK |
| `event_id` | `UUID` | **UNIQUE** |
| `aggregate_id` | `UUID` | id konverze |
| `event_type` | `VARCHAR(128)` | např. `FxConversionExecuted` |
| `payload` | `TEXT` | serializovaná událost |
| `status` | `VARCHAR(16)` | PENDING / SENT / FAILED |
| `attempt_count` | `INTEGER` | default 0 |
| `sent_at` | `TIMESTAMPTZ` | |
| `last_error` | `TEXT` | poslední selhání publishe |
| `created_at` / `updated_at` | `TIMESTAMPTZ` | default `NOW()` |

Indexy `idx_fx_outbox_status_created_at (status, created_at ASC)`, `idx_fx_outbox_aggregate_id (aggregate_id)`.

## Migrace (Flyway)

| Verze | Soubor | Co |
|---|---|---|
| V1 | `V1__create_fx.sql` | `fx_rates`, `fx_conversions` + ECB seed kurzy |
| V2 | `V2__create_fx_outbox.sql` | `fx_outbox` + indexy |
| V3 | `V3__hibernate_sequences.sql` | `fx_outbox_seq` (INCREMENT BY 50) pro alokaci id Panache |

`migrate-at-start = true`, `validate-on-migrate = false`. **Nikdy needituj aplikovanou migraci** (checksum mismatch → pád startu; jako záchrana `QUARKUS_FLYWAY_REPAIR_AT_START=true`).

> **Důvod V3:** Hibernate Reactive + `PanacheEntity` alokují id ze sekvence `<table>_seq` (allocationSize 50). `BIGSERIAL` PK vytvoří jen `<table>_id_seq` a schéma je `generation:none`, takže inserty by selhaly s *relation "fx_outbox_seq" does not exist*. V3 ji přidává. `HibernateSequenceGuardTest` hlídá regresi. Rollback: `DROP SEQUENCE fx_outbox_seq;`.

## PII & klasifikace

`governance.yaml`: `dataClassification: confidential`, `retentionPolicy: 5 years`.

| Pole | Třída | Zacházení |
|---|---|---|
| `party_id`, `account_id` | pseudonymní identifikátory (vazba na PII) | uloženy jako UUID, ne přímé identifikátory |
| `partyName` (jen request) | **PII (jméno)** | posláno do `sanctions-service` k prověrce; **neperzistováno** v `fx_conversions` — pouze v AML případu (`aml-service`) a auditu screeningu |
| částky konverzí, kurzy, měny | finanční / confidential | uchováno dle politiky |

Jméno konvertujícího klienta se **v tabulkách této služby neukládá** — je prověřeno za běhu a perzistováno pouze `aml-service` při otevření případu. To minimalizuje PII v klidu zde.

## Retence

| Data | Retence | Základ |
|---|---|---|
| `fx_conversions` | 5 let (`governance.yaml`); AML důkazy mohou dosáhnout 10 let, kde je otevřen případ | uchovávání záznamů AML (AMLD); `governance.yaml` |
| `fx_rates` (vč. ČNB fixingu) | uchováno, dokud na ně odkazují konverze / pro audit | provenance kurzu pro obranu sporů |
| `fx_outbox` | přechodné — prořezáno po `SENT` (provozní) | není systém záznamu |

## Lineage (`governance.yaml`)

- `dataLineageRole: both`.
- **Downstream:** `transaction-service` konzumuje kurzy (`relationType: api`).
- Vlastněné schéma: `fx_schema`; závislé schéma: `transactions_schema`.
