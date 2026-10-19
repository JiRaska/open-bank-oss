# openbank-transaction-service — Documentation

> **What it is:** the money-path service that **initiates and records transactions** — it orchestrates a payment saga (reserve funds → post to the ledger → capture → complete) and is the system of record for transaction history and BIAN-aligned transaction search. **What it is NOT:** it is not the double-entry book (that's `openbank-ledger-service`), not the balance engine (`openbank-balance-service`), and not a payment-scheme adapter (`sepa-payment`, `domestic-payment`, `swift-service`, … create transactions here).

This documentation is published directly by the service at the management endpoint `/q/openbank/docs` (Docs-as-Service pattern — see [ADR 0019](../../../../docs/adr/0019-docs-as-service.md)). The admin UI fetches it when rendering the Service Docs page.

## Contents

| Section | Audience | What you'll find |
|---|---|---|
| [01 — Overview](./01-overview.md) | Product, audit, management | What the service does, who calls it, where it sits in the domain |
| [02 — Architecture](./02-architecture.md) | Engineering, tech leads | C4 diagrams, hexagonal layers, payment saga, outbox flow |
| [03 — API](./03-api.md) | Service developers, integrators | REST contract, idempotency, error model |
| [04 — Data](./04-data.md) | Data, analytics, DBA | Schema, migrations, retention, PII fields |
| [05 — Operations](./05-operations.md) | DevOps, SRE, release engineers | Build, deploy, runbooks, SLO |
| [06 — Compliance](./06-compliance.md) | Compliance, audit, GRC | DORA, GDPR, PSD2, AML mapping |

## TL;DR

- **Tech stack:** Kotlin / Quarkus 3.x / Hibernate Reactive (Panache) / Reactive PostgreSQL / SmallRye Kafka — see `build.gradle.kts`
- **Port:** 8102 (app), 8085 (management, `/q`)
- **Persistence:** PostgreSQL database `openbank_transactions`, range-partitioned `transactions` table, Flyway migrations V1..V7
- **Outbox:** `transaction_outbox` → Kafka topic `openbank.transactions.transaction.initiated` (also `.completed` / `.failed` event types)
- **Idempotency:** caller-supplied `idempotencyKey` on the initiate command → global registry primary key with transactional partition triggers
- **Orchestration:** synchronous payment saga (hold → ledger post → debit/credit → complete, with compensation)
- **Auth:** Keycloak OIDC; reads require `ROLE_API`/`ROLE_VIEWER`/`ROLE_OPERATOR`/`ROLE_ADMIN`, initiation requires `ROLE_OPERATOR`
- **Money-path service** (see `rules.yaml: money_path_services`) — 2 approvals + threat model on every change

## Global idempotency and V22 rollout

Initiation keys are global across booking dates. V22 reserves each key in
`transaction_idempotency_keys` from a database BEFORE INSERT trigger, in the same
transaction as the transaction and its initiated outbox event. A conflicting writer
waits for the first transaction, then returns its existing ID and stored status.
Both new and replayed initiation responses use HTTP 201. Callers must inspect status:
PENDING/PROCESSING are in flight; FAILED/REVERSED are not successful bookings.
A replay never starts another workflow. This guarantee concerns initiation, not a
claim that all reversal endpoint side effects are exactly once.

V22 takes ACCESS EXCLUSIVE locks on the parent and every attached partition before
scanning and backfilling all history. Plan a maintenance write barrier and a bounded
lock wait through the deployment procedure. Historical duplicate keys abort the
migration with no financial rows changed: a reconciliation owner must assess those
rows, preserve financial history and approve remediation before retrying. Do not
choose a winner automatically, delete rows, ignore conflicts or run V22 without a
transaction. Before V22 commits, old writers are still unprotected across dates;
a failed migration is not a protection against continued old-node writes.

The insert trigger protects old nodes and direct inserts into attached partitions.
New empty partitions inherit it. Attaching a prepopulated partition is a separate
controlled maintenance operation: quiesce all writers, lock the parent and partition,
validate all its keys against history and registry, backfill its claims, attach, and
verify trigger clones and one-to-one registry pointers before allowing writes.
Attaching alone does not backfill pre-existing rows. Do not disable the triggers.
Key, transaction ID and booking date are immutable; normal status updates remain
allowed. Claims survive archival/deletion to prevent key reuse. A missing or corrupt
registry pointer fails closed and requires reconciliation.

Rollback: roll back the application while retaining V22, its registry and triggers.
Do not drop the registry or release claims as an application rollback. Removing this
protection requires a separately reviewed maintenance change, owner approval and
quiescence of writes and retries across all nodes. No financial history is removed
by this migration or its safe application rollback.
