<!--
SPDX-License-Identifier: Apache-2.0
Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
-->
# Threat model — sepa-instant-service (SCT Inst)

- **Date:** 2026-06-17
- **Status:** Lightweight STRIDE/DFD (ADR-0030 D2). **Money-path** bounded context.
- **Service ADR:** see `docs/adr/`; platform controls per ADR-0029/0030/0034.

## 1. Scope & purpose

SEPA Instant Credit Transfer (SCT Inst): initiate, query, query-by-debtor, recall. Payments are
**near-irrevocable and settle in seconds** — the window to catch fraud is minimal, raising stakes
above batch SEPA.

## 2. Data flow (DFD)

```
[Channels/Operators] --> (REST /api/v1/sepa-instant) --> [sepa-instant-service] --> [(Postgres: sct_inst payments)]
                                                                |
                                                                +--> [(sct_inst_outbox)] --> [Kafka events] --> clearing/scheme
                                                                |
                                                                +--> [fraud-service] (shadow, OIDC CC / mTLS, fail-open)
   recall <-- (POST /{paymentId}/recall)
```

- **External entities:** initiating channels/operators, SCT Inst scheme/clearing.
- **Trust boundaries:** caller↔service (mTLS+OIDC+OPA); service↔Postgres/Kafka; scheme edge;
  service↔fraud-service (OIDC client-credentials + mTLS, internal cluster-only, shadow/read-only).
- **Assets:** instant payment instructions, recall requests.

## 3. Authn/Authz

- Initiation/recall must be role-gated (payments) + OPA enforce; SCA for customer-initiated.

## 4. STRIDE

| Threat | Vector | Mitigation |
|---|---|---|
| **S**poofing | Forged instant initiation | OIDC + role; mTLS |
| **T**ampering | Amount/beneficiary change before send | Server-validated, immutable once accepted; audit |
| **R**epudiation | Deny initiating an instant payment | AuditEvent + SCA evidence + correlation id |
| **I**nfo disclosure | Debtor payment history (`/debtor/{id}`) leak | AuthZ scoping to owner/role |
| **I**nfo disclosure | Domain metrics leak PII / enable per-payment inference via high-cardinality labels | `DomainMetrics` low-cardinality contract (ADR-0077 / ADR-0079): the outbox-backlog gauge `openbank.outbox.backlog` is tagged **only** by `service="sepa-instant"` — never a payment id, end-to-end id, debtor/creditor IBAN, amount, or any PII. The gauge value is a read-only `COUNT(*)` of PENDING+FAILED outbox rows, refreshed off a scheduled tick (not on the Prometheus scrape thread); `/q/metrics` is cluster-internal |
| **D**oS | Flood to exhaust instant-rail capacity | Rate limit; idempotency |
| **E**oP | Unauthorized recall to claw back funds | Recall gated by distinct authority; audit; reason required |

## 4a. Four-eyes approval (ADR-0155) — STRIDE supplement

