# Data

## Datové úložiště

- **Engine:** PostgreSQL 16, přístup přes Hibernate Reactive (Panache) nad reaktivním PG klientem.
- **Databáze:** `openbank_tpp_registry` (`governance.yaml` deklaruje `databaseName: openbank_tpp_registry`, což odpovídá runtime connection stringu; tabulky žijí v jejím schématu `public`).
- **Generování schématu:** `none` — schéma vlastní Flyway; `migrate-at-start: true`.

## Tabulky

### `tpp_entries` (kořen agregátu)

| Sloupec | Typ | Poznámky |
|---|---|---|
| `id` | BIGSERIAL PK | náhradní klíč (Hibernate sekvence `tpp_entries_seq`, INCREMENT 50) |
| `tpp_id` | VARCHAR(100) UNIQUE NOT NULL | identifikátor EBA/CNB, např. `CZ-CNB-123456` |
| `name` | VARCHAR(255) NOT NULL | právní/obchodní název TPP |
| `country_code` | CHAR(2) NOT NULL | ISO 3166-1 alpha-2 |
| `nca` | VARCHAR(20) NOT NULL | národní příslušný orgán (`CNB`, `BaFin`, …) |
| `roles` | VARCHAR(100) NOT NULL | čárkou spojená množina `TppRole` (`AISP,PISP`) |
| `status` | VARCHAR(20) NOT NULL DEFAULT `ACTIVE` | doména sloupce: ACTIVE / SUSPENDED / REVOKED / BLACKLISTED. Zapisují se pouze ACTIVE a BLACKLISTED (#6489) |
| `qwac_subject_dn` | TEXT | Subject DN eIDAS QWAC certifikátu |
| `qseal_subject_dn` | TEXT | Subject DN eIDAS QSeal certifikátu |
| `qwac_expires_at` | DATE | expirace QWAC (kontrolováno při autorizaci) |
| `qseal_expires_at` | DATE | expirace QSeal |
| `registered_at` | TIMESTAMPTZ NOT NULL DEFAULT NOW() | |
| `updated_at` | TIMESTAMPTZ NOT NULL DEFAULT NOW() | |
| `blacklisted_at` | TIMESTAMPTZ | nastaveno při blacklistu |
| `blacklist_reason` | TEXT | |

Indexy: `idx_tpp_entries_status(status)`, `idx_tpp_entries_country(country_code)`.

### `eba_sync_state`

| Sloupec | Typ | Poznámky |
|---|---|---|
| `id` | BIGSERIAL PK | sekvence `eba_sync_state_seq` |
| `last_sync_at` | TIMESTAMPTZ | poslední pokus o sync |
| `last_success_at` | TIMESTAMPTZ | poslední úspěšný sync |
| `total_entries` | INT NOT NULL DEFAULT 0 | záznamy viděné v registru |
| `error_message` | TEXT | poslední chyba / stub zpráva |

Fakticky singleton řádek (repozitář čte/aktualizuje první řádek).

### `tpp_outbox`

| Sloupec | Typ | Poznámky |
|---|---|---|
| `id` | BIGSERIAL PK | sekvence `tpp_outbox_seq` |
| `event_id` | UUID UNIQUE NOT NULL | dedup klíč pro dispatcher |
| `aggregate_id` | UUID NOT NULL | id agregátu TPP |
| `event_type` | VARCHAR(128) NOT NULL | např. `TppRegistered` (zatím bez producentů) |
| `payload` | TEXT NOT NULL | serializovaná událost |
| `status` | VARCHAR(16) NOT NULL | PENDING / SENT / FAILED |
| `attempt_count` | INTEGER NOT NULL DEFAULT 0 | |
| `sent_at` | TIMESTAMPTZ | |
| `last_error` | TEXT | |
| `created_at` | TIMESTAMPTZ NOT NULL DEFAULT NOW() | |
| `updated_at` | TIMESTAMPTZ NOT NULL DEFAULT NOW() | |

Indexy: `idx_tpp_outbox_status_created_at(status, created_at ASC)` (pořadí draining), `idx_tpp_outbox_aggregate_id`.

## Flyway migrace

| Verze | Soubor | Co dělá | Rollback |
|---|---|---|---|
| V1 | `V1__init.sql` | `tpp_entries`, `eba_sync_state`, indexy, 3 seed sandbox TPP | `DROP TABLE tpp_entries, eba_sync_state;` |
| V3 | `V3__create_tpp_outbox.sql` | `tpp_outbox` + 2 indexy | `DROP TABLE tpp_outbox;` |
| V4 | `V4__hibernate_sequences.sql` | sekvence `*_seq` (INCREMENT 50) vyžadované Panache při `generation:none` | `DROP SEQUENCE eba_sync_state_seq, tpp_entries_seq, tpp_outbox_seq;` |
| V5 | `V5__tpp_outbox_claimed_at.sql` | `tpp_outbox.claimed_at` pro atomický claim `FOR UPDATE SKIP LOCKED` | `ALTER TABLE tpp_outbox DROP COLUMN claimed_at;` |
| V6 | `V6__tpp_entries_entry_uuid.sql` | `tpp_entries.entry_uuid` — doménové id, odlišné od interního BIGSERIAL PK (#2340) | `ALTER TABLE tpp_entries DROP COLUMN entry_uuid;` |
| V7 | `V7__tpp_entries_seq_past_seeded_rows.sql` | `setval` na `tpp_entries_seq` za seed řádky z V1 — bez toho první registrace koliduje na `tpp_entries_pkey` a vrací 500 (#4007) | `SELECT setval('tpp_entries_seq', 1, false);` |

> **Poznámka:** ve stromě není `V2` (historie migrací jej přeskakuje). Komentář u V4 dokumentuje napříč-službový vzor (stejná vada opravena u party V6 a notification V4/V5): samotný `BIGSERIAL` vytvoří jen `<table>_id_seq`, ale Panache očekává `<table>_seq`.

### Seed data (V1)

Tři sandbox/test TPP jsou vloženy pro local/dev: `CZ-CNB-SANDBOX-001` (AISP,PISP), `CZ-CNB-TEST-AISP` (AISP), `CZ-CNB-TEST-PISP` (PISP) — všechny `ACTIVE`, země `CZ`, NCA `CNB`.

> Tyto tři dostanou id 1..3 z implicitní `tpp_entries_id_seq`, zatímco Panache alokuje z `tpp_entries_seq`, kterou V4 vytvořila od 1. Do V7 tak byla PRVNÍ registrace přes API zaručeně `duplicate key value violates unique constraint "tpp_entries_pkey"` → 500. Nikdo na to nenarazil: měřeno na sandboxu 2026-08-16, `tpp_entries_seq` měla `last_value = 1, is_called = f` — nikdy nebyla zavolána, takže registrace nebyla v nasazeném prostředí nikdy vyzkoušena (#4007).

## PII a klasifikace dat

`governance.yaml` deklaruje `dataClassification: internal`. Registr drží informace o **právnických osobách (TPP)**, ne o fyzických osobách:

| Pole | Citlivost |
|---|---|
| `tpp_id`, `name`, `country_code`, `nca` | veřejná/regulatorní data registru (zrcadlí registr EBA) |
| `qwac_subject_dn`, `qseal_subject_dn` | identita firemního certifikátu — bezpečnostně relevantní, ne osobní PII |
| `roles`, `status`, `blacklist_reason` | provozní/compliance stav |

Žádné zákaznické PII (žádné party-id, IBAN, jméno fyzické osoby) se zde neukládá. Důvod blacklistu by neměl obsahovat osobní údaje.

## Retence

`governance.yaml: retentionPolicy: 5 years`. Záznamy registrace a blacklistu TPP se uchovávají **5 let**, v souladu s PSD2 / povinnostmi vedení záznamů pro důkaz autorizace. Záznamy se při deautorizaci tvrdě nemažou — přechod stavu na `BLACKLISTED` zachovává auditní historii. (`REVOKED` a `SUSPENDED` jsou v enumu deklarovány, ale dnes je nic nezapisuje — viz #6489.)

## Datová lineage

`governance.yaml: dataLineageRole: producer`, `evidenceExported: false`. Upstream: `psd2-service` (api vztah — validuje proti tomuto registru). Vlastněné schéma: `tpp_schema`.
