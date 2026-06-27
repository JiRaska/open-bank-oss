<!--
SPDX-License-Identifier: Apache-2.0
Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
-->
# Threat model — domestic-payment-service

- **Date:** 2026-06-17
- **Status:** Lightweight STRIDE/DFD (ADR-0030 D2). **Money-path** bounded context.
- **Service ADR:** see `docs/adr/`; platform controls per ADR-0029/0030/0034.

## 1. Scope & purpose

Domestic payment initiation and status: create payment, query, status. Initiates value transfer
to a beneficiary — a primary fraud target.

## 2. Data flow (DFD)

```
[Operator/Payments role, channels] --> (REST /api/v1/domestic-payments) --> [domestic-payment-service] --> [(Postgres: domestic_payments)]
                                                                                  |
                                                                                  +--> [(domestic_payment_outbox)] --> [Kafka payment events] --> clearing/ledger
                                                                                  |
                                                                                  +--> [fraud-service] (shadow, OIDC CC / mTLS, fail-open)
                                                                                  |
                                                                                  +--> [clearing-simulator] (pacs.008 out / pacs.002 in; OIDC CC; ADR-0104 D4; flag-gated)
```

- **External entities:** payment-initiating channels/operators, downstream clearing & ledger,
  clearing-simulator (Czech CERTIS proxy; swap-point for real CERTIS connector).
- **Trust boundaries:** caller↔service (mTLS+OIDC+OPA); service↔Postgres; service↔Kafka;
  service↔fraud-service (OIDC client-credentials + mTLS, internal cluster-only, shadow/read-only);
  service↔clearing-simulator (OIDC client-credentials; cluster-internal; pilot flag off by default).
- **Assets:** payment instructions, amounts, debtor/creditor accounts.

## 3. Authn/Authz

- `@RolesAllowed("ROLE_OPERATOR","ROLE_ADMIN","ROLE_PAYMENTS")` on initiation; read includes `ROLE_VIEWER`.
- OPA enforce; SCA expected for customer-initiated payments.

## 4. STRIDE

| Threat | Vector | Mitigation |
|---|---|---|
| **S**poofing | Forged initiation | OIDC + role; mTLS for service callers |
| **S**poofing | Forged `pacs.002` ACSC from clearing-simulator (ADR-0104 D4) | clearing-simulator is cluster-internal only; OIDC CC verifies identity; `Pacs002Reader` validates XML schema before parsing; scheme accept moves payment to SENT_TO_CLEARING, then settlement call to transaction-service (ADR-0108) triggers the debit |
| **T**ampering | ACSC verdict triggers double-booking via settlement retry | Idempotency key `domestic-settlement-<paymentId>` on transaction-service; 409 = already-booked success; debit runs once regardless of Temporal retries |
| **T**ampering | Alter amount/beneficiary in flight | Server-validated instruction; signed/immutable once accepted; audit |
| **R**epudiation | Customer/operator denies initiating | AuditEvent + SCA evidence + correlation id |
| **I**nfo disclosure | Payment history harvesting | AuthZ scoping; `ROLE_VIEWER` read-only, owner-scoped |
| **I**nfo disclosure | Domain metrics leak PII / enable per-payment inference via high-cardinality labels | `DomainMetrics` low-cardinality contract (ADR-0077 / ADR-0079): the `openbank.outbox.backlog` gauge is tagged **only** by `service="domestic"` — never a payment id, IBAN, amount, debtor/creditor identity, or any PII. The value is a read-only `COUNT` of PENDING+FAILED outbox rows refreshed off the scrape thread (a cached `AtomicLong` ticked by a scheduled `suspend` query), so a Prometheus scrape touches neither the DB nor payment data. `/q/metrics` is cluster-internal |
| **D**oS | Initiation flooding | Rate limit; idempotency |
| **E**oP | Viewer initiates payment | Distinct `ROLE_PAYMENTS`; deny-by-default |

## 5. Residual risks / assumptions

- **Idempotency-key required** — duplicate payment on retry must be rejected.
- SCA (sca-service) must gate customer-initiated transfers.

## 6. Change log

