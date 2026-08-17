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
                                                                +--> [Kafka events] (direct emit via KafkaSctInstEventPublisher) --> clearing/scheme
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
  **Superseded — see the 2026-08-17 entry below**: this gauge, `countProcessable()`, and the outbox
  pipeline it measured were removed as dead code (PR #1364); the corresponding STRIDE row above no
  longer applies and has been removed from §4.
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
- **2026-08-17** — Doc correction (issue #5127), no behavior change. PR #1364 (2026-07-17) had
  already removed the dead transactional-outbox pipeline —
  `SctInstOutboxPort`/`SctInstOutboxDispatcher`/`KafkaSctInstOutboxEventPublisher`/the
  outbox-backlog gauge — after confirming nothing ever wrote to it: `KafkaSctInstEventPublisher`
  (a direct, synchronous emitter) was always the pipeline actually in use (issue #1034). This
  entry corrects §2's DFD (the `sct_inst_outbox` node is replaced with the direct
  `KafkaSctInstEventPublisher` edge that was always the real path) and removes the now-void
  metric-cardinality row from §4 STRIDE, and lands alongside a Flyway migration
  (`V4__drop_sct_inst_outbox.sql`) dropping the vestigial `sct_inst_outbox` table and
  `sct_inst_outbox_seq` sequence that PR #1364 left behind (0 rows, unused since #1034).
  **Risk class = none** — this is a documentation and dead-schema cleanup only; the live pipeline
  (direct Kafka emitter) and every trust boundary above are unchanged. Rollback: revert this doc
  commit; the migration's own rollback is stated in `V4__drop_sct_inst_outbox.sql`.
