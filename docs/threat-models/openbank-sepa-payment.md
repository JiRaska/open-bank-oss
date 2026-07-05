<!--
SPDX-License-Identifier: Apache-2.0
Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
-->
# Threat model — sepa-payment-service

- **Date:** 2026-06-17
- **Status:** Lightweight STRIDE/DFD (ADR-0030 D2). **Money-path** bounded context.
- **Service ADR:** see `docs/adr/`; platform controls per ADR-0029/0030/0034.

## 1. Scope & purpose

SEPA Credit Transfer (standard, non-instant): create payment, query, status. Cross-border EUR
value transfer — a primary fraud target; clears via batch/clearing rather than instantly.

## 2. Data flow (DFD)

```
[Operator/Payments role, channels] --> (REST /api/v1/sepa-payments) --> [sepa-payment-service] --> [(Postgres: sepa_payments)]
                                                                              |
                                                                              +--> [(sepa_payment_outbox)] --> [Kafka events] --> clearing/ledger
                                                                              |
                                                                              +--> [fraud-service] (shadow, OIDC CC / mTLS, fail-open)
                                                                              |
                                                                              +--> [clearing-simulator] (pacs.008 out / pacs.002 in; OIDC CC; ADR-0104 D3; flag-gated)
```

- **External entities:** payment-initiating channels/operators, downstream clearing & ledger,
  clearing-simulator (scheme network proxy; swap-point for real SCT scheme connector).
- **Trust boundaries:** caller↔service (mTLS+OIDC+OPA); service↔Postgres/Kafka;
  service↔fraud-service (OIDC client-credentials + mTLS, internal cluster-only, shadow/read-only);
  service↔clearing-simulator (OIDC client-credentials; cluster-internal; pilot flag off by default).
- **Assets:** payment instructions, amounts, debtor/creditor IBANs.

## 3. Authn/Authz

- `@RolesAllowed("ROLE_OPERATOR","ROLE_ADMIN","ROLE_PAYMENTS")` on initiation; read includes `ROLE_VIEWER`.
- OPA enforce; SCA for customer-initiated transfers.

## 4. STRIDE

| Threat | Vector | Mitigation |
|---|---|---|
| **S**poofing | Forged initiation | OIDC + role; mTLS for service callers |
| **S**poofing | Forged `pacs.002` ACSC from clearing-simulator (ADR-0104 D3) | clearing-simulator is cluster-internal only; OIDC CC verifies identity; `Pacs002Reader` validates XML schema before parsing; scheme accept moves payment to PROCESSING (money does not leave until settlement) |
| **T**ampering | Alter amount/IBAN in flight | Server-validated, immutable once accepted; audit |
| **R**epudiation | Deny initiating a transfer | AuditEvent + SCA evidence + correlation id |
| **I**nfo disclosure | Payment history harvesting | AuthZ scoping; `ROLE_VIEWER` owner-scoped read |
| **I**nfo disclosure | Domain metrics leak PII / enable per-payment inference via high-cardinality labels | `DomainMetrics` low-cardinality contract (ADR-0077): the `openbank.outbox.backlog` gauge is tagged only by `service` (`"sepa-payment"`) — never a payment id, debtor/creditor IBAN, amount, or any PII. The gauge exposes only a read-only **count** of processable (PENDING + FAILED) outbox rows, cached and refreshed off the scrape thread (no DB query on the Prometheus worker thread). `/q/metrics` is cluster-internal |
| **D**oS | Initiation flooding | Rate limit; idempotency |
| **E**oP | Viewer initiates transfer | Distinct `ROLE_PAYMENTS`; deny-by-default |

## 5. Residual risks / assumptions

- **Idempotency-key required** — duplicate transfer on retry must be rejected.
- IBAN/sanctions screening expected upstream (sanctions-service) before release.

## 5a. Return path (pacs.004) — STRIDE supplement

Introduced by ADR-0109: `POST /api/v1/sepa-payments/returns` receives inbound `pacs.004.001.09`
from clearing-simulator (cluster-internal, `ROLE_SERVICE`). New trust boundary:
`clearing-simulator → sepa-payment-service → transaction-service /reverse`.

| STRIDE | Threat | Mitigation |
|---|---|---|
| **S**poofing | Rogue caller posts a forged pacs.004 to `/returns` | Endpoint requires `ROLE_SERVICE` (OIDC client-credentials); cluster-internal only (NetworkPolicy); clearing-simulator identity verified by OIDC CC token |
| **T**ampering | Malformed or XXE-injected pacs.004 XML | `Pacs004Reader` (openbank-libs) configures `XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES = false` and `IS_RESOLVING_ENTITY_REFERENCES = false` before parsing |
| **R**epudiation | Denial of having processed a return | All `/returns` invocations logged via existing `AuditService` with correlation id, `OrgnlEndToEndId`, reason code, and actor identity |
| **I**nfo disclosure | Return reason codes (AC04, AM09, etc.) visible to unauthorised parties | Reason codes and return details accessible to `ROLE_OPERATOR`/`ROLE_ADMIN` only; `ROLE_VIEWER` sees payment status (`RETURNED`) but not raw reason code |
| **D**oS | Replay of the same pacs.004 | `RETURNED` transition is idempotent — a second call with the same `OrgnlEndToEndId` returns 409 (already RETURNED), no double-reversal |
| **E**oP | Reversal credited to wrong account | `transaction-service /reverse` validates that the transaction being reversed is owned by the payment's `debtorAccountId`; cross-account reversals are rejected with 403 |

