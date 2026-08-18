# Overview

## What the service does

`openbank-audit-service` is the **platform-wide immutable audit trail**. It is a pure event **consumer**: it subscribes to domain events emitted across the OpenBank fleet and writes each one into an append-only ledger that downstream auditors, compliance officers and administrators can query per aggregate.

It records, per event:

- **AuditEntry** — `entryId` (UUID), `eventType`, `aggregateType` (ACCOUNT / PARTY / TRANSACTION / CONSENT / KYC_CASE / UNKNOWN), `aggregateId`, `actorId` / `actorType` (who triggered it), the full original `payload` (JSON, verbatim), `sourceService`, `correlationId`, `occurredAt` (business time) and `recordedAt` (ingest time).
- **Compliance enrichment** (DB columns, see [04](./04-data.md)) — `session_id`, `user_agent`, `ip_address`, `data_sensitivity`, `is_security_event`, `risk_score`, and a DB-enforced `retention_until` (occurred_at + 10 years).

The aggregate id and type are derived from the inbound payload (`accountId` → ACCOUNT, `partyId` → PARTY, `transactionId` → TRANSACTION, `consentId` → CONSENT, `kycCaseId` → KYC_CASE), so the service can absorb heterogeneous event shapes without per-producer coupling.

## What the service **does NOT** do

- ❌ Does not own any business aggregate — it has no accounts, balances, parties of its own.
- ❌ Does not make authorization decisions — that is `openbank-libs/authz` + OPA (ADR-0034). Audit only *records*.
- ❌ Does not run SIEM correlation / alerting — it flags `is_security_event` for an external SIEM to consume, it does not alert.
- ❌ Does not expose write APIs — the only ingest path is Kafka; there is no `POST` endpoint.
- ❌ Does not mutate or delete entries — UPDATE/DELETE are rejected at the database level (immutability rules).

## Position in the domain

```
  ┌──────────────────┐   account.created     ┌─────────────────────────────┐
  │ account-service  │ ───────────────────►  │                             │
  ├──────────────────┤   transaction.initiated│                             │
  │ transaction-svc  │ ───────────────────►  │     openbank-audit-service  │
  ├──────────────────┤   balance.events       │  (Kafka consumer)           │
  │ balance-service  │ ───────────────────►  │                             │
  ├──────────────────┤   party.events         │   ┌──────────────────────┐  │
  │ party-service    │ ───────────────────►  │   │ audit_entries (append │  │
  ├──────────────────┤   kyc.events           │   │  -only, immutable)    │  │
  │ kyc-service      │ ───────────────────►  │   └──────────────────────┘  │
  ├──────────────────┤   consent.events       │                             │
  │ consent-service  │ ───────────────────►  └──────────────┬──────────────┘
  └──────────────────┘                                       │ GET /api/v1/audit/entries/{id}
                                                              ▼
                                                   admin UI / auditor / compliance
```

## Key use cases

| Use case | API / channel | Direction |
|---|---|---|
| Record an account lifecycle event | Kafka `openbank.accounts.account.created` | consume |
| Record a transaction initiation | Kafka `openbank.transactions.transaction.initiated` | consume |
| Record a balance change | Kafka `openbank.balance.events` | consume |
| Record a party / KYC / consent event | Kafka `openbank.party.events`, `openbank.kyc.events`, `openbank.consent.events` | consume |
| Retrieve the audit trail for an aggregate | `GET /api/v1/audit/entries/{aggregateId}` | serve |

## Callers

- **Producers (feed it):** account-service, transaction-service, balance-service, party-service, kyc-service, consent-service — via Kafka, no synchronous coupling.
- **Readers (query it):** admin-ui (operators, auditors, compliance) over a Keycloak token carrying `ROLE_AUDITOR`, `ROLE_ADMIN` or `ROLE_COMPLIANCE`.

## Dependencies

- **PostgreSQL** (`openbank_audit` database, table `audit_entries` in the `public` schema)
- **Kafka** (consumer group `audit-service`, channel `audit-events-in`)
- **Keycloak** — OIDC auth for the read API
- **openbank-libs** — shared runtime plumbing (BuildInfo, ServiceInfoResource, DocsResource, security)

## Business value

- **Single, immutable record of truth** for "what happened" across the platform — one place auditors and regulators look, rather than reconstructing history from each service's logs.
- **Tamper resistance by construction** — the database physically refuses UPDATE/DELETE on audit rows, so the integrity of the trail does not depend on application-layer discipline.
- **Regulatory retention** — every entry is stamped with a 10-year `retention_until` (EBA ICT / CNB / AMLD), enforced at insert time by a DB trigger.
- **Decoupled ingestion** — producers fire-and-forget over Kafka; an audit outage never blocks a business transaction, and replay from `earliest` backfills any gap.
