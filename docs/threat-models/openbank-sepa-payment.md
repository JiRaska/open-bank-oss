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
- Four-eyes approval-decide endpoint: same role set as the gated action, plus a domain-level
  segregation-of-duties check (checker id != maker id) — see §4a.

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

## 4a. Four-eyes approval (ADR-0155) — STRIDE supplement

`PATCH /status` (`sepaPayment.transitionStatus`) is a money-path action OPA (`rest.rego`)
flags `four_eyes_required` (issue #395 fixed the scope/verb match that had silently disabled
this fleet-wide). New endpoint `PATCH /api/v1/sepa-payments/approvals/{id}` lets a DIFFERENT
operator decide the resulting `PendingApproval`; the maker retries `PATCH /status` with an
`X-Approval-Id` header. **`authz.four-eyes.enforce` stays `false` in this PR** — the
`ApprovalStore`/endpoint are wired and tested, but blocking is a deliberate follow-up flip,
not bundled here (see ADR-0155).

| STRIDE | Threat | Mitigation |
|---|---|---|
| **S**poofing | A caller other than an operator decides an approval | `@RolesAllowed("ROLE_OPERATOR","ROLE_ADMIN","ROLE_PAYMENTS")` + OPA `@Authorize(action="sepaPayment.approval.decide")` on the decide endpoint |
| **E**oP | The maker approves their own request (self-approval defeats maker-checker) | `ApprovalStore.decide` throws `SelfApprovalNotAllowedException` (mapped to 403) when `decidedBy == makerId` — enforced in the domain port itself, not just the REST layer, and `makerId`/`decidedBy` both resolve via the same `.principal.name` extraction (interceptor vs. `SecurityIdentity`) so the comparison can't silently mismatch for the same real person |
| **T**ampering | A stale, mismatched, or already-consumed `X-Approval-Id` is replayed to unlock a different request | `AuthorizeInterceptor` requires the approval's `action` + `resourceId` + `makerId` to match the CURRENT request exactly, `status == APPROVED`, and marks it `EXECUTED` (one-time use) on success; any mismatch re-issues a fresh pending approval instead of proceeding |
| **R**epudiation | No record of who approved a gated transition | `PendingApproval.decidedBy` + `decidedAt` recorded in the approval record itself (Redis, TTL-bounded — see ADR-0155 Negative consequences: not yet a permanent audit trail) |
| **I**nfo disclosure | Approval id enumeration reveals payment/action metadata to an unauthorized caller | `find`/`decide` require the caller to already hold a valid, role-gated session; the id itself is a random UUID (`RedisApprovalStore`, not sequential) |
| **D**oS | Flooding `PATCH /status` to exhaust Redis with pending approvals | Bounded by the same rate-limit/idempotency controls as the gated endpoint itself; each `PendingApproval` is TTL-bounded (86400s) so abandoned records expire |

**DFD update:** adds `Operator (checker) → PATCH /api/v1/sepa-payments/approvals/{id} → Redis (approval:*)`
alongside the existing `PATCH /status` edge; the maker's retry reuses the existing DFD edge.
**Risk class:** integrity (segregation of duties) + confidentiality (approval record scope).
**Rollback:** `authz.four-eyes.enforce=false` (default) — the endpoint and store exist but do
not change any existing request's outcome until explicitly flipped.

## 5. Residual risks / assumptions

- **Idempotency-key required** — duplicate transfer on retry must be rejected.
- IBAN/sanctions screening expected upstream (sanctions-service) before release.
- **Four-eyes `PendingApproval` records are TTL-bounded (Redis), not a permanent audit
  trail** (ADR-0155) — a durable-audit requirement for "who approved what, forever" would
  need an additional store; not implemented in this PR.

## 5a. Return path (pacs.004) — STRIDE supplement

Introduced by ADR-0111: `POST /api/v1/sepa-payments/returns` receives inbound `pacs.004.001.09`
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

- **2026-07-24** — Retire the legacy in-service orchestration; Temporal is the sole orchestrator
  (ADR-0120 Phase 6, issue #1917). `createPayment` no longer branches on `openbank.temporal.enabled`
  (removed) — it always dispatches `SepaPaymentWorkflow` (screening → shadow fraud scoring → scheme
  submission → settle). The in-service `applyScreening`/`scoreFraudShadow`/`submitToScheme`/
  `settleProcessingPayment` flow and its now-unused ports (screening/aml/fraud/scheme/settlement) are
  deleted; `persistTransition`, the pacs.004 return path (`reversalPort`), and the query/transition
  endpoints are unchanged. Worker registration is gated separately by `openbank.sepa.worker.enabled`
  (default true; `%test` false) so @QuarkusTest boot does not connect to an absent Temporal frontend;
  a test `WorkflowClientTestProducer` backs the CDI `WorkflowClient` with an in-process
  `TestWorkflowEnvironment`. **No new trust boundary or external caller** — the same screening/fraud/
  scheme/settlement steps now run inside Temporal activities (each already OIDC/mTLS-bounded), with the
  workflow adding durable retries + reverse compensation. The prerequisite that the Temporal path was
  missing shadow fraud scoring was fixed first (#2068). Rollback: revert the commit (the flag +
  in-service flow return). Risk class = **availability/correctness** (durable orchestration replaces a
  best-effort in-process sequence); verified by a sandbox canary (worker registers + polls, real
  payments complete via Temporal end-to-end).
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
- **2026-06-24** — ADR-0111 R-transaction return path (pacs.004). New inbound trust boundary:
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
- **2026-07-07** — ADR-0155 four-eyes enforcement pilot. New endpoint `PATCH
  /api/v1/sepa-payments/approvals/{id}` + `ApprovalConfig` (Redis-backed `ApprovalStore`) +
  `AuthorizeInterceptor` four-eyes gate (openbank-libs-runtime, shared, opt-in). New STRIDE
  supplement §4a. `authz.four-eyes.enforce` defaults `false` — no behavior change to any
  existing request in this PR; flipping it is a tracked follow-up. No DB schema change (Redis,
  TTL-bounded); rollback = revert the commit (or leave `authz.four-eyes.enforce=false`, its
  default).

- **2026-08-02** — **New inbound trust edge: the `delegation` namespace.** `#3414` added
  `delegation` as an allowed ingress peer in this component's `network-policies.yaml`, so
  `delegation-service` can now reach this service's API from inside the cluster. A NetworkPolicy is
  coarse — it decides *reach*, not *permission* — so the actual authorization is unchanged and still
  rests on OIDC (`@RolesAllowed`) plus the OPA sidecar (ADR-0034); this edge widens who may attempt a
  call, not who may succeed. Risk class = **elevation of privilege** if a policy gap exists on an
  endpoint that previously had no in-cluster caller: network reach was an implicit second control for
  such endpoints and is now gone for this peer. Per ADR-0232 delegation-service holds
  `DelegationGrant` and enforcement stays with the product services, which build their own local
  projection — so a compromised or buggy delegation-service should not be able to grant access it
  never had, and that property is the mitigation this edge depends on. Rollback: drop the
  `namespaceSelector` entry for `delegation`. Recorded here because #3431's measurement showed this
  change landed with no threat-model update.
