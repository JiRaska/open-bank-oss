# Data

## Datastore

- **Engine:** PostgreSQL 16 (reaktivní Vert.x PG klient + Hibernate Reactive Panache).
- **Databáze:** `openbank_statement` (dedikovaná, ADR-0002 — žádné cross-service čtení DB).
- **Migrace:** Flyway, `migrate-at-start: true`, `db/migration/V1..V3`. Generace schématu Hibernate je `none` — schéma vlastní Flyway.
- **Datová doména / klasifikace (governance.yaml):** `compliance`, `restricted`, retence **10 let**, role linie `both`.

Definující datový princip (ADR-0035): **ukládej záznam, ne soubor.** Ukládá se pouze malý záznam `statement_period`. camt.053 / MT940 / PDF jsou deterministické, bajt po bajtu identické projekce renderované na vyžádání z tohoto záznamu plus zaúčtovaných položek přehraných z transaction-service — nikdy se neskladují.

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

Dle pravidla projektu: **nikdy neměň migraci po aplikaci na živou DB** (checksum mismatch → pád při startu).

## PII a citlivá pole

| Pole | Umístění | Klasifikace | Zacházení |
|---|---|---|---|
| `account_id` | všechny tabulky | pseudonymní identifikátor | ne přímo fyzická osoba |
| `party_id` | `account_registry` | pseudonymní identifikátor | odkazuje na party-service (vlastník dat fyzické osoby) |
| IBAN | **neuchováváno** | PII | přítomno jen v in-memory `StatementModel` a vyrenderovaném výstupu / payloadu outbox události; nikdy neuloženo jako řádek |
| jméno majitele | **neuchováváno** | PII | rozlišeno při renderu z account/party; nikdy neuloženo |
| zůstatky / položky | `statement_period` (jen kotvy); položky přehrávané z transaction-service | finanční | ukládají se jen počáteční/koncové kotvy; řádkové položky se neskladují |

Protože vyrenderované výpisy se neukládají, osobní data na výpisu (IBAN, jméno majitele, popisy řádkových položek) žijí jen přechodně během renderu a v upstream službách, které je vlastní. Payload outbox události nese IBAN a zůstatky — stejný správce dat, intra-OpenBank (viz [06 — Compliance](./06-compliance.md)).

## Retence

10 let na reprodukovatelném záznamu `statement_period` (ČNB / AML). Protože záznam je vstupem deterministického renderu, jeho uchování splňuje PSD2 čl. 58(2) "zpřístupněno, reprodukovatelné beze změny" bez uložení jakýchkoli bajtů souboru.
