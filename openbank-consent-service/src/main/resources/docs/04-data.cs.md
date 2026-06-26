# Data

## Datové úložiště

- **Engine:** PostgreSQL 16, přistupováno reaktivně (Vert.x PG klient + Hibernate Reactive Panache).
- **Databáze:** `openbank_consents`. Tabulky jsou ve schématu `public` (per-service governance manifest pojmenovává logické schéma `consents_schema`; fyzicky jsou tabulky v `public`).
- **Generování schématu:** `none` — schéma vlastní Flyway, aplikuje se při startu (`migrate-at-start: true`).

## Tabulky

### `consents` — agregát

| Sloupec | Typ | Poznámky |
|---|---|---|
| `id` | `UUID` PK | `gen_random_uuid()` |
| `party_id` | `UUID` | zákazník, který udělil — **PII (pseudonymní identifikátor)** |
| `grantee_id` | `VARCHAR(255)` | eIDAS org id TPP nebo id agenta |
| `grantee_type` | `VARCHAR(50)` | `TPP` / `BANK_AGENT` / `CUSTOMER_AGENT` / `INTERNAL_SERVICE` |
| `grantee_name` | `VARCHAR(255)` | čitelný název |
| `status` | `VARCHAR(50)` | default `PENDING_SCA` |
| `valid_from`, `valid_to` | `TIMESTAMPTZ` | `CHECK (valid_to > valid_from)`; 90denní PSD2 strop vynucen v doméně |
| `sca_session_id` | `UUID` | odkaz na SCA výzvu, která souhlas aktivovala |
| `redirect_uri` | `TEXT` / `VARCHAR(500)` | redirect TPP — **PII-adjacentní** |
| `tpp_transaction_id` | `VARCHAR(255)` | vlastní reference TPP (vstup idempotence) |
| `ip_address` | `VARCHAR(45)` | IP klienta při vytvoření — **PII** |
| `user_agent` | `TEXT` / `VARCHAR(500)` | UA klienta při vytvoření — **PII** |
| `created_at`, `updated_at` | `TIMESTAMPTZ` | defaulty `NOW()` |
| `revoked_at` | `TIMESTAMPTZ` | nastaveno při odvolání |
| `revoked_reason` | `TEXT` | důvod odvolání |
| *(V2 compliance pole)* | | `tpp_name`, `tpp_roles`, `sca_method`, `sca_reference`, `frequency_per_day` (`CHECK 1..4`, default 4), `combined_service_flag`, `last_action_date`, `revoked_by`, `revocation_reason` |

Indexy: `party_id`, `grantee_id`, `status`, `valid_to`, `(party_id, grantee_id)`, parciální indexy na `tpp_name` a `sca_reference`.

### `consent_scopes` — množina scopů (1‑k‑mnoha)

| Sloupec | Typ | Poznámky |
|---|---|---|
| `consent_id` | `UUID` | FK → `consents(id)` `ON DELETE CASCADE` |
| `scope` | `VARCHAR(100)` | PK `(consent_id, scope)` |

### `consent_accounts` — pokryté IBANy (1‑k‑mnoha, volitelné)

| Sloupec | Typ | Poznámky |
|---|---|---|
| `consent_id` | `UUID` | FK → `consents(id)` `ON DELETE CASCADE` |
| `iban` | `VARCHAR(34)` | PK `(consent_id, iban)` — **PII** |

Absence řádků ⇒ souhlas pokrývá všechny účty.

### `consent_outbox` — transakční outbox

| Sloupec | Typ | Poznámky |
|---|---|---|
| `id` | `BIGSERIAL` PK | (id alokovaná ze `consent_outbox_seq`, viz V4) |
| `event_id` | `UUID UNIQUE` | dedup klíč |
| `aggregate_id` | `UUID` | id souhlasu |
| `event_type` | `VARCHAR(128)` | `ConsentGranted` / `ConsentRevoked` / `ConsentRejected` / `ConsentExpired` |
| `payload` | `TEXT` | serializovaný JSON události |
| `status` | `VARCHAR(16)` | `PENDING` / odeslaný stav |
| `attempt_count` | `INTEGER` | default 0 |
| `sent_at` | `TIMESTAMPTZ` | nastaveno při úspěchu |
| `last_error` | `TEXT` | poslední chyba dispatche |
| `created_at`, `updated_at` | `TIMESTAMPTZ` | |

Indexy: `(status, created_at ASC)` pro poll dispatcheru, `aggregate_id`.

## Flyway migrace

| Verze | Soubor | Co dělá |
|---|---|---|
| V1 | `V1__init_consent.sql` | `consents`, `consent_scopes`, `consent_accounts` + indexy |
| V2 | `V2__compliance_fields.sql` | PSD2/RTS compliance sloupce (detaily TPP, SCA metoda, frequency, audit trail) + frequency `CHECK` |
| V3 | `V3__create_consent_outbox.sql` | `consent_outbox` + indexy |
| V4 | `V4__hibernate_sequences.sql` | `CREATE SEQUENCE consent_outbox_seq INCREMENT BY 50` — vyžadováno alokací id v Panache (rollback: `DROP SEQUENCE consent_outbox_seq;`) |

> **Disciplína migrací (CLAUDE.md):** nikdy needituj aplikovanou migraci — spustí to Flyway checksum mismatch při startu. Místo toho přidej novou `V{n}`. Každá migrace nese poznámku k rollbacku.

## Inventář PII

| Pole | Klasifikace | Zacházení |
|---|---|---|
| `party_id` | pseudonymní identifikátor | není přímý identifikátor; rozluštitelný jen přes party-service |
| `account_iban` | finanční PII | maskováno v logu (PiiMask); přístup gated samotným souhlasem |
| `ip_address`, `user_agent` | PSD2 SCA evidence / PII | uchováno jako fraud/audit evidence, nevystaveno v API odpovědích |
| `redirect_uri` | PII-adjacentní | URL řízené TPP |

Celková klasifikace dat: **confidential** (governance manifest). DTO `ConsentResponse` záměrně vynechává `ipAddress`, `userAgent`, `redirectUri` a `tppTransactionId` z odpovědí pro čtení.

## Retence

- **Politika:** 5 let (governance manifest `retentionPolicy: 5 years`), sladěno s PSD2/AML retencí evidence pro záznamy o souhlasech.
- Odvolané/expirované souhlasy se uchovávají po dobu evidence, nemažou se při odvolání, protože jsou auditovatelným důkazem, že přístup byl (a již není) autorizován.

Viz [06 — Compliance](./06-compliance.md) pro zdůvodnění zákonného základu GDPR a retence.
