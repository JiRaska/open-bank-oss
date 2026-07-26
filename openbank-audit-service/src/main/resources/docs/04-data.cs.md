# Data

## Datastore

- **Engine:** PostgreSQL (reactive `pg-client` pro aplikační cestu, JDBC pro Flyway).
- **Databáze:** `openbank_audit`.
- **Schema:** tabulky žijí ve schématu `public` (granty jsou vydány `IN SCHEMA public`).
- **Hibernate:** `database.generation = none` — schema vlastní Flyway, nikdy se auto-negeneruje.

> Poznámka: kurátorský `governance.yaml` deklaruje `primaryDatastore: PostgreSQL` / `databaseName: openbank_audit`, což odpovídá běžícímu kódu.

## Flyway migrace

| Migrace | Účel |
|---|---|
| `V1__create_audit.sql` | Vytvoří `audit_entries` (BIGSERIAL `id` PK, unikátní `entry_id` UUID, sloupce event/aggregate/actor/payload) a lookup indexy na `aggregate_id`, `event_type`, `occurred_at DESC`, partial index na `actor_id`. Granty na `public`. |
| `V2__compliance_fields.sql` | EBA ICT + GDPR obohacení: přidává `session_id`, `user_agent`, `ip_address`, `data_sensitivity` (default `INTERNAL`), `retention_until`, `is_security_event` (default `FALSE`), `risk_score`. Přidává security/session/retention indexy. **Instaluje neměnnost:** `RULE no_update_audit DO INSTEAD NOTHING` a `RULE no_delete_audit DO INSTEAD NOTHING`. Doplní `retention_until` a instaluje trigger `trg_audit_retention` BEFORE INSERT (`occurred_at + 10 let`). |
| `V3__create_audit_outbox.sql` | Vytvoří `audit_outbox` (BIGSERIAL `id` PK, unikátní `event_id` UUID, `aggregate_id`, `event_type`, `payload`, `status`, `attempt_count`, `sent_at`, `last_error`, timestamps) plus indexy `(status, created_at)` a `aggregate_id`. |
| `V4__hibernate_sequences.sql` | Vytvoří `audit_entries_seq` a `audit_outbox_seq` (`INCREMENT BY 50`) vyžadované alokací id PanacheEntity. Rollback: `DROP SEQUENCE audit_entries_seq, audit_outbox_seq;`. |

## Tabulky

### `audit_entries` (append-only, neměnná)

| Sloupec | Typ | Poznámky |
|---|---|---|
| `id` | BIGSERIAL PK | Surrogate; id alokována z `audit_entries_seq` |
| `entry_id` | UUID, unique, not null | Logické id záznamu (vystaveno v API) |
| `event_type` | VARCHAR(100) | Název události producenta |
| `aggregate_type` | VARCHAR(50) | ACCOUNT / PARTY / TRANSACTION / CONSENT / KYC_CASE / UNKNOWN |
| `aggregate_id` | VARCHAR(100) | Indexováno; primární dotazovací klíč |
| `actor_id` | VARCHAR(100), null | Kdo událost spustil |
| `actor_type` | VARCHAR(50), null | Klasifikace aktéra |
| `payload` | TEXT, not null | Původní JSON události, doslovně |
| `source_service` | VARCHAR(100) | Zdrojová služba |
| `correlation_id` | VARCHAR(100), null | Trace correlation |
| `occurred_at` | TIMESTAMPTZ | Business čas |
| `recorded_at` | TIMESTAMPTZ, default NOW() | Čas ingestu |
| `session_id` | VARCHAR(100), null | (V2) |
| `user_agent` | VARCHAR(500), null | (V2) |
| `ip_address` | VARCHAR(45), null | (V2) — IPv4/IPv6 |
| `data_sensitivity` | VARCHAR(20), default INTERNAL | (V2) PUBLIC/INTERNAL/CONFIDENTIAL/RESTRICTED |
| `retention_until` | TIMESTAMPTZ | (V2) triggerem nastaveno na occurred_at + 10let |
| `is_security_event` | BOOLEAN, default FALSE | (V2) SIEM hint |
| `risk_score` | SMALLINT, null | (V2) |

**Neměnnost:** PostgreSQL pravidla `DO INSTEAD NOTHING` tiše zahodí jakýkoli `UPDATE`/`DELETE`. Stopa je fyzicky append-only; oprava se dělá připojením nového kompenzačního záznamu, nikdy editací.

### `audit_outbox`

Staging transakčního outboxu pro re-emit zaznamenaných událostí (`event_id`, `aggregate_id`, `event_type`, `payload`, `status` ∈ PENDING/SENT/FAILED, `attempt_count`, `sent_at`, `last_error`, `created_at`, `updated_at`). Drainuje `AuditOutboxDispatcher`.

## PII & klasifikace dat

Auditní stopa ukládá **doslovný payload** každé upstream události, takže tranzitivně obsahuje jakékoli PII, které producenti emitují (id účtů, IBANy, id klientů, případně detail transakce). Další přímo osobní pole zachytávaná na této vrstvě:

| Pole | Klasifikace | Zacházení |
|---|---|---|
| `ip_address` | osobní údaj (GDPR) | uchováno pod audit retention režimem |
| `user_agent` | osobní údaj (GDPR) | uchováno pod audit retention režimem |
| `actor_id` / `session_id` | identifikující | spojuje akci s osobou |
| `payload` | smíšené, až RESTRICTED | klasifikováno dle `data_sensitivity`; zacházej jako s nejcitlivějším přítomným polem |

Viz [06 — Compliance](./06-compliance.md) pro analýzu právního základu a výmazu (výmaz je překryt AML/EBA retenční povinností).

## Retence

- **10 let**, vynucováno dvěma způsoby: per-row `retention_until = occurred_at + INTERVAL '10 years'` nastavené triggerem `trg_audit_retention`, a property služby `openbank.gdpr.audit-retention-days: 3650`.
- Smazání před `retention_until` není jen politika — delete pravidlo na úrovni DB ho zcela blokuje. Purge po expiraci je provozní záležitost (samostatný auditovaný maintenance job), ne ad-hoc `DELETE`.
