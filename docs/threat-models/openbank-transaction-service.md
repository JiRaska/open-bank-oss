<!--
SPDX-License-Identifier: Apache-2.0
Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
-->
# Threat model — transaction-service

- **Date:** 2026-06-25
- **Status:** Lightweight STRIDE/DFD (ADR-0030 D2). **Money-path** bounded context.
- **Service ADR:** see `docs/adr/`; platform controls per ADR-0029/0030/0034.

## 1. Scope & purpose

Transaction lifecycle: initiation, status tracking, and BIAN-aligned history/search (by IBAN/BBAN/
reference/counterparty/amount/date). Holds customer financial movement data.

## 2. Data flow (DFD)

```
[Payment / saga callers] --> (REST POST /api/v1/transactions) --> [transaction-service] --> [(Postgres: transactions)]
[Operator] --------------> (initiate) ------------------------^                                  |
[Viewer / service] ------> (GET list/search/{id}) -----------^                                  +--> [(tx_outbox)] --> [Kafka]
[Kafka: payment.scheme-accepted] --> (SchemeAcceptedConsumer) --> [transaction-service]
                                                                        +--> [Kafka: payment.scheme-accepted.dlq]  (on failure)
```

- **External entities:** payment flows / service callers + operators (initiate), viewers/service (read history), Kafka rail services (ACSC events).
- **Trust boundaries:** caller↔service (OIDC; OPA per ADR-0034 is a tracked follow-up); service↔Postgres; service↔Kafka (inbound `payment.scheme-accepted` + outbound `tx_outbox`).
- **Assets:** transaction records, counterparty data, the searchable history index.

### 2a. Kafka inbound trust boundary — `payment.scheme-accepted` (ADR-0108)

`SchemeAcceptedConsumer` opens a new **inbound trust boundary**: any Kafka producer able to publish to `payment.scheme-accepted` can trigger a settlement transaction in the money-path engine.

| Threat | Vector | Mitigation |
|---|---|---|
| **S**poofing — rogue producer | An attacker or misconfigured service publishes a forged `SchemeAcceptedEvent` to trigger an unauthorised settlement | Strimzi mTLS + ACL: only the `sepa-payment`, `sepa-instant`, `domestic-payment`, and `swift-service` service accounts hold `Write` on `payment.scheme-accepted`; all other producers are denied at the broker. The topic is not auto-created (explicit Strimzi `KafkaTopic` manifest). |
| **T**ampering — message in flight | Alter `amount`, `currency`, or `debtorAccountId` between producer and consumer | Kafka mTLS encrypts and authenticates the channel end-to-end; broker ACLs prevent a third party from producing to this topic. |
| **R**epudiation — deny settlement | Claim the ACSC event was never emitted | Producer-side outbox pattern in sepa/domestic/swift services; `originatingPaymentId` is stored on the `transactions` row for audit trail and reconciliation. |
| **I**nfo disclosure | Consumer logs expose PII from the event payload | Log only `paymentId`, `rail`, `amount`, `currency` — no IBAN, no party data. First 200 chars of raw payload logged only on deserialization failure. |
| **D**oS — message flood | High-volume publish to saturate the consumer group | Consumer is `@Blocking` (one thread per partition); `group.id` isolation means only transaction-service consumes; DLQ prevents partition stall on processing error. Topic partition count is 1 in sandbox (rate-limited); prod should tune. |
| **E**oP — settlement without SCA | Drive a customer-facing debit without SCA (ADR-0021) | `initiatedByPartyId` is **null** on `SchemeAcceptedConsumer`-generated commands — the SCA gate in `TransactionService` only fires for non-null party. Rail settlements are system-initiated, consistent with clearing/interest postings. |

## 3. Authn/Authz

- **K7 closed (this change):** `listTransactions`, `searchTransactions`, `getTransaction` were
  `@PermitAll` — an unauthenticated disclosure of customer financial data (search exposes IBAN/amount/
  counterparty lookups). Now `@RolesAllowed(SERVICE, VIEWER, OPERATOR, ADMIN)`. Initiation stays `OPERATOR`.
- Enforced by Quarkus OIDC; locked declaratively by `TransactionSecurityContractTest`.

## 4. STRIDE

