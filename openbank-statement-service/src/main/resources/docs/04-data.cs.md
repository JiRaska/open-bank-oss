# Data

## Datastore

- **Engine:** PostgreSQL 16 (reaktivní Vert.x PG klient + Hibernate Reactive Panache).
- **Databáze:** `openbank_statement` (dedikovaná, ADR-0002 — žádné cross-service čtení DB).
- **Migrace:** Flyway, `migrate-at-start: true`, `db/migration/V1..V3`. Generace schématu Hibernate je `none` — schéma vlastní Flyway.
- **Datová doména / klasifikace (governance.yaml):** `compliance`, `restricted`, retence **10 let**, role linie `both`.

Definující datový princip (ADR-0035): **ukládej záznam, ne soubor.** Ukládá se pouze záznam `statement_period`. camt.053 / MT940 / PDF jsou deterministické, bajt po bajtu identické projekce renderované na vyžádání z tohoto záznamu — nikdy se neskladují.

Od #3986 záznam nese vedle kotev i **zmrazené vstupy renderu** (`model_snapshot`). Předtím render přehrával zaúčtované položky a identitu účtu *živě*, takže položka doúčtovaná do již uzavřeného období nebo přejmenování majitele tiše změnily již vydanou právní stránku výpisu — pravý opak toho, co znamená „bajt po bajtu identický". Princip se nemění (žádné bajty camt/MT/PDF se neukládají); ukládá se kanonický **model**, což je varianta, kterou zvolily samotné „Alternatives considered" v ADR-0035.

## Tabulky

