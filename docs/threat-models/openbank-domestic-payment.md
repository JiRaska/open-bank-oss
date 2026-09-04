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
[Kafka compacted delegated reservation state] --> [durable binding projection] --+
                                                                                  |
                                                                                  +--> [fraud-service] (shadow, OIDC CC / mTLS, fail-open)
                                                                                  |
                                                                                  +--> [clearing-simulator] (pacs.008 out / pacs.002 in; OIDC CC; ADR-0104 D4; flag-gated)
                                                                                  |
                                                                                  +--> [document-service] (GET .../templates, POST .../templates/preview; OIDC CC; ADR-0248 #3; synchronous, on customer request only)
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
| **T**ampering / **I**nfo disclosure | Reuse another caller's `Idempotency-Key`, or change amount/beneficiary while receiving the first caller's cached 201 body | Redis is no longer an authority for create-payment responses. A canonical SHA-256 over the complete normalized command, issuer-qualified actor scope, synthetic classification and delegation/reservation binding is stored on `domestic_payments` in the same transaction as the payment and outbox. Exact digest replay returns the durable row; any mismatch and every legacy NULL fingerprint fail closed with 409 `IDEMPOTENCY_KEY_REUSED`. The DB unique key arbitrates concurrent creators; the losing transaction (including its outbox) rolls back before its winner is fingerprint-verified. Cutover is blue/green with creates quiesced and drained for the 15-second request timeout, never a mixed-writer rolling interval. |
| **T**ampering / **E**oP | Forged, replayed or reordered delegated reservation snapshot authorizes a payment | The default-OFF Kafka consumer accepts the exact version-1 envelope and complete immutable tuple only. A domain-separated SHA-256 binds the raw Idempotency-Key without retaining it. PostgreSQL stores the monotonic reservation revision; same/stale revisions are idempotent, contradictory tuples fail to DLQ, and cross-pod timestamps never override the version. Source timestamps are truncated to PostgreSQL's microsecond precision before both persistence and comparison, so a nanosecond-bearing first delivery/redelivery cannot become a false contradiction. The create transaction locks PENDING and revalidates grantee, grantor/debit owner, account, amount, currency, operation, delegation and key hash before atomically inserting payment+created outbox and moving to BOUND. |
| **T**ampering / **E**oP | A delegated request names the reserved account id but supplies another debtor account number/bank code, or the account has moved to another owner | Before any payment/outbox/workflow side effect, the default-OFF use-case rebuilds the canonical Czech IBAN from the normalized debtor coordinates, requires account-service to resolve it to the exact reserved resource id, and independently requires that id's owner to equal the reservation grantor. A different resolved id/owner is a permanent tuple mismatch; a null result (404 and outage are intentionally indistinguishable in the existing narrow port) is retryable `AccountAuthorityUnavailable`, never authorization. The repository still locks and revalidates the tuple in the create transaction. |
| **T**ampering / **R**epudiation | Create races an absence finalizer, or a future code path inserts a standalone payment against PENDING and later releases its reservation | Pessimistic row lock plus deferred two-way FKs serialize the race. The inverse link is composite `(reservation_id,payment_id)`, backed by a non-partial unique target, so PENDING+NULL cannot authorize any delegated payment and only the exact BOUND pair can commit; owner rows pass via `MATCH SIMPLE` NULL reservation. Exactly BOUND+payment+created outbox or permanent FINALIZED_ABSENT+absence outbox can commit; failures roll back the entire transaction. Tombstones are retained and delayed RESERVED cannot reopen them. No HTTP callback and no blind delegation TTL release exists. |
| **I**nfo disclosure | Indefinitely compacted state retains caller-controlled Idempotency-Key text | Only lowercase domain-separated SHA-256 is published and persisted (`openbank.delegation.spend-reservation.idempotency-key.v1`, NUL delimiter, raw UTF-8 key). The callback also carries only this digest. |
| **R**epudiation | Customer/operator denies initiating | AuditEvent + SCA evidence + correlation id |
| **I**nfo disclosure | Payment history harvesting | AuthZ scoping; `ROLE_VIEWER` read-only, owner-scoped |
| **I**nfo disclosure | Domain metrics leak PII / enable per-payment inference via high-cardinality labels | `DomainMetrics` low-cardinality contract (ADR-0077 / ADR-0079): the `openbank.outbox.backlog` gauge is tagged **only** by `service="domestic"` — never a payment id, IBAN, amount, debtor/creditor identity, or any PII. The value is a read-only `COUNT` of PENDING+FAILED outbox rows refreshed off the scrape thread (a cached `AtomicLong` ticked by a scheduled `suspend` query), so a Prometheus scrape touches neither the DB nor payment data. `/q/metrics` is cluster-internal |
| **D**oS | Initiation flooding | Rate limit; idempotency |
| **E**oP | Viewer initiates payment | Distinct `ROLE_PAYMENTS`; deny-by-default |

## 4a. Four-eyes approval (ADR-0155) — STRIDE supplement

`PATCH /status` (`domestic-payment.transitionStatus`) is a money-path action OPA (`rest.rego`)
flags `four_eyes_required`. New endpoint `PATCH /api/v1/domestic-payments/approvals/{id}` lets a
DIFFERENT operator decide the resulting `PendingApproval`; the maker retries `PATCH /status` with
an `X-Approval-Id` header. **`authz.four-eyes.enforce` stays `false` in this PR** — the
`ApprovalStore`/endpoint are wired and tested, but blocking is a deliberate follow-up flip,
not bundled here (see ADR-0155).

| STRIDE | Threat | Mitigation |
|---|---|---|
| **S**poofing | A caller other than an operator decides an approval | `@RolesAllowed("ROLE_OPERATOR","ROLE_ADMIN","ROLE_PAYMENTS")` + OPA `@Authorize(action="domestic-payment.approval.decide")` on the decide endpoint |
| **E**oP | The maker approves their own request (self-approval defeats maker-checker) | `ApprovalStore.decide` throws `SelfApprovalNotAllowedException` (mapped to 403) when `decidedBy == makerId` — enforced in the domain port itself, not just the REST layer, and `makerId`/`decidedBy` both resolve via the same `.principal.name` extraction (interceptor vs. `SecurityIdentity`) so the comparison can't silently mismatch for the same real person |
| **T**ampering | A stale, mismatched, or already-consumed `X-Approval-Id` is replayed to unlock a different request | `AuthorizeInterceptor` requires the approval's `action` + `resourceId` + `makerId` to match the CURRENT request exactly, `status == APPROVED`, and marks it `EXECUTED` (one-time use) on success; any mismatch re-issues a fresh pending approval instead of proceeding |
| **R**epudiation | No record of who approved a gated transition | `PendingApproval.decidedBy` + `decidedAt` recorded in the approval record itself (Redis, TTL-bounded — see ADR-0155 Negative consequences: not yet a permanent audit trail) |
| **I**nfo disclosure | Approval id enumeration reveals payment/action metadata to an unauthorized caller | `find`/`decide` require the caller to already hold a valid, role-gated session; the id itself is a random UUID (`RedisApprovalStore`, not sequential) |
| **I**nfo disclosure | (issue #5679) `GET /api/v1/domestic-payments/approvals` lists every pending four-eyes request with its `makerId` and age | Role-gated `ROLE_OPERATOR`/`ROLE_ADMIN`/`ROLE_PAYMENTS` + `@Authorize(action = "domestic-payment.approval.read")`; the payload carries approval metadata only — the action name, the resource id and who asked — never payment/account details, which stay behind the existing read-role gate (I1 above). Limit clamped to 200 — an unbounded query parameter over a Redis scan is a trivially reachable amplification. Deliberately NOT filtered to exclude the caller's own requests: hiding a maker's request from them would not stop them attempting it (the guard is in `RedisApprovalStore.decide`, server-side) and would only make the queue lie about its own depth |
| **D**oS | Flooding `PATCH /status` to exhaust Redis with pending approvals | Bounded by the same rate-limit/idempotency controls as the gated endpoint itself; each `PendingApproval` is TTL-bounded (86400s) so abandoned records expire |

**DFD update:** adds `Operator (checker) → GET /api/v1/domestic-payments/approvals → Redis
(approval:*)` and `Operator (checker) → PATCH /api/v1/domestic-payments/approvals/{id} → Redis
(approval:*)` alongside the existing `PATCH /status` edge; the maker's retry reuses the existing
DFD edge.
**Risk class:** integrity (segregation of duties) + confidentiality (approval record scope).
**Rollback:** `authz.four-eyes.enforce=false` (default) — the endpoint and store exist but do
not change any existing request's outcome until explicitly flipped.

## 5. Residual risks / assumptions

- **Idempotency-key required** — only an exact, actor-bound retry may replay the durable payment;
  changed or unverifiable legacy requests are rejected with 409.
- **Global-key collision remains fail-closed:** V1's existing `UNIQUE(idempotency_key)` is intentionally
  retained for mixed-version safety. A key already used in another actor scope can therefore deny
  that same key to a victim (409), but can never replay or disclose the first actor's response. Do
  not weaken the constraint while an old writer can still omit the fingerprint. Follow-up must be
  an expand/contract rollout: first deploy every writer with a separately persisted one-way actor-
  scope binding and prove no legacy writers remain; then add/validate the scoped uniqueness rule;
  only in a later migration may the global unique constraint be removed. Until then clients should
  generate high-entropy keys and treat a cross-scope collision as terminal, not retry it unchanged.
- **Delegated-spend rollout is intentionally inert:** the consumer and finalizer are separate,
  default-OFF switches; GitOps provisions ACL/DLQ only, while the group/offset activation ConfigMap
  is deliberately absent until a compatible image is pinned. Enable projection/backfill first,
  then the separately approved workload-only
  create endpoint, and finalization last. Until the consumer is caught up, create returns the future
  425 semantic and the caller must retry; a permanent absence returns the future 410 semantic.
- **Account authority proof is an activation gate:** do not expose or enable the workload endpoint
  unless both canonical-IBAN-to-account-id and account-id-to-owner checks are green. A lookup outage
  must stay retryable and must produce no payment, outbox event or workflow start; it must never be
  downgraded to a tuple mismatch or treated as proof of ownership.
- **Finalizer availability trade-off:** a disabled/stalled finalizer retains PENDING rows and reserved
  headroom rather than releasing funds without proof. This fail-closed strand is observable/retryable
  and is safer than a time-only release; the callback is a transactional domestic outbox event.
- SCA (sca-service) must gate customer-initiated transfers.
- **Four-eyes `PendingApproval` records are TTL-bounded (Redis), not a permanent audit
  trail** (ADR-0155) — a durable-audit requirement for "who approved what, forever" would
  need an additional store; not implemented in this PR.

## 6. Change log

- **2026-09-01** — Added an expand-first, default-OFF delegated-spend binding state machine; no REST
  endpoint or new caller is exposed in this phase. V13 creates the durable PENDING/BOUND/
  FINALIZED_ABSENT projection with full immutable party/resource/money tuple, domain-separated
  Idempotency-Key hash, partial indexes, `NOT VALID` checks and deferred two-way payment FKs. The
  inverse FK uses the exact `(reservation_id,payment_id)` pair rather than reservation alone, so a
  standalone payment can never commit against PENDING; V14
  validates separately. A default-OFF Kafka consumer applies compacted version-1 snapshots by
  monotonic reservationVersion, tolerates clock skew and canonicalizes older nanosecond timestamps
  to the database's microsecond precision before persistence/comparison. A separate default-OFF suspend scheduler
  atomically finalizes old PENDING rows and writes `DELEGATED_SPEND_FINALIZED_ABSENT`; it does not
  call delegation over HTTP and never releases by TTL alone. The workload-only application port
  accepts only a reservation selector, derives grantee identity/delegation/debit owner/account id
  from the local projection, proves the normalized debtor coordinates resolve to that exact account
  and that its current owner is the grantor, then locks/revalidates before
  payment+created-outbox+BOUND commit. An unprovable lookup is retryable and has no write/workflow
  side effects. GitOps adds
  exact future consumer-group ACL and DLQ but intentionally omits the group/offset ConfigMap and any
  activation wiring until a compatible image is pinned. V10's nullable reservation uniqueness is
  the one deliberate table scan (PostgreSQL cannot attach a UNIQUE constraint `NOT VALID`); table
  sizing must be checked before rollout, and its named constraint is the rollback boundary.
  **Risk class:** money-path integrity, confidentiality and availability.
  **Rollback:** disable finalizer, workload seam and consumer in that order; retain/export BOUND and
  FINALIZED_ABSENT evidence, verify no PENDING rows, then drop inverse FK before the binding table.

- **2026-09-01** — Hardened create-payment idempotency from a raw Redis key lookup to a durable,
  actor-bound request fingerprint. Previously, the REST resource queried Redis by the caller-
  supplied key before it parsed or compared the payment command; a reused key could therefore
  return another request's cached response body, while the DB fallback compared only the emerging
  delegation/reservation pair. New payments persist a length-framed canonical SHA-256 of all
  normalized command fields, the authenticated issuer/subject scope, synthetic taint and
  delegation/reservation ids atomically with the payment and outbox. Postgres remains the sole
  authority across replicas; the unique-key race path returns the committed winner only after the
  loser's transaction is rolled back, then verifies its fingerprint. A changed request or legacy
  row with no provable fingerprint returns RFC 9457 media type `application/problem+json`, status
  409 and code `IDEMPOTENCY_KEY_REUSED`; exact replay returns the existing payment and
  `X-Idempotency-Replayed: true`. V11 is an additive nullable metadata-only column plus a
  `NOT VALID`/`VALIDATE` format check; NULL is retained intentionally so legacy replay fails closed
  rather than being falsely backfilled. No endpoint, caller, role or network edge was added.
  V1's global unique key remains deliberately intact through the cutover: this preserves
  fail-closed collision handling at the cost of a bounded cross-scope key-collision DoS; it never
  permits cross-scope replay because the fingerprint comparison happens before any row is returned.
  The former Redis replay contract was 24 hours (`86400` seconds). A pre-cutover NULL-fingerprint
  row inside that window now returns 409 even for a legitimate retry: this is an explicit residual
  availability break chosen over returning another request's response. The caller must not switch
  keys because the first attempt may have committed; status lookup/operator reconciliation decides
  the outcome. There is no flag that re-enables unprovable replay.
  **Risk class:** integrity, confidentiality and bounded cutover availability. **Rollback:** do not
  make Redis or a NULL fingerprint authoritative again. Quiesce creates, keep the additive column
  and constraint, and reconcile every ambiguous request before any image reversal/resubmission;
  reverting to the previous Redis-authoritative behavior requires a separate security decision.

- **2026-08-26** — The existing authenticated initiation edge now carries a bounded synthetic
  classification into the existing `domestic_payment_outbox` event boundary. The resource copies
  it only from the `SyntheticTaintRequestFilter` request property after that filter has accepted a
  configured trusted principal; it does **not** accept a request header, JWT claim, MDC value, or
  an unconfigured caller as synthetic. The property is persisted on the payment-created outbox
  message so an asynchronous consumer can retain the classification instead of treating a canary
  payment as real. This adds no caller, role, OPA grant, endpoint, payment-control bypass, or new
  network edge: authentication, authorisation, SCA, limits, sanctions and fraud remain on the
  normal initiation path. **STRIDE-S/T:** an untrusted caller attempting to label a real payment as
  synthetic is mitigated by the fail-closed filter decision and by copying only that server-side
  property. **Residual risk:** no trusted principal is configured and no downstream regulatory
  exclusion is claimed here; activating either remains a separately reviewed production change.
  Rollback: revert this propagation, which restores the previous default of `synthetic=false`.

- **2026-08-24** — Synthetic-journey taint now propagates over this service's existing internal REST clients through `SyntheticTaintClientFilter` (ADR-0252, #4348). This adds no caller, endpoint, network-policy edge, privilege or payment-control bypass: sanctions, fraud, limits and SCA continue to run. It prevents a canary payment becoming indistinguishable before a downstream persistence/event boundary; a fleet gate requires every new client to choose propagation or a reasoned external boundary.

- **2026-08-19** — `ApprovalResource` served only `PATCH /{id}` (decide), so a
  `domestic-payment.transitionStatus` four-eyes decision parked at 202 was discoverable only by
  whoever had been handed its approval id out of band — the ceremony completed only if the two
  operators were already talking, and the 24h Redis TTL then expired the request silently
  otherwise (issue #5679, mirroring sanctions #3472 and ledger). Added
  `GET /api/v1/domestic-payments/approvals` (§4a new I row); no new trust boundary crossed — same
  `RedisApprovalStore`, same role gate shape as the existing decide endpoint, additive-only
  OpenAPI change (1.4.0 -> 1.5.0, ADR-0048). Checked the existing decide endpoint's own authz
  posture while here (verify-by-effect, not by appearance): `opa eval` against the real
  `domestic_payment_rest_ext.rego` bundle confirms `domestic-payment.approval.decide` already
  resolves `allow=true` for `ROLE_OPERATOR` via `operator-domestic-payment-write` (a
  `startswith(input.action, "domestic-payment.")` prefix rule that covers the whole namespace) —
  unlike balance-service, which had no such prefix rule and found its `approval.decide` silently
  ungranted. No authz-matrix gap here.
- **2026-08-17** — Recorded here only because #3931's threat-model-diff gate maps the whole
  `openbank-infra/gitops/components/payments/network-policies.yaml` file to every money-path
  service that lives in this directory, not to the specific block that changed. **No trust
  boundary of this service's own changed**: the diff adds a `lending` ingress peer to
  `transaction-service`'s block in that shared file only — this service's own ingress/egress
  rules are byte-identical before and after. See
  `docs/threat-models/openbank-transaction-service.md` §6 for the edge that actually changed.
- **2026-08-09** — Settlement outage no longer completes the workflow on a non-terminal state
  (#4182). No new trust boundary and no new caller: the outbound edge to transaction-service is
  unchanged, and what changes is what this service does when that edge fails. Previously
  `settlePayment` caught `SettlementUnavailableException` — and, more broadly, every `Exception` —
  and *returned* `SENT_TO_CLEARING`. Temporal drives activity retries off activity failure, so an
  activity that returns is a success: the configured retry policy was structurally unreachable on
  precisely the fault it exists for, and `DomesticPaymentWorkflowImpl.process` completed on a
  business state that is not terminal.
  - **Availability / non-repudiation (STRIDE-D, -R)** is the property at stake. The money left the
    payer's instruction path and was never booked, while the workflow, the API response and every
    tier-1 rule reported success — the only artefact anywhere was one WARN line, and a row that
    stops changing raises nothing. The blanket `catch (Exception)` additionally made a settlement
    *bug* indistinguishable from a planned degradation.
  - **Mitigation**: the activity now fails. `SettlementUnavailableException` is logged at ERROR and
    rethrown; the blanket catch is gone, so any other fault propagates with its own type. The
    workflow therefore retries under its existing policy and, if the fault persists, ends as
    **failed** — a state Temporal surfaces and an operator can re-drive — rather than completed.
  - **Why retrying is safe rather than a duplicate-payment risk**, established from the code and
    not assumed: `SettlementAdapter` sends `idempotencyKey = "domestic-settlement-<paymentId>"`,
    payment-scoped and stable across attempts; transaction-service deduplicates on it by
    early-returning the existing transaction (`TransactionService.initiateTransaction`), which it
    answers as **201 with that transaction** — not 409, and the adapter maps that arm to
    `settled = true` too; and the `SENT_TO_CLEARING` guard makes the activity re-entrant once the
    status write lands. The `HTTP_CONFLICT` branch in `SettlementAdapter` is unreachable today and
    kept only as defence if that service ever starts answering 409.

    One caveat that follows from the early return and is NOT covered: it returns the existing row
    whatever its status, so a first attempt that committed the transaction but died before posting
    hands a retry a `PENDING` transaction as 201, which the adapter reads as `settled = true`. The
    fix belongs in transaction-service; recorded here so the safety argument is not read as wider
    than it is. This is the opposite of the #4218 dispatch
    edge, where no downstream deduplication exists and holding is therefore the correct trade.
  - **Residual risk**: the retry window is unchanged (3 attempts, 10 min schedule-to-close), so a
    multi-hour outage still ends in a failed workflow with the payment in `SENT_TO_CLEARING`. That
    is a visible, re-drivable strand rather than a silent one, but it is still a strand needing a
    human. A resumable state with a sweeper (issue #4182's second suggestion) is deliberately left
    out of scope — it needs a new status and a migration. `DomesticPaymentStrandedGauge` (#3273)
    exports age-in-status and is the reader for it meanwhile.
  - **Rollback**: revert the commit; the previous behaviour was to swallow and return
    `SENT_TO_CLEARING`.

- **2026-08-09** — Duplicate clearing submission closed (#4218). No new trust boundary and no new
  caller: the outbound edge to the scheme gateway (`pacs.008` → clearing-simulator / CERTIS) is the
  same one, and what changes is how many times a single payment may cross it. Previously: **more
  than once.** `submitScheme` wrapped both the outbound call and the follow-up status write in one
  `try/catch`, so a database failure after a successful submit was caught and logged as "holding in
  VALIDATED" — leaving a live clearing item behind a row asserting nothing was sent. A re-drive read
  that row and submitted again. Nothing downstream deduplicates: the `pacs.008` carries no
  idempotency key (only a deterministic `messageId`, which the receiver is free to ignore) and
  `openbank-clearing-simulator` performs no deduplication of any kind.
  - **Integrity (STRIDE-T)** is the property at stake, and it was violated in the worst available
    direction — an unauthorised *duplicate* money movement arising from an internal failure, with
    no external attacker required and no signal beyond one WARN line.
  - **Mitigation**: a `scheme_dispatched_at` marker written before the outbound call and in its own
    transaction, so it outlives any failure of the work that follows; `submitScheme` refuses to
    submit a payment that already carries it. The catch now covers the gateway call only, so a
    failed status write surfaces instead of being reported as "not submitted".
  - **New residual risk, accepted deliberately**: an ambiguous failure (a timeout, where the scheme
    may or may not hold the item) now **strands** the payment in VALIDATED rather than retrying it.
    The marker is cleared only when the gateway proves the request never left this process
    (`ConnectException` / `UnknownHostException`), which keeps the ordinary "scheme is down" case
    re-drivable. For an outbound money instruction a strand an operator can see is the correct
    trade against a duplicate nobody can recall — but it is a strand, it needs a human, and it is
    logged at ERROR for that reason. The partial index added in V8 is the query that finds them.
  - **Not addressed here**: `DomesticPaymentRepositoryImpl.update` still has no compare-and-set and
    the entity no `@Version`, so two concurrent workflows can both write one transition (#4218
    item 3). Pre-existing, independent of this defect, and deliberately left out of a money-path
    fix rather than enlarged into an aggregate-wide locking change.
- **2026-08-07** — ADR-0248 #3: new outbound trust boundary, `domestic-payment-service →
  document-service (GET /api/v1/documents/templates, POST /api/v1/documents/templates/preview,
  OIDC client-credentials)`, plus a new customer-facing endpoint
  `GET /api/v1/domestic-payments/{paymentId}/confirmation` (`@RolesAllowed("ROLE_VIEWER",
  "ROLE_OPERATOR","ROLE_ADMIN","ROLE_PAYMENTS")`, `@Authorize(action=
  "domestic-payment.confirmation.read", resource="#paymentId")`). Renders the payment
  confirmation document **synchronously, only on explicit customer request** — no
  pre-generation off `DomesticPaymentStatusChangedEvent`, no Kafka consumer, nothing cached or
  persisted a second time here or in document-service (document-service's `preview` endpoint is
  the existing non-persisting one; no `Document` row or `document.generated` outbox event is ever
  created for this call). `PaymentConfirmationService` reads the payment's own already-persisted
  record via `DomesticPaymentRepository.findById` and calls neither `save` nor `update` — it
  cannot affect payment status, and 409s (via `PaymentNotSettledMapper`) unless the payment has
  reached `SETTLED`. `PaymentConfirmationRenderAdapter` resolves the current PUBLISHED
  `POTVRZENI_O_PLATBE_CS`/`_EN` template body via `listTemplates`, then merges the payment's own
  data into it via `previewTemplate` — both document-service calls are read-only/non-persisting on
  the document-service side, so a retry can never double-render or double-persist anything.
  **Risk class = availability** (a document-service outage fails only the download itself — never
  payment initiation, status transition, or clearing/settlement — retryable by the customer, no
  data lost) and **confidentiality** (payment amount, debtor/creditor account numbers/bank codes,
  creditor name, and remittance info cross the boundary to document-service in the preview
  request; mitigated by OIDC client-credentials + cluster-internal-only document-service ingress,
  same posture as the existing `fraud-service`/`transaction-service` edges above). **New STRIDE
  rows**: Info disclosure (payment data sent to document-service for a one-shot render, never
  stored there) and Spoofing (a caller other than the payment's own viewer/operator requesting a
  confirmation) — mitigated by the same `@RolesAllowed`/`@Authorize` gate as every other read
  endpoint on this resource. `openapi.yaml` bumped `1.3.0` → `1.4.0` (additive). No DB schema
  change; rollback = revert the endpoint/use-case/adapter commit (document-service's `preview`
  endpoint and its own threat model are unaffected either way).

- **2026-08-03** — Missing required query/header parameter answered 500, not 400 (#3104). A required `@QueryParam`/`@HeaderParam` declared with a non-nullable Kotlin type was fed `null` by JAX-RS when the caller omitted it, and answered **500** rather than 400 (#3104). Kotlin's null-safety is compile-time only, so the declared type only decided where the failure landed: a non-suspend handler threw `Intrinsics.checkNotNullParameter` at the method boundary, and a **suspend** handler got no intrinsic at all, so the null flowed into the body. `Idempotency-Key` on createPayment. The existing guard `require(idempotencyKey.isNotBlank())` could not run for an ABSENT header: this handler is `suspend`, so no intrinsic was emitted and `null.isNotBlank()` threw NPE — the replay control answered 500 in exactly the case it was written for, while a BLANK header correctly gave 400. Now `require(!idempotencyKey.isNullOrBlank())`. This is a control that was partially inoperative, not a new one. No new caller or boundary. Rollback: revert.
- **2026-07-24** — Retire the legacy in-service orchestration; Temporal is the sole orchestrator
  (ADR-0120 Phase 6, issue #1917). `createPayment` no longer branches on `openbank.temporal.enabled`
  (removed) — it always dispatches `DomesticPaymentWorkflow` (screening → shadow fraud scoring → scheme
  submission → settle). The in-service `applyScreening`/`applyFraudGate`/`submitToScheme`/
  `attemptSettlement` flow and its now-unused ports (screening/aml/fraud/scheme/settlement + the
  `schemeSubmissionEnabled`/`fraudEnforcementEnabled` service flags) are deleted; `persistTransition`,
  `buildReceivedPayment`, the server-side transferScope derivation, and the query/transition endpoints
  are unchanged. Worker registration is gated separately by `openbank.domestic.worker.enabled` (default
  true; `%test` false) so @QuarkusTest boot does not connect to an absent Temporal frontend; a test
  `WorkflowClientTestProducer` backs the CDI `WorkflowClient` with an in-process `TestWorkflowEnvironment`.
  **No new trust boundary or external caller** — the same screening/fraud/scheme/settlement steps now run
  inside Temporal activities (each already OIDC-bounded; mTLS is NOT deployed for service-to-service HTTP — the only PeerAuthentication/DestinationRule in the tree is `openbank-infra/k8s/base/istio.yaml`, which no ArgoCD application applies, #1914. Kafka mTLS is real and separate), with the workflow adding durable retries +
  reverse compensation. The prerequisite that the Temporal path was missing shadow fraud scoring was
  fixed in the same change (the `shadowFraudScore` activity is now invoked between validation and scheme
  submission, matching the retired flow). Rollback: revert the commit (the flag + in-service flow return).
  Risk class = **availability/correctness** (durable orchestration replaces a best-effort in-process
  sequence); verified by a sandbox canary (worker registers + polls `openbank-domestic-payments`, real
  payments complete via Temporal end-to-end).
- **2026-07-08** — ADR-0155 rollout (issue #413): wired the four-eyes maker-checker mechanism
  piloted on sepa-payment. New `ApprovalConfig` (`ApprovalStore` via `RedisApprovalStore`) and
  new checker-facing endpoint `PATCH /api/v1/domestic-payments/approvals/{id}`
  (`@RolesAllowed("ROLE_OPERATOR","ROLE_ADMIN","ROLE_PAYMENTS")`,
  `@Authorize(action="domestic-payment.approval.decide")`). Two new exception mappers
  (`SelfApprovalNotAllowedMapper` → 403, `InvalidApprovalStateMapper` → 409). New config key
  `authz.four-eyes.enforce` (default `false`, no behavior change — see §4a). No DB schema
  change; rollback = `authz.four-eyes.enforce=false` (already the default) or revert the commit.

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
- **2026-07-05** — ADR-0122 Phase 2: `build.gradle.kts` now declares `openbank-libs-domain` +
  `openbank-libs-runtime` directly instead of the umbrella `openbank-libs` (which already re-exported
  both via `api()`). Pure Gradle dependency-graph change — no source import changed, no new transitive
  dependency introduced, no behavior change. Attack surface, trust boundaries, and STRIDE rows above are
  unaffected. No DB change; rollback = revert the commit.
- **2026-07-09** — ADR-0084 §4.2 (issue #667): the `domestic-payment → fraud-service` edge added
  2026-06-17 gains an ENFORCEMENT mode, flag-gated by `openbank.domestic.fraud.enforcement-enabled`
  (off by default — same runbook-gated-rollout posture as `authz.four-eyes.enforce`). With the flag
  on, a non-ALLOW verdict now has a real effect: REVIEW/CHALLENGE hold the payment in RECEIVED (a
  `FRAUD_REVIEW` case is opened via the existing `AmlCasePort`/aml-service case store — reused, not a
  new case-management system) for manual release; DECLINE rejects the payment outright
  (`DomesticRejectReason.FRAUD_SUSPECTED`). With the flag off, or verdict ALLOW, behavior is
  byte-for-byte identical to the shadow-only path. **No new trust boundary or data flow** — the same
  `fraud-service` call, same fail-open adapter contract (an unreachable fraud-service still scores
  ALLOW, so this gate can only ever add friction, never remove availability). **Risk class = integrity**
  (a miscalibrated non-ALLOW verdict can hold or reject a legitimate payment) — mitigated by the
  default-off flag: ADR-0084 itself discloses the v3/v4 thresholds as "first-pass, non-calibrated
  figures... pending shadow-mode data and risk-team input", so enforcement stays a deliberate,
  separately-reviewed flip, not bundled with this change. No DB schema change (the AML case store's
  `alertCode` column already accepts arbitrary values); rollback = flip the flag back to `false` or
  revert the commit.
- **2026-08-09** — **Retraction of the 2026-07-09 entry above, plus fraud-scoring observability
  (issue #4221).** The ENFORCEMENT mode credited on 2026-07-09 **does not exist**: the code it
  describes (`DomesticPaymentService.applyFraudGate` and the `fraudEnforcementEnabled` flag) was
  deleted on 2026-07-24 by the Temporal migration in the entry above, which carried over only the
  shadow scoring activity. The `openbank.domestic.fraud.enforcement-enabled` key survived with no
  reader anywhere in `src/main/kotlin`, so this threat model has credited an absent mitigation since
  that migration, and the documented runbook flip would have been a no-op. The key is removed here;
  restoring enforcement needs a workflow-level decision path and a hold state, tracked in #4403.
  **Treat the fraud gate as SHADOW-ONLY** — a non-ALLOW verdict is logged and the payment proceeds.
  Landed in the same change: a synthetic (fail-open) verdict is now distinguishable from a real one
  at the outcome (`FraudScoreOutcome.synthetic`), in the per-payment log line, and in the
  `openbank_fraud_scoring_degraded` gauge / `openbank_fraud_scoring_outcomes_total` counters, with
  alerts in `gitops/components/payments/prometheus-rules.yaml`. The adapter also now contains an
  `Error` rather than letting it escape the fail-open path. **Risk class = detectability** — no
  behaviour, boundary, data flow or DB schema changes; the fail-open posture is unchanged and
  deliberate (the verdict is observed, never enforced). This is a correction of the record and an
  added signal, not a new control. Rollback = revert the commit.

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

- **2026-08-02** — **New outbound trust edge: `account-service` party lookup.** `d949ce9ef` added
  `AccountServiceClient.getById` (`GET /api/v1/accounts/{accountId}`) and wired `AmlCaseAdapter` to
  call it with an OIDC client-credentials token, so an AML case carries the debtor's *party* id
  rather than an account id (ADR-0032 follow-up, #3274). Risk class = **information disclosure**
  (account→party linkage now crosses a service boundary) and **availability** (a second synchronous
  dependency on the AML-case path). Mitigations already in the code: the call is bearer-authenticated
  per request; every failure path is caught and returns `null`, so a lookup outage degrades the case
  record rather than blocking the payment; and a 404 is deliberately not logged as a warning, so a
  missing account is not treated as an error. Residual: `null` is indistinguishable between "no such
  account" and "lookup failed", so a sustained outage silently reintroduces cases without a party id
  — the exact condition #3274 exists to fix. Rollback: revert; the adapter's previous behaviour was
  to store the account id in `partyId`. Recorded here because #3431's measurement showed this change
  landed with no threat-model update.

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