| Threat | Vector | Mitigation |
|---|---|---|
| **S**poofing | Forged initiate from unknown caller | OIDC; `OPERATOR`/service role required |
| **T**ampering | Alter amount/counterparty post-initiation | Status state-machine; immutable financial fields; audit |
| **T**ampering | Settlement posts the customer the **wrong direction** (an outbound payment credits the payer, or an internal transfer fails to debit the source) — silent money creation/loss in the booked balance | `PaymentJournalFactory` branches the same-currency journal on direction: outbound = DEBIT payer deposit-control / CREDIT cash-clearing; incoming = the mirror; internal transfer = DEBIT source / CREDIT target deposit-control (two sub-ledger legs). Ledger `validateBalance` enforces per-currency debits==credits; the credit-positive booked delta the projection derives (`bookedDeltas`) therefore moves each customer the right way. Pinned by `PaymentJournalFactoryTest` (outbound, incoming and internal-transfer journal shape, plus the cross-currency four-legged entry). **Corrected 2026-08-20:** this row also credited a `PaymentSagaLedgerIT`, which exists in no source tree here and never has; the coverage it was credited for is genuinely present in `PaymentJournalFactoryTest`, so the claim was over-attributed rather than unfounded |
| **R**epudiation | Deny initiating a transaction | AuditEvent with `initiatedBy`; idempotency key persisted |
| **I**nfo disclosure | Unauthenticated search by IBAN/amount | **Fixed**: reads role-gated (§3) |
| **I**nfo disclosure | Domain metrics leak PII / enable per-transaction inference via high-cardinality labels | `DomainMetrics` low-cardinality contract (ADR-0077 / ADR-0079): the `openbank.outbox.backlog` gauge is tagged **only** by `service="transaction"` — never a transaction id, amount, IBAN, counterparty, party id, or reference. It exposes a single read-only count (PENDING + FAILED outbox rows), sampled off the Prometheus scrape thread from a cached `AtomicLong` refreshed by a scheduled `suspend` tick, so the scrape never runs a per-request DB query. `/q/metrics` is cluster-internal |
| **D**oS | Search flooding (expensive multi-criteria query) | `limit` coerced to ≤200; `offset` ≥0; pagination |
| **E**oP | Viewer initiates a transaction | Reads exclude write role; initiate = `OPERATOR` only, deny-by-default |

## 4a. Four-eyes approval (ADR-0155) — STRIDE supplement

`POST /{transactionId}/reverse` (`transaction.reverse`) is a money-path action OPA (`rest.rego`) can
flag `four_eyes_required`. New endpoint `PATCH /api/v1/transactions/approvals/{id}` lets a DIFFERENT
operator decide the resulting `PendingApproval`; the maker retries `POST /{transactionId}/reverse`
with an `X-Approval-Id` header. Unlike `account.freeze` (human-only maker), `transaction.reverse`
also permits `Roles.SERVICE` (M2M) makers — the checker role set below is unaffected by that, since
**`authz.four-eyes.enforce` stays `false` in this PR** — the `ApprovalStore`/endpoint are wired
(mirroring the account-service rollout, issue #413), but blocking is a deliberate follow-up flip,
not bundled here (see ADR-0155).

This service had **no Redis client wired before this change**. The `ApprovalStore` (Redis-backed)
is wired onto the payments namespace's existing shared Redis instance (`redis.payments.svc:6379`,
already used by sepa-payment/domestic-payment/sepa-instant for idempotency) rather than a new
dedicated instance — no new trust boundary or NetworkPolicy edge is introduced; the existing
`redis-ingress-allow-list` (same-namespace-only) already covers transaction-service as a caller.