`POST /{paymentId}/recall` (`sctInstPayment.recall`) is a money-path action that OPA
(`rest.rego`) can flag `four_eyes_required` — recalling a SETTLED SCT Inst payment claws back
funds that have already left the debtor's account on a near-real-time rail, so a single actor
being able to trigger it unilaterally is the highest-stakes gap this service has. New endpoint
`PATCH /api/v1/sepa-instant/approvals/{id}` lets a DIFFERENT operator decide the resulting
`PendingApproval`; the maker retries `POST /{paymentId}/recall` with an `X-Approval-Id` header.
**`authz.four-eyes.enforce` stays `false` in this PR** — the `ApprovalStore`/endpoint are wired
(mirroring the sepa-payment pilot), but blocking is a deliberate follow-up flip, not bundled
here (see ADR-0155, issue #413).

| STRIDE | Threat | Mitigation |
|---|---|---|
| **S**poofing | A caller other than an operator decides an approval | `@RolesAllowed("ROLE_OPERATOR","ROLE_ADMIN","ROLE_PAYMENTS")` + OPA `@Authorize(action="sctInstPayment.approval.decide")` on the decide endpoint |
| **E**oP | The maker approves their own recall request (self-approval defeats maker-checker on an already-settled, near-irrevocable payment) | `ApprovalStore.decide` throws `SelfApprovalNotAllowedException` (mapped to 403) when `decidedBy == makerId` — enforced in the domain port itself, not just the REST layer, and `makerId`/`decidedBy` both resolve via the same `.principal.name` extraction (interceptor vs. `SecurityIdentity`) so the comparison can't silently mismatch for the same real person |
| **T**ampering | A stale, mismatched, or already-consumed `X-Approval-Id` is replayed to unlock a different recall | `AuthorizeInterceptor` requires the approval's `action` + `resourceId` + `makerId` to match the CURRENT request exactly, `status == APPROVED`, and marks it `EXECUTED` (one-time use) on success; any mismatch re-issues a fresh pending approval instead of proceeding |
| **R**epudiation | No record of who approved a recall clawing back settled funds | `PendingApproval.decidedBy` + `decidedAt` recorded in the approval record itself (Redis, TTL-bounded — see ADR-0155 Negative consequences: not yet a permanent audit trail) |
| **I**nfo disclosure | Approval id enumeration reveals payment/action metadata to an unauthorized caller | `find`/`decide` require the caller to already hold a valid, role-gated session; the id itself is a random UUID (`RedisApprovalStore`, not sequential) |
| **D**oS | Flooding `POST /{paymentId}/recall` to exhaust Redis with pending approvals | Bounded by the same rate-limit/idempotency controls as the gated endpoint itself; each `PendingApproval` is TTL-bounded (86400s) so abandoned records expire |

**DFD update:** adds `Operator (checker) → PATCH /api/v1/sepa-instant/approvals/{id} → Redis
(approval:*)` alongside the existing `POST /{paymentId}/recall` edge; the maker's retry reuses
the existing DFD edge.
**Risk class:** integrity (segregation of duties on a fund-clawback action) + confidentiality
(approval record scope).
**Rollback:** `authz.four-eyes.enforce=false` (default) — the endpoint and store exist but do
not change any existing request's outcome until explicitly flipped.

## 5. Residual risks / assumptions

- **Irrevocability** ⇒ pre-send fraud checks + SCA are the key controls; post-hoc recall is best-effort.
- Idempotency-key mandatory (instant retries must not double-send).
- **Four-eyes `PendingApproval` records are TTL-bounded (Redis), not a permanent audit
  trail** (ADR-0155) — a durable-audit requirement for "who approved what, forever" would
  need an additional store; not implemented in this PR.

## 6. Change log

- **2026-08-09** — Fraud shadow scoring's fallback is now observable (#4221). **No new trust
  boundary and no new caller**: the outbound edge to fraud-service (OIDC client-credentials + mTLS,
  cluster-internal, shadow) is the same edge, and the verdict is still *observed, never enforced* —
  the caller logs a non-ALLOW and proceeds identically either way. What changed is that a failure of
  that edge is no longer indistinguishable from a clean payment.
  - **The property at stake is detectability, not integrity.** `catch (Exception)` returned a
    synthetic ALLOW down the same silent branch a real ALLOW takes, so fraud scoring being wholly
    down and every payment being clean produced identical observable behaviour. A control nobody
    can see fail is a control nobody knows they have lost.
  - **Mitigation**: the synthetic answer is flagged on the outcome (`FraudScoreOutcome.synthetic`),
    counted, and exported as the `openbank_fraud_scoring_degraded` gauge, where **`-1` means never
    attempted** — deliberately distinct from a healthy `0`, because a counter that has never been
    incremented is not created at all and an alert on it matches nothing, forever.
  - **`Throwable`, not `Exception`**, and this is a real change in fault containment: a fault
    crossing into a rest-client or fault-tolerance interceptor can surface as an `Error`, which the
    previous `catch (Exception)` did not hold. An `Error` escaping here would propagate out of a
    path whose entire contract is that it cannot affect the payment. Verified against `origin/main`:
    a `NoClassDefFoundError` escapes the old catch and the containment test fails.
    `CancellationException` is rethrown — cancelling the caller's coroutine is not a fraud-service
    outage and must not be reported as one.
  - **Fail-open is retained deliberately.** Failing closed would stop payments on a money-path rail
    to protect a value nothing reads. Real enforcement is tracked separately (#4403); until then
    this service must not pretend to have a fraud control it does not have.
  - **Rollback**: revert the commit; the previous behaviour was a silent synthetic ALLOW.

- **2026-05-30** — Added `sct_inst_outbox_seq` (Hibernate fix). Additive DDL only — no new flow/
  surface/boundary. Risk class = **availability**, mitigated by `HibernateSequenceGuardTest`.
  Rollback: `DROP SEQUENCE`.
- **2026-06-11** — Added outbox-backlog gauge (`openbank.outbox.backlog`, tagged `service="sepa-instant"`)
  + `countProcessable()` on the outbox port (ADR-0077 / ADR-0079). Touches the **I — information
  disclosure** row: a new domain metric. **No new data flow, endpoint, or trust boundary** — it is a
  read-only `COUNT(*)` of PENDING+FAILED `sct_inst_outbox` rows, refreshed by a scheduled in-process
  tick (not on the scrape thread), exposed on the cluster-internal `/q/metrics`. The gauge carries no
  payment id, IBAN, amount, or PII (low-cardinality contract). **Risk class = confidentiality / metric
  cardinality** (bounded to a single per-service series). Mitigated by `SctInstOutboxBacklogGaugeTest`
  (supplier tracks the refreshed cache). No DB change; rollback = revert the commit.
- **2026-06-17** — ADR-0084 fraud shadow scoring (observe-only). New outbound trust boundary:
  `sepa-instant → fraud-service (POST /api/v1/fraud/score, OIDC client-credentials)`.
  **Shadow = fail-open and never-enforce**: `SctInstPaymentService.scoreFraudShadow()` wraps the call
  in `.onFailure().recoverWithUni {}` — any fault (timeout, circuit-open, 5xx) is swallowed; the
  payment outcome is unchanged. `FraudScoringAdapter` applies `@CircuitBreaker` (threshold 0.3,
  30% failure ratio) + `@Timeout(3 s)`. No retry (avoid double-scoring on near-real-time rail).
  **Risk class = availability** (fault in fraud-service cannot block a payment) and **confidentiality**
  (payment amount, debtor/creditor IBAN, currency sent to fraud-service; mitigated by mTLS +
  OIDC client-credentials for service-to-service authn; fraud-service is internal, cluster-only).
  **DFD update**: add `sepa-instant → fraud-service` edge with `OIDC client-credentials / mTLS`
  trust-boundary label. No DB schema change; rollback = revert adapter + port commits.
- **2026-07-05** — ADR-0122 Phase 2: `build.gradle.kts` now declares `openbank-libs-domain` +
  `openbank-libs-runtime` directly instead of the umbrella `openbank-libs` (which already re-exported
  both via `api()`). Pure Gradle dependency-graph change — no source import changed, no new transitive
  dependency introduced, no behavior change. Attack surface, trust boundaries, and STRIDE rows above are
  unaffected. No DB change; rollback = revert the commit.
- **2026-07-08** — ADR-0155 four-eyes enforcement, rolled out from the sepa-payment pilot
  (issue #413). New endpoint `PATCH /api/v1/sepa-instant/approvals/{id}` + `ApprovalConfig`
  (Redis-backed `ApprovalStore`) + `AuthorizeInterceptor` four-eyes gate (openbank-libs-runtime,
  shared, opt-in) on `sctInstPayment.recall`. New STRIDE supplement §4a. `authz.four-eyes.enforce`
  defaults `false` — no behavior change to any existing request in this PR; flipping it is a
  tracked follow-up. No DB schema change (Redis, TTL-bounded); rollback = revert the commit (or
  leave `authz.four-eyes.enforce=false`, its default).
