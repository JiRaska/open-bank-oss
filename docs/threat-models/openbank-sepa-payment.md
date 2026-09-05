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
                                                                              |
                                                                              +--> [document-service] (GET /templates + POST /templates/preview; OIDC CC; ADR-0248 #3; synchronous, customer-triggered only)
```

- **External entities:** payment-initiating channels/operators, downstream clearing & ledger,
  clearing-simulator (scheme network proxy; swap-point for real SCT scheme connector),
  document-service (confirmation-document rendering, non-persisting).
- **Trust boundaries:** caller↔service (mTLS+OIDC+OPA); service↔Postgres/Kafka;
  service↔fraud-service (OIDC client-credentials + mTLS, internal cluster-only, shadow/read-only);
  service↔clearing-simulator (OIDC client-credentials; cluster-internal; pilot flag off by default);
  service↔document-service (OIDC client-credentials; cluster-internal; synchronous, read-only from
  this service's perspective — see §5b).
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
| **I**nfo disclosure | (issue #5679) `GET /api/v1/sepa-payments/approvals` lists every pending four-eyes request with its `makerId` and age | Role-gated `ROLE_OPERATOR`/`ROLE_ADMIN` + `@Authorize(action = "sepaPayment.approval.read")`; the payload carries approval metadata only — the action name, the resource id and who asked — never payment amount, IBAN or other payload content, which stay behind the existing read-role gate (I1 above). Limit clamped to 200 — an unbounded query parameter over a Redis scan is a trivially reachable amplification. Deliberately NOT filtered to exclude the caller's own requests: hiding a maker's request from them would not stop them attempting it (the guard is in `RedisApprovalStore.decide`, server-side) and would only make the queue lie about its own depth |
| **D**oS | Flooding `PATCH /status` to exhaust Redis with pending approvals | Bounded by the same rate-limit/idempotency controls as the gated endpoint itself; each `PendingApproval` is TTL-bounded (86400s) so abandoned records expire |

**DFD update:** adds `Operator (checker) → GET /api/v1/sepa-payments/approvals → Redis (approval:*)`
and `Operator (checker) → PATCH /api/v1/sepa-payments/approvals/{id} → Redis (approval:*)`
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
| **R**epudiation | Denial of having processed a return | Every `/returns` invocation writes a `sepa.payment.returned` non-repudiation record into `sepa_payment_outbox` **in the same transaction as the `RETURNED` transition** (`SepaPaymentService.handlePaymentReturn` -> `SepaPaymentRepository.updateWithEvidence`), carrying the `OrgnlEndToEndId`, the pacs.004 reason code, the correlation id, whether the ledger reversal actually happened, and the **authenticated** actor — `SecurityContext.actorName`/`actorType`, derived server-side in `SepaPaymentResource`, never read from the pacs.004 body. The outbox dispatcher (`openbank.outbox.dispatch-enabled: true`) publishes it to `openbank.sepa.payment.events`, which openbank-audit-service already consumes into the append-only, hash-chained `audit_entries` store. Proved end to end by `SepaPaymentReturnAuditEvidenceIT` — real HTTP + real Postgres, row read back over an independent JDBC connection (issue #6056; before it, this row credited an `AuditService` present in no source file, and the service had no audit publisher of any kind) |
| **I**nfo disclosure | Return reason codes (AC04, AM09, etc.) visible to unauthorised parties | Reason codes and return details accessible to `ROLE_OPERATOR`/`ROLE_ADMIN` only; `ROLE_VIEWER` sees payment status (`RETURNED`) but not raw reason code |
| **D**oS | Replay of the same pacs.004 | `RETURNED` transition is idempotent — a second call with the same `OrgnlEndToEndId` returns 409 (already RETURNED), no double-reversal |
| **E**oP | Reversal credited to wrong account | `transaction-service /reverse` validates that the transaction being reversed is owned by the payment's `debtorAccountId`; cross-account reversals are rejected with 403 |

**DFD update:** adds `clearing-simulator → sepa-payment /returns → transaction-service /reverse` edge.
**Risk class:** integrity (money-path reversal) + availability (idempotency).
**Rollback:** revert the `/returns` commits, or revoke `ROLE_API` from the clearing-simulator
caller. There is **no** feature flag for this endpoint — see the 2026-09-03 change-log entry.

## 5b. Payment confirmation download (ADR-0248 #3) — STRIDE supplement

New outbound trust edge: `GET /api/v1/sepa-payments/{paymentId}/confirmation` (customer-facing
download action) → `sepa-payment-service` → `document-service` (`GET /api/v1/documents/templates`
+ `POST /api/v1/documents/templates/preview`, OIDC client-credentials, cluster-internal). Rendered
**synchronously, only on this explicit customer request** — no pre-generation on
`SepaPaymentStatusChangedEvent`, no new Kafka consumer, nothing persisted a second time anywhere:
the response bytes are read from document-service's non-persisting `preview` endpoint and streamed
straight back. Only meaningful for a `COMPLETED` payment; every other status is rejected with 409
before document-service is ever called.

| STRIDE | Threat | Mitigation |
|---|---|---|
| **S**poofing | A caller other than the payment's own owner downloads its confirmation | Same `@RolesAllowed("ROLE_VIEWER","ROLE_OPERATOR","ROLE_ADMIN","ROLE_PAYMENTS")` + `@Authorize(action="sepaPayment.downloadConfirmation", resource="#paymentId")` as the existing `sepaPayment.read` endpoint; no new role is introduced |
| **T**ampering | The rendered document is altered in flight or a stale template is served | document-service call is OIDC client-credentials + cluster-internal only; `DocumentPreviewAdapter` resolves the template by `code` **and** requires `status == PUBLISHED` — a `DRAFT`/`RETIRED` body is never served |
| **R**epudiation | No record of who downloaded a confirmation | Same request-level audit trail as every other `sepaPayment.*` endpoint (`AuthorizeInterceptor` decision log); this ADR adds no new unaudited path |
| **I**nfo disclosure | Payment PII (IBANs, amount, counterparty name) sent to a third service | document-service already sits inside the same trust boundary as fraud-service/clearing-simulator (mTLS + OIDC CC, cluster-internal); the confirmation is the ONLY new data this service ever sends it, and nothing sent is persisted there — `preview` is non-persisting by construction, so document-service never becomes a second copy of the payment record |
| **D**oS | Flooding the download endpoint to exhaust document-service | No new rate-limit surface beyond the existing per-endpoint controls; a document-service fault or timeout fails the single request (502) and does not retry — no amplification |
| **E**oP | A non-COMPLETED payment's internal state leaks via a confirmation rendered too early | `PaymentConfirmationService` checks `status == COMPLETED` before ever calling `DocumentPreviewPort` — a `RECEIVED`/`VALIDATED`/`PROCESSING`/`REJECTED`/`RETURNED`/`CANCELLED` payment is rejected 409 with document-service never invoked |

**DFD update:** adds `sepa-payment-service → document-service (templates list + preview)` edge,
customer-facing-edge/operator-initiated only — no service-account/M2M caller is granted this action.
**Risk class:** confidentiality (payment data sent to a new outbound peer) + availability (a slow or
unreachable document-service fails only the download, never a payment transition — see §4/§5:
**payment execution, settlement, and status-transition logic are entirely unchanged** by this ADR).
**Rollback:** revert the endpoint + adapter commit; no DB schema change, no flag needed (the route
simply stops existing).

## 6. Change log

- **2026-08-24** — Synthetic-journey taint now propagates over this service's existing internal REST clients through `SyntheticTaintClientFilter` (ADR-0252, #4348). This adds no caller, endpoint, network-policy edge, privilege or payment-control bypass: screening and SCA still run. It preserves the marker before a downstream persistence/event boundary; a fleet gate requires every new client to choose propagation or a reasoned external boundary.

- **2026-08-20** — The `/returns` non-repudiation control now exists (issue #6056). It did not
  before: the R row of §5a credited an `AuditService` that is present in no source file in this
  repository, and `openbank-sepa-payment/src/main` contained no audit publisher of any kind. The
  two things that did happen — an application log line and the payment row's own status transition
  — are both written by the same code path whose behaviour a repudiation dispute puts in question,
  so neither could ever be evidence against it.
  - **What was built**: a `SepaPaymentReturnedEvent` (`sepa.payment.returned`) written into the
    existing transactional outbox in the SAME transaction as the `RETURNED` transition, so the
    record and the act commit together or neither does. It carries `OrgnlEndToEndId`, the pacs.004
    reason code, the correlation id, the measured `reversalPerformed` outcome, and the actor.
  - **Where the evidence lands**: `openbank.sepa.payment.events` -> openbank-audit-service's
    `AuditConsumer` -> the append-only, hash-chained `audit_entries` table. **Deliberately not**
    `com.openbank.libs.audit.AuditEventPublisher`: the only implementation of that interface in
    this repository is `LoggingAuditEventPublisher`, and a log line is not an evidentiary record.
    The field names match what `AuditConsumer` actually reads (`eventType`, `actorId`, `actorType`,
    `correlationId`, `occurredAt`, `sourceService`, `paymentId`), so the row is EVENT-attributed
    rather than landing on its `"unknown"` sentinels — `source_service` is chain-hashed into
    `record_hash`, so attribution cannot be corrected afterwards.
  - **Attribution is server-derived**, from `SecurityContext`, never from the request body. The
    pacs.004 carries no actor field and none is read from it; a record whose actor is supplied by
    the party whose action is in dispute is not a control.
  - **No new trust boundary, no new caller, no API change**: same endpoint, same role gate
    (`ROLE_API`/`ROLE_ADMIN`), same OPA action, same topic, same request and response bodies. The
    only new data on the wire is the evidence event itself, on an existing internal topic.
  - **Risk class**: accountability/evidence (DORA Art. 17 reconstruction). **Rollback**: revert the
    commit — the return path returns to transitioning with no record, i.e. the state this entry
    describes as the defect.

- **2026-08-19** — `ApprovalResource` served only `PATCH /{id}` (decide), so a
  `sepaPayment.transitionStatus` four-eyes decision parked at 202 was discoverable only by
  whoever had been handed its approval id out of band — the ceremony completed only if the two
  operators were already talking, and the 24h Redis TTL then expired the request silently
  otherwise (issue #5679, mirroring sanctions #3472). Added `GET /api/v1/sepa-payments/approvals`
  (§4a new I row); no new trust boundary crossed — same `RedisApprovalStore`, same role gate
  shape as the existing decide endpoint, additive-only OpenAPI change (1.6.0 -> 1.7.0, ADR-0048).
  Verified via `opa eval` that both `sepaPayment.approval.read` (generic `operator-read-any`) and
  the pre-existing `sepaPayment.approval.decide` (`role_action_matrix` + `operator-sepa-payment-write`)
  already resolve `allow=true` for `ROLE_OPERATOR` under this service's live policy bundle — no
  authorization gap, unlike the one #5679's balance-service slice found.
- **2026-08-17** — Recorded here only because #3931's threat-model-diff gate maps the whole
  `openbank-infra/gitops/components/payments/network-policies.yaml` file to every money-path
  service that lives in this directory, not to the specific block that changed. **No trust
  boundary of this service's own changed**: the diff adds a `lending` ingress peer to
  `transaction-service`'s block in that shared file only — this service's own ingress/egress
  rules are byte-identical before and after. See
  `docs/threat-models/openbank-transaction-service.md` §6 for the edge that actually changed.
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

- **2026-08-08** — ADR-0248 #3: payment confirmation download. New endpoint `GET
  /api/v1/sepa-payments/{paymentId}/confirmation`, new outbound trust edge to
  `document-service` (STRIDE supplement §5b). Strictly additive and read-only — no change to
  payment execution, settlement, or status-transition logic; `PaymentConfirmationService` only
  reads the payment's own already-persisted record via the existing `SepaPaymentUseCase.getPayment`
  and rejects (409) anything not `COMPLETED` before document-service is ever called. Rendered
  synchronously on customer request only, via document-service's existing non-persisting `preview`
  endpoint; nothing is cached or persisted a second time anywhere. Risk class = confidentiality
  (new outbound peer receiving payment data) + availability (a document-service fault fails only
  this one download, never a payment transition). Rollback: revert the endpoint + adapter commit.
- **2026-08-05** — Trust-boundary change (#3734): `operator-sepa-payment-write` now excludes `service-account-*` principals, and a new `prohibited` veto closes `sepaPayment.{transitionStatus, handleReturn, approval.decide}` to `service-account-openbank-edge` (the role_action_matrix grants those to ROLE_OPERATOR, which the edge service-account carries; matrix-allows does not consult the ext exclusion). The edge's verified legitimate access — `sepaPayment.{create, read}` via `service-sepa-payment-edge-m2m` (customer transfer initiation after the edge's own ownership guard + SCA gate, ADR-0021) — is preserved; clearing-simulator keeps `handleReturn` via the shared-client identity rule. Ext moved from generator heredoc to standalone `sepa_payment_rest_ext.rego` with an opa test suite.
- **2026-08-03** — Missing required query/header parameter answered 500, not 400 (#3104). A required `@QueryParam`/`@HeaderParam` declared with a non-nullable Kotlin type was fed `null` by JAX-RS when the caller omitted it, and answered **500** rather than 400 (#3104). Kotlin's null-safety is compile-time only, so the declared type only decided where the failure landed: a non-suspend handler threw `Intrinsics.checkNotNullParameter` at the method boundary, and a **suspend** handler got no intrinsic at all, so the null flowed into the body. `Idempotency-Key` on createPayment. As in domestic-payment, the existing `require(idempotencyKey.isNotBlank())` could not run for an ABSENT header — this handler is `suspend`, so `null.isNotBlank()` threw NPE and the replay control answered 500 in exactly the case it existed for. A duplicate submission was never at risk (the store is only consulted with a real key), but the caller was told the server had broken when it had not. Now `require(!idempotencyKey.isNullOrBlank())`. No new caller or boundary. Rollback: revert.
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
  scheme/settlement steps now run inside Temporal activities (each already OIDC-bounded; mTLS is NOT deployed for service-to-service HTTP — the only PeerAuthentication/DestinationRule in the tree is `openbank-infra/k8s/base/istio.yaml`, which no ArgoCD application applies, #1914. Kafka mTLS is real and separate), with the
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
- **2026-09-03** — Doc correction, no behavior change: §5a and the 2026-06-24 entry both named
  `openbank.sepa.returns.enabled` as the rollback control for the pacs.004 return path, §5a adding
  that it is "off by default" and that "flag OFF = 404 on `/returns`". **No such property exists.**
  It occurs nowhere in the repository except this document
  (`git grep -l -F openbank.sepa.returns.enabled` returns only this file), and the endpoint reads
  no config at all: `SepaPaymentResource.handlePaymentReturn` is gated by
  `@RolesAllowed("ROLE_API", "ROLE_ADMIN")` plus `@Authorize(action = "sepaPayment.handleReturn")`
  and nothing else. The two real flags in this service are
  `openbank.sepa.scheme-submission.enabled` and `openbank.sepa.worker.enabled`; neither disables
  `/returns`.

  This one is not a renamed control, so unlike a wrong class name it changes what an operator can
  do: the documented rollback was **not executable**, and the stated default was backwards — the
  path has been on since it shipped, not off. What is actually available is a revert, or revoking
  `ROLE_API` from the clearing-simulator's client, which is coarser (that role admits other
  callers) and is why it is worth recording rather than silently swapping in. Adding a real flag is
  a code change and is deliberately not made here. Everything else the 2026-06-24 entry and §5a say
  about the trust boundary, the STRIDE rows and the idempotency posture is unaffected.

- **2026-06-24** — ADR-0111 R-transaction return path (pacs.004). New inbound trust boundary:
  `clearing-simulator → sepa-payment /returns → transaction-service /reverse`. STRIDE supplement
  added in §5a above. **Risk class = integrity + availability**. Rollback = revert, or revoke `ROLE_API` from the
  clearing-simulator caller (no feature flag exists — see the 2026-09-03 entry).
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

- **2026-08-06** — **Error-envelope disclosure: `ApiError.timestamp` now carries a real
  clock reading.** `#3874` — the shared `ApiError` envelope (openbank-libs-domain) defaulted
  `timestamp` to `Instant.EPOCH` and no call site passed it, so every error this service served
  carried `1970-01-01T00:00:00Z`. The field is now a required constructor argument, stamped
  `Instant.now()` at construction in this service's mappers. **Risk class = information
  disclosure**, and it is a deliberate, bounded increase: error responses now reveal the server's
  wall-clock time to any caller who can provoke an error, including an unauthenticated one on
  endpoints that answer 401/403 through this envelope. Assessed as acceptable — the value is
  second-resolution UTC already implied by the HTTP `Date` header on the same response, so it
  discloses nothing a caller could not already read, and it is what makes the envelope's own
  instruction ("contact support with traceId=…") actionable by letting support bind a trace to a
  moment. No new field, no new endpoint, no authorization or ingress change; the response SHAPE is
  unchanged (`string`/`date-time`), so no API-contract bump under ADR-0048. Not a timing oracle:
  the stamp is taken when the error object is built, not measured against request start, so it
  does not expose per-request processing duration. Rollback: revert; the field is
  serialisation-only and nothing persists it.

- **2026-09-03** — **New outbound trust edge: `account-service` party lookup.** Added
  `AccountServiceClient.getById` (`GET /api/v1/accounts/{accountId}`) and wired `AmlCaseAdapter` to
  call it with an OIDC client-credentials token, so an AML case opened from a SEPA payment carries
  the debtor's *party* id rather than an account id — the SEPA rail's port of the domestic rail's
  #3274 fix (#8505). Risk class = **information disclosure** (account→party linkage now crosses a
  service boundary) and **availability** (a second synchronous dependency on the AML-case path).
  Mitigations already in the code: the call is bearer-authenticated per request; every failure path
  is caught and returns `null`, so a lookup outage degrades the case record rather than blocking the
  payment; and a 404 is deliberately not logged as a warning, so a missing account is not treated as
  an error. Correction to an earlier draft of this entry: the fallback is **not silent** — the
  adapter logs `aml.case.party_unresolved` with the payment and account ids before opening the case
  with the account id in `party_id`, so rows carrying the old shape are identifiable rather than
  indistinguishable. Residual: `null` still does not distinguish "no such account" from "lookup
  failed" at the *data* level, and the case row itself carries no marker of which branch produced
  it. Rollback: revert; the adapter's previous behaviour was to store the account id in `partyId`.