### `statement_period` (V1) — uchovávaný právní záznam
| Sloupec | Typ | Poznámky |
|---|---|---|
| `id` | UUID PK | |
| `account_id` | UUID | |
| `pocket_currency` | VARCHAR(3) | ISO-4217 |
| `period_from` / `period_to` | DATE | |
| `legal_sequence_number` | BIGINT | monotónní per kapsa |
| `electronic_sequence_number` | BIGINT | |
| `opening_balance` / `closing_balance` | NUMERIC(23,4) | |
| `entry_count` | INTEGER | výchozí 0 |
| `status` | VARCHAR(16) | `CLOSED` (výchozí) / `SUPERSEDED` |
| `supersedes_sequence` | BIGINT (null) | korekce nahrazuje předchozí uzávěrku |
| `closed_at` | TIMESTAMPTZ | razítkováno při uzávěrce, řídí deterministické rendery |
| `model_snapshot` | TEXT (null) | **V7** — zmrazené vstupy renderu jako JSON (`iban`, `holderName`, `entries`), zachycené při uzávěrce, aby byl re-render bajt po bajtu identický (#3986). NULL pro období uzavřená před V7, která stále přehrávají živá data; záměrně se nedoplňuje zpětně, protože živé projekce už mohly odplout a zmrazení dnešní odpovědi by z odchylky udělalo kanonický stav |

Indexy: `ux_statement_period_window` (UNIQUE `account_id, pocket_currency, period_from, period_to` — idempotenční klíč), `ux_statement_period_legal_seq` (UNIQUE `account_id, pocket_currency, legal_sequence_number` — monotónní právní sekvence), `ix_statement_period_account` (`account_id, period_to DESC`).

### `statement_outbox` (V1) — transakční outbox (ADR-0050)
`id` PK, `event_id` (UNIQUE), `aggregate_id`, `event_type`, `payload` (TEXT), `status` (`PENDING`/`SENT`/`FAILED`/`DEAD`), `attempt_count`, `sent_at`, `last_error`, `created_at`, `updated_at`. Index `ix_statement_outbox_status` (`status, created_at`).

### `account_registry` (V2) — read-only projekce pro enumeraci
`account_id` PK, `party_id`, `currency` (VARCHAR(3)), `registered_at`. Sestaveno konzumací `AccountCreated` streamu account-service (endpoint "všechny účty" neexistuje; cross-service čtení DB je zakázáno). Používáno plánovanou uzávěrkou k enumeraci účtů; eventuálně konzistentní, dostačující pro měsíční dávku.

### `statement_close_run` (V3) — telemetrie kadence (ADR-0069 D3)
`id` PK, `trigger` (`SCHEDULED`/`MANUAL`), `status` (`RUNNING`/`COMPLETED`/`COMPLETED_WITH_FAILURES`), `period_from`/`period_to`, `accounts_enumerated`, `pockets_closed`, `pockets_failed`, `pockets_skipped`, `started_at`, `finished_at`. Index `ix_statement_close_run_started` (`started_at DESC`). Provozní výsledek běhu — ne obsah výpisu.

### `statement_close_failure` (V3) — selhání per kapsa
`id` PK, `run_id` (FK → `statement_close_run` ON DELETE CASCADE), `account_id`, `pocket_currency`, `period_from`/`period_to`, `reason` (`RECONCILIATION`/`UPSTREAM`/`UNKNOWN`), `detail` (TEXT), `failed_at`. Indexy `ix_statement_close_failure_run`, `ix_statement_close_failure_pocket` (`account_id, pocket_currency, failed_at DESC`). Selhaná kapsa **není** řádek `statement_period` — období existuje jen když se čistě uzavře; selhání je zaznamenáno zde, aby ho dohánějící běh mohl zopakovat.

## Seznam migrací a rollback

| Verze | Co | Rollback |
|---|---|---|
| V1 `init_statement` | `statement_period`, `statement_outbox` | `DROP TABLE statement_outbox; DROP TABLE statement_period;` |
| V2 `account_registry` | projekce `account_registry` | `DROP TABLE account_registry;` |
| V3 `close_run` | `statement_close_run`, `statement_close_failure` | `DROP TABLE statement_close_failure; DROP TABLE statement_close_run;` |
| V6 `statement_period_restatement` | zúžení indexu okna na řádky mimo `SUPERSEDED` | `DROP INDEX ux_statement_period_window_active;` poté smazat řádky `SUPERSEDED` a obnovit striktní `ux_statement_period_window` (nemohou koexistovat) |
| V7 `statement_period_model_snapshot` | `statement_period.model_snapshot` | `ALTER TABLE statement_period DROP COLUMN model_snapshot;` — beze ztráty pro ostatní sloupce; render při chybějícím snapshotu spadne zpět na živé projekce, takže drop obnoví chování před #3986 pro **všechna** období, nejen ta před V7 |

Dle pravidla projektu: **nikdy neměň migraci po aplikaci na živou DB** (checksum mismatch → pád při startu).

## PII a citlivá pole

| Pole | Umístění | Klasifikace | Zacházení |
|---|---|---|---|
| `account_id` | všechny tabulky | pseudonymní identifikátor | ne přímo fyzická osoba |
| `party_id` | `account_registry` | pseudonymní identifikátor | odkazuje na party-service (vlastník dat fyzické osoby) |
| IBAN | `statement_period.model_snapshot` (**od V7**) | PII | dříve se neukládalo; zmrazeno při uzávěrce jako součást vstupů renderu (#3986), přítomno i v payloadu outbox události |
| jméno majitele | `statement_period.model_snapshot` (**od V7**) | PII | dříve rozlišováno živě při renderu; zmrazeno při uzávěrce, protože právě živé rozlišování přepisovalo hlavičku již vydaných výpisů |
| zůstatky | `statement_period` (kotvy) | finanční | počáteční/koncové kotvy jako dosud |
| řádkové položky | `statement_period.model_snapshot` (**od V7**) | finanční | řádkové položky (částka, data, popis, protistrana) se nyní pro uzavřené období uchovávají; dříve se při každém renderu přehrávaly z transaction-service |

**#3986 tuto sekci změnilo a nejde o kosmetiku.** Reprodukovatelnost uzavřeného výpisu vyžaduje uchovat, co na něm stálo, takže IBAN, jméno majitele a popisy řádkových položek se nyní ukládají na **10 let** do `model_snapshot`, místo aby žily jen přechodně během renderu. Druh dat se nemění (tytéž údaje už byly ve vyrenderovaném výstupu i v payloadu události `period.closed`, stejný správce, intra-OpenBank) a stávající klasifikace tabulky je pokrývá — `compliance` / `restricted` / retence 10 let. Změnilo se *umístění*: žádost o výmaz nebo export vůči uzavřenému období musí nyní sáhnout i do tohoto sloupce, ne jen k upstream vlastníkům. Viz [06 — Compliance](./06-compliance.md).

## Retence

10 let na reprodukovatelném záznamu `statement_period` (ČNB / AML). Protože záznam je vstupem deterministického renderu, jeho uchování splňuje PSD2 čl. 58(2) "zpřístupněno, reprodukovatelné beze změny" bez uložení jakýchkoli bajtů souboru.
