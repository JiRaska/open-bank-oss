# Overview

## What the service does

`openbank-analytics-sink` is the **single ingestion path into the analytics/reporting warehouse** (ADR-0022). It:

- **Consumes the platform's domain-event stream** — the same outbox-published Kafka topics the audit service ingests (`account`, `transaction`, `balance`, `party`, `kyc`, `consent` events). It is fed from the existing event stream (ADR-0003), so reporting adds **zero read load** on the operational databases.
- **Normalises** each raw event into a canonical `AnalyticsEnvelope` and **masks PII at the boundary** (`PayloadMasker`) before anything durable is written — the bronze layer is kept ≥10 years and must never hold raw identifiers.
- **Writes the medallion warehouse** in ClickHouse: **bronze** (append-only log of record), **silver** (current-state-per-aggregate views, last-writer-wins), **gold** (BI rollups).
- **Hardens the warehouse for regulators** (ADR-0023): tamper-evidence anchors, dead-letter quarantine, four-eyes recovery loads, reconciliation against the source of record, GDPR Art. 17 erasure, data-residency guard.

## What the service **does NOT** do

- ❌ Does not own an OLTP / PostgreSQL database — its store of record is the ClickHouse warehouse (ADR-0022). No Hibernate, no Flyway.
- ❌ Does not use CDC/Debezium/WAL extraction — it is **not** a second read path on the operational DBs.
- ❌ Does not produce domain events — it is a pure **consumer**; there is no outbox.
- ❌ Does not sit in any customer request path — it is asynchronous, off the money path.
- ❌ Does not compute balances, post ledger entries, or execute payments — those are `balance-service` / `ledger-service` / payment services.

## Position in the domain

```
  account-service ┐
  transaction-svc │  outbox → Kafka domain events
  balance-service │  (account/transaction/balance/
  party-service   │   party/kyc/consent .events)
  kyc-service     │            │
  consent-service ┘            ▼
                     ┌──────────────────────┐
                     │  analytics-sink      │  PII-masked at boundary
                     │  (AnalyticsConsumer) │
                     └──────────┬───────────┘
                                │ AnalyticsSink port
                                ▼
                     ┌──────────────────────┐      ┌─────────────────┐
                     │ ClickHouse warehouse │◄─────│ BI: Metabase /  │
                     │ bronze→silver→gold   │ read │ Superset        │
                     └──────────┬───────────┘      └─────────────────┘
                                │ integrity anchors
                                ▼
                     S3 Object Lock (WORM) — tamper-evidence
```

## Key use cases

| Use case | API | Event / mechanism |
|---|---|---|
| Ingest a domain event into bronze | — | Kafka `analytics-events-in` → `AnalyticsConsumer` |
| Quarantine an un-projectable / unknown-schema event | — | `DeadLetterSink` → `dead_letter_events` |
| Reconcile warehouse vs source of record | `POST /api/v1/analytics/reconciliation/run` | `ReconciliationJob` (also scheduled, cron) |
| Read last reconciliation evidence | `GET /api/v1/analytics/reconciliation/last` | — |
| Propose a recovery reload (backfill/correction) | `POST /api/v1/analytics/backfill/proposals` | four-eyes `Proposal` state machine |
| Approve a reload (different operator) | `POST /api/v1/analytics/backfill/proposals/{id}/approve` | maker-checker (self-approval ⇒ 409) |
| Execute an approved reload | `POST /api/v1/analytics/backfill/proposals/{id}/execute` | `backfill_audit` evidence row |
| GDPR Art. 17 erasure in analytics | `POST /api/v1/analytics/erasure` | crypto-shred, or refuse under statutory hold |

## Callers / feeders

- **Feeders (Kafka, asynchronous):** account, transaction, balance, party, kyc, consent services — via their existing outbox topics. analytics-sink is a passive consumer.
- **Operators / auditors / compliance (admin UI, via Keycloak token):** the REST operator surface (reconciliation, backfill, erasure).
- **BI tools (Metabase / Superset):** read the gold/silver layer directly in ClickHouse — they never touch operational databases.

## Dependencies

- **ClickHouse** (`openbank_analytics` database) — the warehouse store of record; activated when `openbank.analytics.sink.type=clickhouse` (default sink is an offline `LoggingAnalyticsSink`, so the service boots with zero infra).
- **Kafka** — inbound domain-event topics, consumer group `analytics-sink`.
- **Keycloak** — OIDC auth for the operator REST surface.
- **Vault (optional)** — Transit-based crypto-erasure (`erasure.backend=vault`).
- **S3 Object Lock (optional)** — WORM integrity anchors (`worm.backend=s3`).
- **Apicurio (optional)** — schema catalogue source (`schema.backend=apicurio`).
- **openbank-libs** — `analytics.AnalyticsEnvelope`, `AnalyticsProjections`, `BackfillRequest`, `Proposal`/maker-checker, `RetentionPolicies`, `Integrity`, security `Roles`/`PiiMask`, BuildInfo, DocsResource.

## Business value

- **Reporting without operational risk** — analytics is fed from events, never by querying the live banking databases, so dashboards can never slow or lock the money path.
- **A 10-year, replayable log of record** — bronze is append-only and tamper-evident; any historical "report as-of" cut-off is exact.
- **Regulator-ready by design** — reconciliation evidence, integrity anchors, four-eyes recovery, residency guard and GDPR erasure are built in (ADR-0023), addressing CNB/EBA/DORA/GDPR/BCBS 239 findings.
- **Cheap to scale to zero** — being off the request path, it is a FinOps scale-to-zero candidate (ADR-0057).