| STRIDE | Threat | Mitigation |
|---|---|---|
| **S**poofing | A caller other than an operator decides an approval | `@RolesAllowed(Roles.OPERATOR, Roles.ADMIN)` + OPA `@Authorize(action="transaction.approval.decide")` on the decide endpoint |
| **E**oP | The maker approves their own reversal request (self-approval defeats maker-checker) — including an M2M (`Roles.SERVICE`) maker, since `transaction.reverse` permits SERVICE callers | `ApprovalStore.decide` throws `SelfApprovalNotAllowedException` (mapped to 403) when `decidedBy == makerId` — enforced in the domain port itself, not just the REST layer; `makerId`/`decidedBy` both resolve via the same `.principal.name` extraction (interceptor vs. `SecurityIdentity`) so the comparison can't silently mismatch for the same real caller |
| **T**ampering | A stale, mismatched, or already-consumed `X-Approval-Id` is replayed to unlock a different request | `AuthorizeInterceptor` requires the approval's `action` + `resourceId` + `makerId` to match the CURRENT request exactly, `status == APPROVED`, and marks it `EXECUTED` (one-time use) on success; any mismatch re-issues a fresh pending approval instead of proceeding |
| **R**epudiation | No record of who approved a gated reversal | `PendingApproval.decidedBy` + `decidedAt` recorded in the approval record itself (Redis, TTL-bounded — see ADR-0155 Negative consequences: not yet a permanent audit trail) |
| **I**nfo disclosure | Approval id enumeration reveals transaction/action metadata to an unauthorized caller | `find`/`decide` require the caller to already hold a valid, role-gated session; the id itself is a random id (`RedisApprovalStore`, not sequential) |
| **I**nfo disclosure | (issue #5679) `GET /api/v1/transactions/approvals` lists every pending four-eyes request with its `makerId` and age | Role-gated `Roles.OPERATOR`/`Roles.ADMIN` + `@Authorize(action = "transaction.approval.read")`; the payload carries approval metadata only — the action name, the resource id and who asked — never transaction amounts, counterparty or merchant data, which stay behind the existing read-role gate. Limit clamped to 200 (`MAX_PENDING_LIMIT`) — an unbounded query parameter over a Redis scan is a trivially reachable amplification, and this Redis is shared with three other services' idempotency traffic. Deliberately NOT filtered to exclude the caller's own requests: hiding a maker's request from them would not stop them attempting it (the guard is in `RedisApprovalStore.decide`, server-side) and would only make the queue lie about its own depth |
| **D**oS | Flooding `POST /{transactionId}/reverse` to exhaust the shared payments Redis with pending approvals | Bounded by the same rate-limit/idempotency controls as the gated endpoint itself; each `PendingApproval` is TTL-bounded (86400s) so abandoned records expire; the Redis instance is already shared/sized for three other services' idempotency traffic |

**DFD update:** adds `Operator (checker) → GET /api/v1/transactions/approvals → Redis
(approval:*, redis.payments.svc)` and `Operator (checker) → PATCH
/api/v1/transactions/approvals/{id} → Redis (approval:*, redis.payments.svc)` alongside the
existing `POST /{transactionId}/reverse` edge; the maker's retry reuses the existing DFD edge.
**Risk class:** integrity (segregation of duties) + confidentiality (approval record scope).
**Rollback:** `authz.four-eyes.enforce=false` (default) — the endpoint and store exist but do not
change any existing request's outcome until explicitly flipped.

## 4b. Merge balance sweep (ADR-0179) — STRIDE supplement

`POST /api/v1/transactions/merge-sweep` (`transaction.sweep`) moves a duplicate party's pocket
balance to the surviving party's pocket during an identity merge. It posts through the existing
saga (`InitiateTransactionCommand`, `type = ADJUSTMENT`), so the money mechanics, cover hold and
ledger projection are unchanged — the new surface is the endpoint and its action.

**Why a separate endpoint rather than `transaction.create` with `type = ADJUSTMENT`.** Two
independent reasons, both structural:

1. `four_eyes_required` is computed from the action name alone, with no awareness of the caller
   (`rules.yaml: four_eyes` guardrail). `transaction.create` is on the M2M payment rails, so a
   verb matching it could never be gated without pausing every automated payment the moment
   enforcement flips. A distinct operator-only action is the pattern the guardrail prescribes —
   the same conclusion sca-service reached for `device.enroll`.
2. `PaymentJournalFactory` never reads `transaction.type`. Passing `ADJUSTMENT` to the ordinary
   endpoint yields a journal byte-identical to a customer payment, so the correction would be
   indistinguishable from a transfer of value between two persons in the trial balance and on a
   statement. The structured `MERGE-SWEEP <ref>: party <a> -> <b>` description minted server-side
   is what carries the distinction into the ledger, which has no entry-type or reason-code column.

| STRIDE | Threat | Mitigation |
|---|---|---|
| **E**oP | An M2M caller uses the sweep to move funds between arbitrary accounts, bypassing the payment rails' SCA and rail controls | `@RolesAllowed(Roles.OPERATOR, Roles.ADMIN)` — no `Roles.SERVICE`; the new `sweep` verb is in `rules.yaml: four_eyes.verbs`, so it is maker-checker gated once `authz.four-eyes.enforce` flips (still `false`, as for `transaction.reverse` above) |
| **T**ampering | An operator sweeps to an account belonging to a third party, laundering a balance out of the duplicate under cover of a merge | `sourcePartyId`/`survivingPartyId` are recorded in the journal description, so the posting itself states the identity claim it was justified by; party-service's merge endpoint independently refuses to retire a party that still owns a non-CLOSED account, so a sweep to a wrong target cannot be completed into a merge without leaving the source account open and visible |
| **R**epudiation | The money movement cannot be tied back to the merge that authorised it | `mergeReference` is required and mint into the description; the party-service `party.merge` audit event carries the same reference in `approval_reference`, so either end resolves the other |
| **S**poofing | A caller forges `initiatedByPartyId` to make a bank correction look customer-initiated | The endpoint hard-codes `initiatedByPartyId = null` and drops any SCA fields — the request DTO has no such field to supply |
| **I**nfo disclosure | Party ids in a journal description leak into statements | The description carries party **ids**, never names, emails or RČ; it is already the field customer statements render, and an opaque UUID discloses nothing a statement holder does not already own |
| **D**oS | Repeated sweeps drain a pocket via replay | `idempotencyKey` is required and enforced by the existing transaction idempotency guard, identically to `POST /transactions` |

**DFD update:** adds `Operator → POST /api/v1/transactions/merge-sweep` alongside the existing
initiate edge; downstream (saga → ledger → balance projection) is unchanged.
**Risk class:** integrity (segregation of duties, auditability of a correction).
**Rollback:** revert the endpoint; the `sweep` verb is inert while `authz.four-eyes.enforce=false`,
and no existing request's outcome changes.

## 4c. Merchant enrichment (D5) — STRIDE supplement

`GET /api/v1/transactions` gained an optional `merchant` object: the public trading name, logo,
category and shop coordinates of the merchant behind a card transaction, resolved from a
`merchant_catalog` table keyed by the normalised acquirer descriptor. No new endpoint, no new
caller, no new role — the surface change is one additive, omitted-when-absent response field on an
already-authorised read.

**What the catalogue is, and what it must never become.** It holds PUBLIC BUSINESS data: where a
shop is. Nothing in it is keyed by customer, card or transaction, and no request writes to it. The
distinction matters because "merchant location" and "cardholder location" look alike in a schema
and are not alike at all under GDPR — the first is a business address, the second is tracking a
person's movements. A future change that keys a row by anything customer-specific crosses that
line and needs its own review.

| STRIDE | Threat | Mitigation |
|---|---|---|
| **I**nfo disclosure | Enrichment leaks a *cardholder's* whereabouts rather than a shop's | The catalogue has no customer-, card- or transaction-scoped column; a row is per merchant descriptor and identical for every customer who shopped there. Nothing customer-derived is written back |
| **T**ampering | A wrong or planted catalogue row attributes a payment to the wrong business — a lever for social engineering ("your payment to X") or for hiding one | Rows arrive only by migration, never from a request. Lookup is an **exact** match on the normalised key: no fuzzy or prefix matching, so a near-name cannot inherit another merchant's identity. `description` is passed through unmodified, so the raw acquirer text remains available and authoritative |
| **R**epudiation | A dispute is raised against a prettified name that does not appear on the acquirer record | Enrichment is display-only and additive. Disputes and SPAYD consume `description`, which this change does not touch; `source: ENRICHED` labels anything the bank resolved |
| **S**poofing | `logoUrl` points at attacker-controlled content rendered inside the bank app | URLs are catalogue-controlled and expected to be on a bank-controlled CDN; there is no request path that can set one |
| **D**oS | Enrichment adds a per-row query to every statement page | One query per page: descriptors are normalised, de-duplicated and fetched together, so cost is bounded by distinct merchants on the page, not row count |

**DFD update:** none. Same caller, same endpoint, same authorisation; one additional read of a
local reference table inside the existing request.
**Risk class:** integrity of merchant attribution (display), with an explicit privacy boundary on
what the catalogue may hold.
**Rollback:** revert; absent the field, responses are byte-identical to before (the field is
`NON_NULL`, so an unenriched transaction never carried it).

## 5. Residual risks / assumptions

- **Booked balance is now a ledger projection (ADR-0039 Phase D-2).** The saga no longer debits/credits
  balance-service directly; it posts the ledger journal and `placeHold`s the synchronous cover. The
  booked movement and the cover-hold release both land asynchronously in balance-service as it projects
  the ledger's `AccountBookedChanged` event (release keyed by `referenceId == transactionId`), so there
  is **no overspend window** between hold-release and the booked drop. `bookedAmount` is eventually
  consistent; overspend is still prevented synchronously by the hold. If the journal never posts the
  projection never fires, so the saga releases the hold during compensation (hold TTL is the final
  backstop). The cutover requires balance-service `openbank.balance.projection.enabled=true` to be
  deployed in lock-step — running with the saga debit still live would double-count the booked movement.
- Same-currency non-CZK incoming/outbound legs still route the bank side through the single CZK
  cash-clearing account; only CZK and the cross-currency (FX-routed) and internal-transfer (cash-clearing
  -free) shapes are exercised today. Per-currency cash-clearing is a tracked follow-up before a non-CZK
  rail settles.
- Initiation is idempotent (keyed by `idempotencyKey`) to prevent double-spend on retry.
- Search authorization is role-coarse — per-account/per-party scoping is OPA's job (ADR-0034 follow-up).
- A null security principal on initiate falls back to a zero-UUID actor — acceptable only while OIDC
  is mandatory at the gateway; revisit if the gateway becomes optional.
- Reversal now has the four-eyes *mechanism* wired (§4a) but not enforced
  (`authz.four-eyes.enforce=false`) — a candidate for a follow-up rollout under issue #413.
- **Temporal orchestration path (ADR-0120 Phase 1, flag-gated OFF).** When
  `openbank.transaction.orchestration.temporal.enabled=true`, `initiateTransaction` drives the payment
  through a durable `PaymentWorkflow` instead of `PaymentSagaOrchestrator`; activities wrap the **same**
  ports with identical arguments, so the §4 money-direction and the ADR-0039 D-2 hold-release invariant
  are preserved (success path never releases the hold; balance projection does). New trust surface: the
  worker opens a synchronous gRPC connection to the Temporal frontend (`:7233`) at startup — a *boot*
  failure if the `openbank-payments` Temporal namespace is unprovisioned or the NetworkPolicy blocks it
  (the `payments` k8s namespace is already allowlisted in `temporal-platform-ingress`). Workflow history
  becomes a second store of in-flight payment state (durable, replayable — a DORA Art. 17 positive); it
  must be access-controlled like the saga table. The ledger idempotency key on the Temporal path is
  `workflow-<txid>-ledger` (distinct from the saga path's `saga-<txid>-ledger`): safe because a
  transaction is initiated under exactly one flag value, but the canary cutover (Phase 4) must not
  re-initiate an in-flight saga transaction under the workflow path. Flag is OFF in all environments;
  this change is inert until a separately-approved cutover.

## 6. Change log

- **2026-08-24** — Synthetic-journey taint now propagates over this service's existing internal balance, FX and ledger REST clients through `SyntheticTaintClientFilter` (ADR-0252, #4348). This adds no caller, endpoint, network-policy edge, privilege or transaction-control bypass. It preserves the marker before a downstream persistence/event boundary; a fleet gate requires every new client to choose propagation or a reasoned external boundary.

- **2026-08-19** — `ApprovalResource` served only `PATCH /{id}` (decide), so a
  `transaction.reverse` four-eyes decision parked at 202 was discoverable only by whoever had been
  handed its approval id out of band — the ceremony completed only if the two operators were
  already talking, and the 24h Redis TTL then expired the request silently otherwise (issue #5679,
  mirroring sanctions #3472). Added `GET /api/v1/transactions/approvals` (§4a new I row); no new
  trust boundary crossed — same `RedisApprovalStore`, same role gate shape as the existing decide
  endpoint, additive-only OpenAPI change (ADR-0048). Rollback: revert the commit — the decide
  endpoint and the store are untouched.

- **2026-08-17** — **New inbound trust edge: the `lending` namespace.** #3931 added `lending` as
  an allowed ingress peer in this component's `network-policies.yaml`, so `lending-service` can
  now reach `POST /api/v1/transactions` from inside the cluster — the disbursement's
  customer-facing credit leg (the loan book's own ledger journal only ever posts to internal GL
  accounts and cannot move a customer's balance; see
  `docs/threat-models/openbank-lending-service.md` §2 items 7-8 for the calling side and why it
  exists). Same M2M `openbank-services` client, `Roles.OPERATOR`, already the caller identity for
  every other `initiateTransaction` edge (welcome bonus, SEPA/SWIFT/domestic settlement legs) —
  this adds a caller, not a new grant shape. Risk class = **elevation of privilege**: a NetworkPolicy
  decides reach, not permission, so the actual control is unchanged (`@RolesAllowed(Roles.OPERATOR)`
  + idempotent posting, §3) — this edge widens who may attempt the call. `type=CREDIT` with no
  `rail` books same-day per `SettlementScope` (#5225); the amount and target account are entirely
  determined by lending-service's own disbursement flow, not caller-suppliable beyond that.
  Rollback: drop the `namespaceSelector` entry for `lending`.
- **2026-08-07** — Merchant enrichment (D5). `GET /api/v1/transactions` answers an optional `merchant` object (clean name, logo, category, shop geo) resolved from the new `merchant_catalog` table via an exact match on the normalised acquirer descriptor. STRIDE supplement in §4c. No new endpoint, caller, role or Kafka topic. Three properties are load-bearing rather than incidental: matching is **exact** (fuzzy matching would hand one merchant's identity and coordinates to a similarly-named other, which is a fabrication with a trust cost, not a UX nicety); the field is `NON_NULL`, so a transaction with no catalogue entry produces a body byte-identical to before (serialising `"merchant": null` is a wire change for every existing consumer and did in fact fail the sepa-payment Pact verification); and `description` is passed through untouched, because disputes and SPAYD are built from the raw acquirer text and must never inherit a prettified name. Geo is null for card-not-present merchants, with a CHECK constraint keeping lat/lon both-or-neither so a half-filled row cannot render as a pin at 0°. Rollback: revert.

- **2026-08-03** — Missing required query/header parameter answered 500, not 400 (#3104). A required `@QueryParam`/`@HeaderParam` declared with a non-nullable Kotlin type was fed `null` by JAX-RS when the caller omitted it, and answered **500** rather than 400 (#3104). Kotlin's null-safety is compile-time only, so the declared type only decided where the failure landed: a non-suspend handler threw `Intrinsics.checkNotNullParameter` at the method boundary, and a **suspend** handler got no intrinsic at all, so the null flowed into the body. `accountId` on listTransactions. Listing "transactions for an account" with no account is a malformed request; the null reached `ListTransactionsQuery` and answered 500. The sibling searchTransactions endpoint already declares `accountId` nullable by design (search is deliberately multi-criteria) and is untouched. No new caller or boundary; `@RolesAllowed` and `@Authorize(action = "transaction.list")` are unchanged and still run first. Rollback: revert.
- **2026-07-12** — Wired the four-eyes (maker-checker) enforcement *mechanism* (ADR-0155) onto
  `transaction.reverse`, mirroring the account-service rollout (issue #413). New `ApprovalConfig`
  (`RedisApprovalStore` producer, wired onto this service's first-ever Redis client — reuses the
  payments namespace's existing shared Redis instance rather than a new dedicated one) and
  `PATCH /api/v1/transactions/approvals/{id}` checker-decide endpoint (`@RolesAllowed(Roles.OPERATOR,
  Roles.ADMIN)`, `@Authorize(action = "transaction.approval.decide")`); two new exception mappers
  (`SelfApprovalNotAllowedMapper` → 403, `InvalidApprovalStateMapper` → 409). STRIDE supplement added
  in §4a above. **`authz.four-eyes.enforce` stays `false`** — no behavior change to any existing
  request; this PR only wires the mechanism. Rollback: revert the commit (no DB/schema change;
  `ApprovalStore` records live in Redis with a TTL).
- **2026-07-11** — #747: `PaymentJournalFactory`'s cash-clearing leg (the bank-side leg of a
  one-sided inbound/outbound payment) was hardcoded to the CZK-only GL account regardless of the
  transaction's actual currency, so ledger-service rejected any non-CZK one-sided payment (422,
  currency mismatch) — confirmed live while building the issue #669 write benchmark. Added a
  per-currency `CASH_CLEARING` map (EUR/USD/GBP, mirroring the existing `DEPOSIT_CONTROL`/
  `FX_POSITION` pattern) and the corresponding `gl_accounts` seed
  (`V14__cash_clearing_accounts_per_currency.sql`, ledger-service). Purely additive reference
  data + a lookup-by-currency change; no new trust boundary, no change to the CZK path (same
  account id as before). Risk class = **integrity** (correct GL routing, not money-direction —
  the D-2 direction invariant from 2026-06-17 below is untouched). Mitigated by two new
  `PaymentJournalFactoryTest` cases asserting the EUR cash-clearing leg resolves to the new
  per-currency account, not the CZK one.
- **2026-06-28** — ADR-0120 Phase 1: Temporal payment orchestration scaffolding (flag-gated, default
  OFF). Additive `PaymentWorkflow` + activities mirroring `PaymentSagaOrchestrator`; no cutover, no saga
  removal. Risk class = **integrity** (must preserve the §4 money-direction + D-2 invariant) + new
  **gRPC trust boundary** to the Temporal frontend; mitigated by `PaymentWorkflowImplTest` (asserts the
  success path never releases/reverses and compensation unwinds correctly), `PaymentActivitiesImplTest`
  (port-args identical to the orchestrator), and the flag defaulting OFF. See §5.
- **2026-06-25** — ADR-0108 rail settlement consumer. Opens new Kafka inbound trust boundary (`payment.scheme-accepted`); threat analysis in §2a. Spoofing mitigated by Strimzi mTLS + per-service-account ACL. DLQ topic (`payment.scheme-accepted.dlq`) provisioned. `originatingPaymentId` stored for reconciliation audit trail.
- **2026-06-25** — #2013 mTLS + write-ACL hardening. **⚠️ PREMATURE — see 2026-06-29.** `KafkaUser` manifests (`authentication: tls`) were deployed in `openbank-infra/gitops/components/payments/kafka-scheme-accepted-acl.yaml`, but **no TLS-auth listener existed on the cluster** (only anonymous `plain:9092`), so no client could present those identities and the §2a gap was *not* actually closed. The ACLs only denied the legitimate (anonymous) consumer → settlements stuck in PROCESSING. The ACLs were removed in #2554 to unblock settlement.
- **2026-06-29** — ADR-0137 Kafka mTLS migration. §2a **genuinely enforced**, topic-scoped. Added a `tls:9093` mutual-TLS listener; re-introduced the five `KafkaUser`s (now named by service identity — one cert per service) and their ACLs; wired all five services to connect over mTLS using Strimzi-minted keystores projected `messaging`→`payments` by External Secrets. Spoofing mitigation now real: `payment.scheme-accepted` is deny-by-default via its ACLs (SimpleAuthorizer per-resource), so only the four rail principals may `Write` and only `transaction-service` may `Read`/commit on group `transaction-scheme-accepted-cg`; `User:ANONYMOUS` (anyone on the kept plaintext listener) is denied because ACLs bind to the principal, not the listener. The cluster-global `allow.everyone.if.no.acl.found` flag is deliberately left `true` (flipping it is a separate fleet-wide program); this topic does not depend on it. Note: the four rails still settle over HTTP today, so the producer `Write` grants are provisioned ahead of the ADR-0108 event path being wired.

- **2026-06-17** — ADR-0039 Phase D-2 settlement cutover. (1) **Direction fix:** `PaymentJournalFactory`
  same-currency journal now branches on payment direction — outbound DEBITs the payer's deposit-control
  (was an unconditional CREDIT that paid the payer), internal transfer posts two deposit-control
  sub-ledger legs (DEBIT source / CREDIT target). This is the latent **T — tampering** money-direction
  defect (§4) that had to be fixed before any payment rail settles through the engine. (2) **Dual-write
  removed:** the saga drops `balanceCoverPort.debit/credit` (and the compensation refund); booked balance
  is the ledger projection's sole mover, the cover hold released by the projection. Risk class =
  **integrity** (money direction + single booked source of truth), mitigated by `PaymentJournalFactoryTest`
  (real, still present) and — as written at the time — two classes that are not in the tree:
  `PaymentSagaOrchestratorTest` and a `PaymentSagaLedgerIT` that was never committed (the IT noted
  2026-08-20, the orchestrator test noted 2026-09-03; both left in place because a change log
  records what was claimed on the day, and correcting them silently would erase the evidence that
  they were). `PaymentSagaOrchestratorTest` went away with its subject: ADR-0120 Phase 5 made
  Temporal the sole orchestrator and removed `PaymentSagaOrchestrator` itself, so the surviving
  coverage of that path is `PaymentWorkflowImplTest`. Coupled
  balance-service change:
  `openbank.balance.projection.enabled=true`.
- **2026-05-30** — K7/ADR-0018: role-gated the previously `@PermitAll` read endpoints; raw-string
  role migrated to `Roles` constant. No DB/flow change. Risk class = **confidentiality**, mitigated
  by `TransactionSecurityContractTest`.
- **2026-06-11** — Outbox-backlog gauge (`TransactionOutboxBacklogGauge` + `countProcessable` on the
  outbox port, ADR-0077 / ADR-0079). Publishes `openbank.outbox.backlog{service="transaction"}` — a
  single low-cardinality count of un-drained (PENDING + FAILED) outbox rows. Touches the **I —
  information disclosure** row: no transaction id / amount / IBAN / counterparty / PII ever becomes a
  label; the count is read off the scrape thread from a cached `AtomicLong` (scheduled `suspend`
  refresh), so no per-scrape DB query. No new endpoint, DB change, data flow, or trust boundary
  (read-only count over the existing `tx_outbox` table). Risk class = **confidentiality** (metric
  cardinality), mitigated by `TransactionOutboxBacklogGaugeTest`.
- **2026-07-19** — ADR-0179 merge balance sweep: new `POST /api/v1/transactions/merge-sweep`
  (`transaction.sweep`, OPERATOR/ADMIN only) and a new `sweep` entry in `rules.yaml:
  four_eyes.verbs`. Posts through the existing saga with `type = ADJUSTMENT` and a structured,
  server-minted description — no change to the money mechanics, cover hold, or ledger projection.
  Touches the **E — elevation of privilege** and **T — tampering** rows (§4b): the action is
  deliberately distinct from `transaction.create` so it can be four-eyes gated without pausing the
  M2M payment rails, and so the resulting journal is distinguishable from a customer payment.
  Risk class = **integrity** (segregation of duties + auditability of a correction). The
  **auditability** half is mitigated by `MergeSweepDescriptionTest`, which pins the server-minted
  description's prefix, its merge reference and both party ids in survivor-last order, and asserts
  it names no PII — that string is the only thing distinguishing a merge correction from an ordinary
  transfer in the trial balance. The
  **segregation-of-duties** half is weaker than this entry claimed, in three separable ways, and
  only the first is a missing artifact. (1) `TransactionResourceMergeSweepTest`, named here as the
  mitigation, is in **no** file of any kind — `git grep -n TransactionResourceMergeSweepTest` finds
  nothing on any path, so the name resolves to nothing rather than to a renamed class. (2) What does
  cover `mergeSweep` is `TransactionSecurityContractTest`'s sweep-all assertion, which walks every
  HTTP endpoint on `TransactionResource` by reflection and requires each to be `@RolesAllowed` and
  never `@PermitAll` — so the endpoint cannot silently become permit-all, and that much of the claim
  does hold. Its **specific** role set is not pinned, unlike `listTransactions`, `searchTransactions`,
  `getTransaction` and `initiateTransaction`, which the same class pins by name: widening
  `mergeSweep` from OPERATOR/ADMIN to any other non-empty role set would pass every test in the
  module. (3) No test exercises the sweep endpoint over HTTP at all. What gates it today is therefore
  declarative — `@RolesAllowed(Roles.OPERATOR, Roles.ADMIN)` plus
  `@Authorize(action = "transaction.sweep")` on `TransactionResource.mergeSweep` — with the
  never-permit-all half locked by test and the exact role set not.
  `authz.four-eyes.enforce` remains `false` (`AUTHZ_FOUR_EYES_ENFORCE:false`); the verb is inert
  until that flip, so the four-eyes leg of the segregation-of-duties claim is not enforced in any
  environment today.
- **2026-08-27** — Transaction-initiation trace contract. `TransactionService` emits the internal
  `transaction.initiate` span only with the terminal transaction status. It deliberately excludes
  amount, account/party identifiers, description, idempotency key and payment metadata. Risk class
  = **confidentiality** (telemetry data minimisation) and **availability** (a trace exporter must not
  alter initiation); the span is assertion-backed by `TransactionApiIT`, which drives the real HTTP
  endpoint against PostgreSQL/Redpanda Testcontainers and the test Temporal terminal-write workflow.
  The contract proves this service boundary only — it does not claim a distributed downstream trace.
- **2026-09-07** — Authentication-failure response shape. An unauthenticated call to a
  `@RolesAllowed` endpoint answered with Quarkus's bare `Not Authorized` string, while every other
  error from this service is an `ApiError` document: `io.quarkus.security.UnauthorizedException` is
  not a `WebApplicationException`, so `WebApplicationExceptionMapper` — which already maps status
  401 to `ErrorCode.UNAUTHORIZED` — never saw it. The service now registers the shared
  `UnauthorizedExceptionMapper` from `openbank-libs-runtime` via a thin `@Provider` subclass, so a
  401 carries the standard envelope. Risk class = **integrity of the client contract**, not
  confidentiality: the trust boundary itself is unchanged, the endpoint is refused exactly as
  before, and the envelope adds no detail about why — the message is a constant
  (`Authentication required`), never the exception's own text, so nothing about the token, the
  principal or the failure reason reaches the caller. What changes is that a caller parsing the
  error body no longer breaks on the one response it is most likely to receive. Assertion-backed by
  the swift, sdd and interest provider-replay interactions, which each require a 401 for a debit
  presented without a valid M2M identity and could not be verified at all until this landed
  (issues #8993, #8984).
