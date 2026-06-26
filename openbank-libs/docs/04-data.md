# 04 — Data

**openbank-libs neuchovává žádná data.** Není to služba, žádná DB, žádné Kafka topiky, žádný persistent state.

## Co libs poskytuje "kolem dat"

- **`AbstractOutboxEntity`** v `persistence/outbox/` — `@MappedSuperclass` se schémem (10 sloupců). Per-service `@Entity` ho extends; konkrétní tabulka (`account_outbox`, `transaction_outbox`, ...) žije v té službě, ne v libs.
- **`AuditEvent`** v `audit/` — envelope DTO. Skutečné perzistování dělá `openbank-audit-service` (Kafka consumer + Postgres `audit_entries` tabulka).
- **`IdempotencyStore`** v `idempotency/` — port. `RedisIdempotencyStore` impl čte/zapisuje do Redis pod klíčem `idempotency:<key>`, TTL default 24h. Per-service producer ji wires.
- **Typesafe IDs** — `AttributeConverter`s mapují value object ↔ UUID column. Žádná data, jen mapování.

## Per-service docs

Skutečné schéma každé služby — Flyway migrace, entity, indexes, partitioning, retention — žije v `openbank-<service>/docs/04-data.md`. Příklady:

- `openbank-ledger-service/docs/04-data.md` — `journal_entries`, `journal_lines`, partitioning po datu, GL chart of accounts
- `openbank-account-service/docs/04-data.md` — `accounts`, `account_balances`, `account_authorizations`, optimistic locking strategy
- `openbank-transaction-service/docs/04-data.md` — `transactions`, `payment_sagas`, idempotency key index

## Shared schemes (pokud někdy vzniknou)

Pokud OpenBank přijme `db/schema-common.sql` (např. shared `audit_columns` typ), bude se popisovat zde a Flyway baseline migrace dorazí přes openbank-infra.

Aktuálně nic takového neexistuje.
