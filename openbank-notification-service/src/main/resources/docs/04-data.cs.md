# Data

## Úložiště

- **Engine:** PostgreSQL 16 (reaktivní `vertx-pg-client` + JDBC pro Flyway).
- **Databáze:** `openbank_notifications`.
- **Schéma:** tabulky jsou ve schématu `public`. *(Governance manifest deklaruje `notifications_schema` jako logický název vlastněného schématu; fyzické migrace vytvářejí objekty v `public`. Deklarováno-vs-fyzicky: za autoritativní pro běžící DB berte `public`.)*
- **ORM:** Hibernate Reactive s Panache; `hibernate-orm.database.generation = none` (schéma vlastní Flyway).
- **Migrace:** Flyway, `migrate-at-start = true`. Hibernate Panache alokuje ID ze sekvencí `<table>_seq` (allocationSize 50) — každá tabulka má odpovídající `CREATE SEQUENCE … INCREMENT BY 50`, vynuceno `HibernateSequenceGuardTest`.

## Flyway migrace

| Verze | Soubor | Změna |
|---|---|---|
| V1 | `V1__create_notifications.sql` | tabulka `notifications` + indexy na `party_id`, `status` |
| V2 | `V2__create_notification_outbox.sql` | tabulka `notification_outbox` + indexy na `(status, created_at)`, `aggregate_id` |
| V3 | `V3__create_dispatch_control.sql` | `dispatch_control_log` + `dispatch_resume_proposal` (ADR-0047 break-glass) |
| V4 | `V4__dispatch_control_sequences.sql` | `dispatch_control_log_seq`, `dispatch_resume_proposal_seq` (oprava Hibernate sekvencí) |
| V5 | `V5__notification_sequences.sql` | `notifications_seq`, `notification_outbox_seq` (oprava Hibernate sekvencí) |
| V6 | `V6__create_device_tokens.sql` | tabulka `device_tokens` + unique `(platform, token)`, index `(party_id, status)`, `device_tokens_seq` |

Každý soubor migrace nese inline **poznámku k rollbacku** (např. V6: `DROP TABLE device_tokens; DROP SEQUENCE device_tokens_seq;`). Nikdy nepřepisujte aplikovanou migraci (selhání startu kvůli checksum mismatch) — při driftu živé DB použijte `QUARKUS_FLYWAY_REPAIR_AT_START=true`.

## Tabulky

### `notifications`

| Sloupec | Typ | Pozn. |
|---|---|---|
| `id` | BIGSERIAL PK | náhradní |
| `notification_id` | UUID UNIQUE | business id (vraceno přes REST) |
| `party_id` | UUID | příjemce party — **pseudonymní identifikátor (vázaný na PII)** |
| `channel` | VARCHAR(10) | EMAIL / SMS / PUSH / IN_APP |
| `template` | VARCHAR(50) | název enum šablony |
| `recipient` | VARCHAR(255) | **PII** — e-mailová adresa / telefon / cíl zařízení |
| `subject` | VARCHAR(500) | vyrenderovaný předmět (může obsahovat obsah) |
| `body` | TEXT | vyrenderované tělo — **může obsahovat PII** (jména, maskované částky, OTP) |
| `status` | VARCHAR(10) | PENDING / SENT / FAILED / BOUNCED |
| `metadata` | JSONB | volná forma, default `{}` |
| `sent_at` | TIMESTAMPTZ | čas doručení |
| `created_at` | TIMESTAMPTZ | default `NOW()` |

### `notification_outbox`

Obecný transakční outbox: `id`, `event_id` (UUID UNIQUE), `aggregate_id`, `event_type`, `payload` (TEXT), `status`, `attempt_count`, `sent_at`, `last_error`, `created_at`, `updated_at`. Pollováno `NotificationOutboxDispatcher`.

### `device_tokens` (registr push)

| Sloupec | Typ | Pozn. |
|---|---|---|
| `id` | BIGSERIAL PK | |
| `device_id` | UUID UNIQUE | business id |
| `party_id` | UUID | vlastnící party (vázáno na PII) |
| `app_instance` | VARCHAR(255) | stabilní id instalace |
| `platform` | VARCHAR(10) | FCM / APNS |
| `token` | TEXT | **push token poskytovatele — PII-adjacentní**; maskovaný v logu, nikdy nevracen přes REST |
| `app_version` / `os_version` | VARCHAR(40) | volitelná metadata klienta |
| `status` | VARCHAR(10) | ACTIVE / INACTIVE / INVALID |
| `last_used_at`, `created_at`, `updated_at` | TIMESTAMPTZ | |

Unique `(platform, token)` → opětovná registrace provede upsert; index `(party_id, status)` je vyhledávací cesta pro rozesílání.

### `dispatch_control_log` (append-only žádaný stav)

`control_key`, `state` (ENABLED/HALTED), `version_no`, `reason`, `actor`, `effective_from`, `deferred_review_required`, `created_at`. Index `(control_key, version_no DESC)` — každá replika čte nejnovější verzi pro daný klíč. Append-only ⇒ rekonstruovatelné k bodu v čase (kdo/kdy/proč).

### `dispatch_resume_proposal` (four-eyes)

`proposal_id` (UNIQUE), `control_key`, `reason`, `proposed_by`, `proposed_at`, `state` (PROPOSED/APPROVED/REJECTED/WITHDRAWN/EXECUTED), `decided_by`, `decided_at`, `decision_reason`, `executed_at`.

## Inventář PII

| Pole | Klasifikace | Kontrola |
|---|---|---|
| `notifications.recipient` | přímé PII (e-mail / telefon) | maskováno v logu (PiiMask); nevystaveno v oversight signálech |
| `notifications.body` / `subject` | možné PII / tajemství (OTP) | OTP/tajné šablony nikdy neegrešují do oversightu |
| `notifications.party_id`, `device_tokens.party_id` | pseudonymní identifikátor | vazba na party-service |
| `device_tokens.token` | PII-adjacentní token poskytovatele | jen pro zápis přes REST, maskovaný v logu |
| `dispatch_control_log.actor` / `dispatch_resume_proposal.*_by` | identita operátora | audit-logováno, jen interní |

Klasifikace dat (governance manifest): **confidential**.

## Retence

- **Deklarovaná retenční politika:** **2 roky** (governance manifest `retentionPolicy: 2 years`).
- Notifikace jsou komunikační záznamy, ne zákonné účetní záznamy — **nepodléhají** 10leté AML retenci, kterou nesou služby na peněžní cestě.
- Device tokeny jsou uchovány po dobu registrace zařízení; tokeny odmítnuté poskytovatelem jsou označeny `INVALID` a vypadnou z rozesílání (služba je dnes automaticky nemaže — purge je **TBD** / provozní).
- Logy řízení výpravy jsou append-only a uchovávané pro okno provozní evidence (DORA čl. 17).

> Plánovaná retenční/purge úloha v této službě **zatím není implementována** (TBD); retence je aktuálně deklarovaná politika vynucovaná provozně.