**DFD update:** adds `clearing-simulator → sepa-payment /returns → transaction-service /reverse` edge.
**Risk class:** integrity (money-path reversal) + availability (idempotency).
**Rollback:** feature flag `openbank.sepa.returns.enabled` (off by default); flag OFF = 404 on `/returns`.

## 6. Change log

- **2026-05-30** — Added `sepa_payments_seq`, `sepa_payment_outbox_seq` (Hibernate fix). Additive
  DDL only — no new flow/surface/boundary. Risk class = **availability**, mitigated by
  `HibernateSequenceGuardTest`. Rollback: `DROP SEQUENCE`.
- **2026-06-11** — Added the `openbank.outbox.backlog` domain-metric gauge (ADR-0077 / ADR-0079):
  `SepaPaymentOutboxBacklogGauge` + `SepaPaymentOutboxRepository.countProcessable()`. Touches the new
  **I — information disclosure** (metric-cardinality) row above. No new endpoint, data flow, or trust
  boundary — read-only `count(PENDING+FAILED)` over the existing `sepa_payment_outbox` table, cached and
  sampled off the scrape thread; gauge labelled only by `service`. **Risk class = confidentiality**
  (label cardinality); mitigated by `SepaPaymentOutboxBacklogGaugeTest`. No DB change; rollback = revert
  the commit.
- **2026-06-17** — ADR-0084 fraud shadow scoring (observe-only). New outbound trust boundary:
  `sepa-payment → fraud-service (POST /api/v1/fraud/score, OIDC client-credentials)`.
  **Shadow = fail-open and never-enforce**: `SepaPaymentService.scoreFraudShadow()` wraps the call
  in `.onFailure().recoverWithItem {}` — any fault (timeout, circuit-open, 5xx) is swallowed; the
  payment outcome is unchanged. `FraudScoringAdapter` applies `@CircuitBreaker` (30% failure ratio,
  10-request window) + `@Timeout(3 s)`. No retry (avoid double-scoring on the same payment).
  **Risk class = availability** (fault in fraud-service cannot block a payment) and **confidentiality**
  (payment amount, debtor/creditor IBAN, currency sent to fraud-service; mitigated by mTLS +
  OIDC client-credentials for service-to-service authn; fraud-service is internal, cluster-only).
  **DFD update**: added `sepa-payment → fraud-service` edge (see §2). No DB schema change;
  rollback = revert adapter + port commits.
- **2026-06-24** — ADR-0109 R-transaction return path (pacs.004). New inbound trust boundary:
  `clearing-simulator → sepa-payment /returns → transaction-service /reverse`. STRIDE supplement
  added in §5a above. **Risk class = integrity + availability**. Rollback = `openbank.sepa.returns.enabled=false`.
- **2026-06-23** — ADR-0104 D3: real ISO 20022 `pacs.008` scheme submission via `clearing-simulator`.
  New outbound trust boundary: `sepa-payment → clearing-simulator` (POST
  `/api/v1/clearing/credit-transfers`, pacs.008 XML; pacs.002 XML response; OIDC client-credentials).
  **Flag-gated** (`openbank.sepa.scheme-submission.enabled`, off by default). Fails **closed**: gateway
  unreachable → payment stays VALIDATED (never silently released). `ACSC` → PROCESSING, `RJCT` →
  REJECTED with mapped reason (`SepaRejectReason`). **New STRIDE row**: forged `pacs.002` ACSC from a
  rogue simulator → mitigated by cluster-internal isolation, OIDC CC identity check, schema validation.
  **Risk class = integrity** (scheme verdict gates money-in-flight state) and **confidentiality**
  (debtor/creditor IBAN, amount, BIC sent to simulator; mitigated by OIDC CC + cluster-only ingress).
  **DFD update**: added `clearing-simulator` edge (see §2). No DB schema change; rollback = flag OFF.
- **2026-07-05** — ADR-0122 Phase 2: `build.gradle.kts` now declares `openbank-libs-domain` +
  `openbank-libs-runtime` directly instead of the umbrella `openbank-libs` (which already re-exported
  both via `api()`). Pure Gradle dependency-graph change — no source import changed, no new transitive
  dependency introduced, no behavior change. Attack surface, trust boundaries, and STRIDE rows above are
  unaffected. No DB change; rollback = revert the commit.
