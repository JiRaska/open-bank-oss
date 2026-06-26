# Compliance

`openbank-transaction-service` is a **money-path service** (`rules.yaml: money_path_services`). Every change needs the `money-path` label, **2 approvals + a threat model** (`docs/threat-models/<service>.md`, ADR-0030). It sits on the synchronous payment path and is the regulator-facing system of record for transactions, so the compliance surface is broad.

## Regulatory framework

| Regulation | Relation to this service | Implementation |
|---|---|---|
| **PSD2** (Reg. (EU) 2015/2366) | Transaction execution + Open Banking history; end-to-end id, channel, IP captured | `end_to_end_id`, `channel`, `ip_address`, `correlation_id` columns; authenticated read API |
| **AMLD** (Anti-Money Laundering Directive) | Transactions are screened upstream; outcome recorded for evidence | `aml_screened` + `aml_screened_at` columns, partial index on unscreened rows; screening gate lives in the payment services (ADR-0032) |
| **GDPR** | IBAN/BBAN, counterparty name, IP are PII | `PiiMask` in logs, 7-year retention overrides erasure for booked transactions |
| **DORA** (Reg. (EU) 2022/2554) | Operational resilience of a critical money-path function | health probes, fault-tolerant ledger/balance clients, outbox circuit breaker, audit events, SLO, runbooks |
| **NIS2** | Network & information security | mTLS in-cluster, security headers, audit log, no `@PermitAll` endpoints |
| **CNB / ČNB** (books of account, reporting) | Transaction store + cross-border reporting codes | partitioned 7-year store, `regulatory_reporting_code`, ISO 20022 fields |
| **ISO 20022 / BIAN** | Transaction message + search alignment | `bank_transaction_code`, `purpose_code`, `category_purpose`, `mandate_id`, `creditor_scheme_id`, etc. |

## GDPR mapping

### Lawful basis (Art. 6)
- **Contract** (Art. 6(1)(b)) — executing and recording a customer's transactions is necessary to perform the payment contract.
- **Legal obligation** (Art. 6(1)(c)) — AML record-keeping, CNB books of account, tax / cross-border reporting.

### Data subject rights

| Right | Application |
|---|---|
| Access (Art. 15) | `GET /api/v1/transactions?accountId=…` and `/search` return the subject's transaction data |
| Rectification (Art. 16) | bookings are immutable financial records; corrections are made by **reversal/adjustment** transactions, never in-place edits |
| Erasure (Art. 17) | **Not applicable** — AMLD / CNB record-keeping overrides (7 years) |
| Restriction (Art. 18) | enforced upstream (account freeze in account-service blocks new transactions) |
| Portability (Art. 20) | transaction history export via the read API (CSV/JSON downstream) |
| Object (Art. 21) | N/A (no marketing processing) |

### Data flows out

- → **ledger-service** (REST, synchronous): transaction id, amounts, dates, journal lines — same controller, intra-OpenBank.
- → **balance-service** (REST, synchronous): account id, amount, currency, reference id (hold/debit/credit) — same controller.
- → **fx-service** (REST, synchronous): currency pair only — no account / PII.
- → **audit-service** (Kafka, outbox): transaction lifecycle event payload — same controller.
- → **notification-service** (Kafka, outbox): lifecycle event for customer notification.

No data leaves the EU/EEA region.

### Retention (Art. 5(1)(e))

| Data | Retention after `booking_date` |
|---|---|
| Booked transaction | 7 years (`governance.yaml`; CNB books of account + AMLD record-keeping) |
| Transaction flagged AML-relevant | 7 years (or per AML case lifecycle) |

Partitioning by `booking_date` makes year-granularity archival a partition detach, not a bulk delete.

## DORA mapping (Reg. (EU) 2022/2554)

| Article | Topic | Implementation |
|---|---|---|
| Art. 5 / 6 | ICT risk management framework | hexagonal service, dependency on centralized `openbank-libs` |
| Art. 9 | Protection & prevention | `/api/v1/info` build identity (gitCommit, buildTime), security headers, OIDC |
| Art. 10 | Detection | Micrometer/Prometheus metrics, OpenTelemetry traces, alerting on error rate / latency |
| Art. 11 | Response & recovery | payment-saga **compensation** (reverse journal, refund pocket, release hold); outbox retry; runbooks in [05 — Operations](./05-operations.md) |
| Art. 16 | Incident management | lifecycle events emitted to audit-service for evidence |
| Art. 28 | Third-party risk | no third-party SaaS — ledger/balance/fx are self-hosted internal services |

Resilience controls on the money path: SmallRye Fault Tolerance on the ledger client (`LedgerCallGuard`) and the outbox dispatcher (`@CircuitBreaker`, `@Retry`, `@Timeout`, `@Bulkhead`); a 300 s hold TTL so a reservation can never leak; idempotent ledger posting and compensation refunds.

## AML — screening boundary

Screening (sanctions / AML) is **gated upstream** at the payment-service surfaces (sepa-payment, sepa-instant, domestic-payment, fx — ADR-0032), not inside transaction-service. The transaction record carries `aml_screened` / `aml_screened_at` so the screening outcome is part of the immutable transaction evidence, and a partial index (`idx_transactions_aml … WHERE aml_screened = FALSE`) surfaces any unscreened bookings for reconciliation.

## Audit trail

Every transaction lifecycle change produces a domain event (`TransactionInitiated` → `TransactionCompleted` / `TransactionFailed`) written to the transactional outbox and published to Kafka, where `audit-service` persists it with a tamper-evident chain. `governance.yaml` marks `evidenceExported: true`. Bookings themselves are immutable — corrections are new reversal/adjustment transactions, preserving a complete history.

## Security controls

- ✅ AuthN: Keycloak OIDC, RS256 JWT; service-to-service via `oidc-client`
- ✅ AuthZ: Quarkus `@RolesAllowed` — reads gated to SERVICE/VIEWER/OPERATOR/ADMIN, initiation OPERATOR-only; **no `@PermitAll`** (K7 / ADR-0018), locked by `TransactionSecurityContractTest`
- ✅ Input validation → 400; broken invariants → 422 (shared `CommonExceptionMappers`)
- ✅ Idempotency: caller key + unique DB constraints + idempotent ledger post + tagged compensation refund
- ✅ Rate limiting: 150 max concurrent requests
- ✅ Security headers: CSP, HSTS, X-Frame-Options DENY, X-Content-Type-Options nosniff, Referrer-Policy, Permissions-Policy
- ✅ TLS: mTLS in-cluster, TLS termination at gateway
- ✅ Audit: every state change → audit-service via outbox event
- ⚠️ IBAN/counterparty tokenisation: rely on log masking (`PiiMask`); column-level tokenisation not implemented (tracked as a residual risk in the threat model)
