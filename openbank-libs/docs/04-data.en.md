# 04 — Data

**openbank-libs holds no data.** It is not a service, has no DB, no Kafka topics, no persistent state.

## What libs provides "around data"

- **`AbstractOutboxEntity`** in `persistence/outbox/` — a `@MappedSuperclass` with a 10-column schema. Per-service `@Entity` classes extend it; the concrete table (`account_outbox`, `transaction_outbox`, ...) lives in the consuming service, not in libs.
- **`AuditEvent`** in `audit/` — an envelope DTO. Actual persistence is handled by `openbank-audit-service` (Kafka consumer + Postgres `audit_entries` table).
- **`IdempotencyStore`** in `idempotency/` — a port. `RedisIdempotencyStore` reads from / writes to Redis under the `idempotency:<key>` key, default TTL 24 h. A per-service producer wires it.
- **Typesafe IDs** — `AttributeConverter`s map a value object ↔ a UUID column. No data, only mapping.

## Per-service docs

The real schema for each service — Flyway migrations, entities, indexes, partitioning, retention — lives in `openbank-<service>/docs/04-data.md`. Examples:

- `openbank-ledger-service/docs/04-data.md` — `journal_entries`, `journal_lines`, date-based partitioning, GL chart of accounts
- `openbank-account-service/docs/04-data.md` — `accounts`, `account_balances`, `account_authorizations`, optimistic-locking strategy
- `openbank-transaction-service/docs/04-data.md` — `transactions`, `payment_sagas`, idempotency-key index

## Shared schemas (if any ever appear)

If OpenBank ever introduces `db/schema-common.sql` (e.g. a shared `audit_columns` type), it will be described here and the Flyway baseline migration will land via openbank-infra.

Nothing of the sort exists today.