- **2026-06-11** — Added the `openbank.outbox.backlog` domain-metric gauge (PENDING+FAILED outbox
  rows) tagged only by `service="domestic"` (ADR-0077 / ADR-0079), with a `countProcessable()` port
  method. Touches the **I — information disclosure** row: the gauge carries no payment id, IBAN,
  amount, or PII (low-cardinality contract) and is a read-only count refreshed off the scrape thread.
  No new endpoint, data flow, or trust boundary. Risk class = **confidentiality (metric cardinality)**,
  mitigated by `DomesticPaymentOutboxBacklogGaugeTest`. No DB change; rollback = revert the commit.
- **2026-05-30** — Added `domestic_payments_seq`, `domestic_payment_outbox_seq` (Hibernate fix).
  Additive DDL only — no new flow/surface/boundary. Risk class = **availability**, mitigated by
  `HibernateSequenceGuardTest`. Rollback: `DROP SEQUENCE`.
- **2026-06-17** — ADR-0084 fraud shadow scoring (observe-only). New outbound trust boundary:
  `domestic-payment → fraud-service (POST /api/v1/fraud/score, OIDC client-credentials)`.
  **Shadow = fail-open and never-enforce**: `DomesticPaymentService.scoreFraudShadow()` wraps the
  call in `try/catch` — any fault (timeout, circuit-open, 5xx) is logged and swallowed; the payment
  outcome is unchanged. `FraudScoringAdapter` applies `@CircuitBreaker` (30% failure ratio) +
  `@Timeout(3 s)`. No retry (avoid double-scoring on the same payment).
  **Risk class = availability** (fault in fraud-service cannot block a payment) and **confidentiality**
  (payment amount, debtor/creditor accounts, currency sent to fraud-service; mitigated by mTLS +
  OIDC client-credentials; fraud-service is internal, cluster-only).
  **DFD update**: added `domestic-payment → fraud-service` edge (see §2). No DB schema change;
  rollback = revert adapter + port commits.
- **2026-06-23** — ADR-0104 D4: real ISO 20022 `pacs.008` submission to Czech CERTIS via
  `clearing-simulator`. New outbound trust boundary: `domestic-payment → clearing-simulator`
  (POST `/api/v1/clearing/credit-transfers`, pacs.008 XML; pacs.002 XML response; OIDC CC).
  BBAN (account number + bank code) converted to Czech IBAN (ISO 13616) before pacs.008 assembly.
  **Flag-gated** (`openbank.domestic.scheme-submission.enabled`, off by default). Fails **closed**:
  gateway unreachable → payment stays VALIDATED. `ACSC` → SENT_TO_CLEARING, `RJCT` → REJECTED with
  mapped reason (`DomesticRejectReason`). **New STRIDE row**: forged `pacs.002` ACSC → mitigated by
  cluster-internal isolation, OIDC CC, schema validation. **Risk class = integrity** (scheme verdict
  gates money-in-flight) and **confidentiality** (IBAN, amount, BIC sent to simulator; mitigated by
  OIDC CC + cluster-only ingress). **DFD update**: added `clearing-simulator` edge (see §2).
  No DB schema change; rollback = flag OFF.
- **2026-06-23** — ADR-0108: settlement via transaction-service after ACSC. New outbound trust boundary:
  `domestic-payment → transaction-service (POST /api/v1/transactions, OIDC CC)`.
  After `clearing-simulator` returns ACSC the `SettlementAdapter` calls `transaction-service` to debit
  the payer's account and book the ledger journal. Idempotency key = `domestic-settlement-<paymentId>`
  prevents double-booking on Temporal retries; HTTP 409 is treated as already-booked success.
  OIDC token acquired explicitly (not via `OidcClientRequestReactiveFilter`) because the filter loses
  Vert.x context on Temporal activity threads. On `SettlementUnavailableException` payment stays in
  `SENT_TO_CLEARING` (fail-safe); Temporal retries via the `settlePayment` activity. Non-Temporal path
  holds in `SENT_TO_CLEARING` on failure; operator intervention (manual settle) is the recovery path.
  **Risk class = integrity** (funds debited on forged ACSC) — mitigated by same OIDC CC + cluster-only
  clearing-simulator ingress as ADR-0104 D4. **New STRIDE rows**: Spoofing (ACSC path) + Tampering
  (double-booking). No DB schema change; rollback = revert `SettlementAdapter`/`SettlementPort` + remove
  `TRANSACTION_SERVICE_URL` from gitops.
