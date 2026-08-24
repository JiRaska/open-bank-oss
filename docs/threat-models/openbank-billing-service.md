<!--
SPDX-License-Identifier: Apache-2.0
Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
-->
# Threat model — billing-service

- **Status:** Lightweight STRIDE/DFD (ADR-0030 D2). Money-path bounded context. Phase 2c/2c-ii
  (persistence, transactional outbox, ledger posting, scheduled trigger), phase 2d (DST invariant,
  now wired to a seeded scenario) and phase 2e (fee reversal/refund) have landed against the
  target design this model already described. Real-environment (sandbox) e2e verification and the
  four-eyes enforcement flip remain outstanding before production go-live.
- **Related:** ADR-0143 (fee posting design), ADR-0138 (waiver engine), ADR-0039 (ledger golden
  source), ADR-0133 (audit chain), ADR-0100 (DST).

## 1. Scope & purpose

billing-service assesses a customer account's product fees for a billing cycle and posts the
chargeable ones to the ledger as balanced journals. It reads (not owns) account/balance context
and product fee definitions; it owns the `AssessedFee` record and the posting intent. It moves
money (debits the customer, credits fee income), so it is a money-path service.

## 2. Data flow (DFD)

1. Trigger: `POST /api/v1/fees/post` (operator/system-initiated) or the scheduled
   `BillingCycleScheduler` sweep → `BillingCycleService.assessAndPost(cycle, account, currency)`.
2. Reads: account-service + balance-service → `FeeContext` (balance, turnover, segment, currency);
   product-catalog → billable fee definitions.
3. Evaluates each fee with the shared `WaiverEvaluator` (openbank-libs).
4. Persists `billing_cycle_assessment` + one `assessed_fee` row per fee, and appends one
   `billing_outbox` row per chargeable (non-waived, non-zero) fee — **in the same transaction**
   (`BillingAssessmentRepositoryImpl.persistWithPostingIntent`).
5. `BillingOutboxDispatcher` → `LedgerOutboxEventPublisher` → ledger `POST /api/v1/journals`
   (DEBIT customer fee-receivable GL, `subAccountId = accountId` / CREDIT fee-income GL), keyed
   `fee-{cycleId}-{accountId}-{feeId}-{currency}`. On success the fee is marked `POSTED` with the
   ledger's journal id; a terminal (DEAD) outbox row marks it `FAILED` instead.
6. **Reversal (phase 2e):** Trigger: `POST /api/v1/fees/reverse?idempotencyKey=...` (operator,
   four-eyes) → `FeeReversalService.reverse`. Looks up the `AssessedFee` by its charge
   idempotency key; if `POSTED`, atomically flips it to `REVERSAL_PENDING` and appends a
   `billing_outbox` row (`billing.fee.reversal-intent.v1`) in the SAME transaction
   (`BillingAssessmentRepositoryImpl.persistReversalIntent`, mirrors step 4's atomicity). The same
   `BillingOutboxDispatcher`/`LedgerOutboxEventPublisher` dispatch this row too (dispatched on
   `eventType`), calling `LedgerPostingAdapter.postReversal` → ledger `POST /api/v1/journals`
   (CREDIT fee-receivable / DEBIT fee-income — the exact reverse), keyed
   `fee-reversal-{cycleId}-{accountId}-{feeId}-{currency}` (distinct from the charge's key). On
   success the fee is marked `REVERSED` with the reversal journal id.
7. **Annual fee-summary (ADR-0248, PAD Art. 5 push duty):** Trigger: `AnnualFeeSummaryScheduler`
   (disabled by default, `openbank.billing.annual-fee-summary.scheduler.enabled=false`) → pages
   account-service's fleet-wide `GET /api/v1/accounts/active` (reuses the existing
   `BillableAccountDiscoveryPort` trust boundary) → for each account,
   `AnnualFeeSummaryService.publishForAccount` reads `account-service GET /api/v1/accounts/{id}`
   a SECOND way (`AccountPartyLookupPort`, new — resolves `partyId`, the same existing endpoint
   `RestAccountContextPort` already calls for `productId`) and reads `assessed_fee` rows
   (`postedFeesForAccount`, POSTED-only, in-year) to build an `AnnualFeeSummary`. Appends a
   `billing_outbox` row (`billing.annual-fee-summary.ready`) idempotent per `(accountId, year)`
   (`appendAnnualFeeSummaryEvent`), dispatched by the SAME `BillingOutboxDispatcher` →
   `LedgerOutboxEventPublisher`, which routes this `eventType` to a **new Kafka producer**
   (`billing-events-out` → topic `openbank.billing.fee.event`) instead of the ledger REST call —
   billing-service's first Kafka publisher; document-service consumes it to render the PAD Art. 5
   annual statement of fees. `interestRate` is always `null` (no source for it anywhere in
   billing-service's domain, a documented gap, not fabricated data).

Trust boundaries: every inbound/outbound hop is service↔service over mTLS with OIDC bearer tokens,
EXCEPT the new outbound Kafka publish in step 7, which is a message-broker boundary (mTLS to the
Kafka cluster per `mp.messaging.connector.smallrye-kafka` config, not OIDC) — billing-service's
first departure from "every trust boundary here is OIDC+mTLS REST".

## 3. Authn/Authz

- Service-to-service: OIDC client-credentials + mTLS (including the ledger posting call itself).
- The posting endpoint (`POST /api/v1/fees/post`) carries `@Authorize(action = "billing.post")`
  and is subject to the four-eyes `post` verb (`rules.yaml: four_eyes`); maker ≠ checker (enforced
  transparently by `AuthorizeInterceptor` + a Redis-backed `ApprovalStore`, ADR-0155);
  `postedBy` is bound to the JWT `sub` (`SecurityIdentity.principal.name`).
- The reversal endpoint (`POST /api/v1/fees/reverse`, phase 2e) carries
  `@Authorize(action = "billing.reverse")` and is subject to the four-eyes `reverse` verb — already
  a registered `rules.yaml: four_eyes.verbs` entry, so this reuses the identical
  `AuthorizeInterceptor` + `ApprovalStore` infrastructure as `billing.post`, decided via the SAME
  `PATCH /api/v1/fees/approvals/{id}` endpoint; `reversedBy` is likewise bound to the JWT `sub`.
  Deliberately does **not** call ledger-service's own `POST /journals/{id}/reverse` (itself
  four-eyes gated at `ledger.reverse`, on the ledger's own principal) — a service-to-service OIDC
  client-credentials caller has no human "checker" distinct from the "maker" service account, so
  that second gate could never be decided and would orphan a `PendingApproval` forever. Billing
  posts its own compensating journal via the plain `POST /journals` contract instead, keeping the
  single human dual-control point at billing's own `billing.reverse` gate.
- **Deviation from the ADR's literal text, intentional:** ADR-0143 step 4 says
  `@Authorize(action = "ledger.post")`. The actual action is `billing.post` — `rest.rego`'s
  `money_path_scopes` derives the four-eyes scope from `rules.yaml: money_path_services` by
  stripping `openbank-`/`-service` (`openbank-billing-service` → `billing`), and billing has no
  `money_path_action_prefixes` override, so only an action literally prefixed `billing.` can ever
  match `four_eyes_required`. `ledger.post` would silently evaluate against **ledger's** scope (a
  different service) and never flag four-eyes on this endpoint — the opposite of the ADR's intent.
  See `openbank-libs/governance/policies/rest.rego` (`money_path_scopes`).
- `authz.four-eyes.enforce` stays `false` by default (same deliberate, separate rollout every
  other money-path service in the fleet makes, e.g. sepa-payment) — OPA already computes
  `four_eyes_required` correctly for `billing.post`; flipping enforcement on is a runbook-gated
  follow-up, not bundled with this change.

## 4. STRIDE

- **Spoofing** — only authenticated callers; service identity via OIDC + mTLS. A forged assessment
  trigger cannot post without passing the four-eyes `post` authorization.
- **Tampering** — `AssessedFee` and outbox rows are append-only; journals are immutable in the
  ledger; the audit chain (ADR-0133) is tamper-evident.
- **Repudiation** — every assessment and posting emits an audit record; `postedBy` is captured.
- **Information disclosure** — `FeeContext` (balance/segment) is processed transiently and not
  re-persisted beyond the audit record; transport is TLS; reads are OPA-authorized.
- **Denial of service** — assessment is idempotent and bounded per cycle; a redrive replays to the
  same ledger journal rather than amplifying. **Availability (self-inflicted):** a transient DB blip
  can poison the reactive pg pool, and Quarkus leaves every pool self-heal lever off by default, so
  the pool has no way to purge dead connections and wedges the service until a pod restart. The
  datasource readiness probe does not catch this — it is a connectivity check (`SELECT 1` on a fresh
  connection) that stays green while a reachable DB's pooled connections are dead, so the service
  keeps receiving traffic it cannot serve, invisibly. Mitigated by `idle-timeout`/`max-lifetime`/
  `max-size` on the reactive pool (bounds any wedge to `max-lifetime`) plus a 5xx-while-Ready alert
  independent of the health probe (`BillingServerErrorsWhileHealthy`). Fleet follow-up: #1682.
- **Elevation of privilege** — no customer-facing write path; only operator/system principals,
  RBAC via OIDC scopes + OPA.

### Money-path specific threats (ADR-0143)

- **Double-charge / replay** → business-natural idempotency key
  `fee-{cycleId}-{accountId}-{feeId}-{currency}` (the `feeId` dimension stops several fees on one
  account/cycle/currency collapsing to one key and under-charging) + ledger idempotency store.
  DST invariant: *Σ fees assessed == Σ fee journals posted* per cycle/account/fee/currency.
- **Charge-on-uncertainty** → the waiver engine charges only on evaluable conditions; the billing
  service **skips and flags** (never charges) when `FeeContext` cannot be resolved.
- **Unbalanced / wrong-direction journal** → the builder always emits a balanced
  DEBIT(customer GL, subAccount=account)/CREDIT(fee-income GL) pair; the ledger rejects unbalanced
  journals; DST invariant *Σ debit == Σ credit*.
- **Currency mismatch** (no FX in phase 2) → a rule whose threshold currency ≠ account currency
  fails closed in `WaiverEvaluator`; cross-currency charging is out of scope.
- **Wrongly-charged fee with no remediation path** (phase 2e) → `POST /api/v1/fees/reverse` posts
  a compensating journal under the four-eyes `reverse` verb, so a waiver-evaluation bug or bad
  `FeeContext` that slipped through as a charge is remediable without a manual ledger edit.
- **Double-reversal / reversal replay** → the reversal has its OWN idempotency key
  (`fee-reversal-{cycleId}-{accountId}-{feeId}-{currency}`, distinct from the charge's key) so it
  can never collapse into a charge replay; `FeeReversalService.reverse` is itself idempotent —
  reversing an already-`REVERSAL_PENDING`/`REVERSED` fee returns the existing fee unchanged
  instead of posting a second compensating journal.
- **Reversing a fee that was never charged** → `FeeReversalService` fails cleanly (404 "no
  assessed fee with that idempotencyKey", or 409 "fee exists but was never POSTED — nothing to
  reverse") rather than fabricating a compensating journal against nothing, or against a
  waived/still-pending/failed fee that never moved money in the first place.
- **Unvalidated input reaching persistence as a 500, and one currency stored under two
  spellings** → the fee endpoints validated only blankness, so the column definitions in
  `V1__init_billing.sql` (`currency CHAR(3)`, `cycle_id`/`account_id VARCHAR(64)`) were the only
  thing asserting the shape of caller input; Postgres' rejection is unmapped, so a client error
  surfaced as a 500 (found by the authenticated fuzz run, #3038). Two consequences beyond the
  status code: the error class leaked that the failure came from the database, and a currency
  differing only in case would have been a *distinct* value in both the column and the
  `(cycleId, accountId, currency)` idempotency key — a second assessment of the same fee under a
  second spelling, which the double-charge control above does not catch because the keys differ.
  Both endpoints now parse currency through `CurrencyCode` (validating + case-normalising) and
  bound the ids at the column width before anything is persisted.

## 5. Residual risks / assumptions

- Monthly turnover is derived from the ledger projection (not a first-class read port); projection
  lag is a correctness assumption to be reconciled.
- Fee reversal/refund is **not** in the initial charge path (milestone 2e) and is required before
  any production go-live.
- **Account discovery (2026-07-10):** `BillingCycleScheduler`'s batch can now be autonomously
  discovered from account-service's fleet-wide `GET /api/v1/accounts/active`
  (`BillableAccountDiscoveryPort` → the existing billing→account-service trust boundary and OIDC
  M2M client — no new boundary, a second read on an established one). Because a discovered sweep
  charges EVERY active account, it is double-gated: `openbank.billing.scheduler.enabled` AND
  `openbank.billing.scheduler.discovery-enabled` (both default `false`); a configured
  `account-ids` CSV always wins as a deliberate manual override. A failed page read aborts the
  sweep (fail-closed, logged) — never a silently partial batch; the monthly re-run is idempotent
  per (cycleId, accountId, currency). Residual: a compromised account-service response could
  inflate the batch — bounded by assessment idempotency and by every charge still resolving its
  own account/product context fail-closed before any posting.
- `authz.four-eyes.enforce=false` by default (see §3): until an operator flips it on for this
  service, `billing.post` is authorized (single principal) but not yet dual-controlled in
  practice — OPA computing `four_eyes_required` correctly is necessary but not sufficient without
  the enforce flag; tracked as a go-live gate, not a code gap.
- The DST fee-conservation invariant (`billing-fee-conservation`, ADR-0143 phase 2d,
  `openbank-simulation`) is now wired to a seeded `FeeBillingScenario`
  (`SimulationRunner.runSeed`) that drives assess → post → (a seeded fraction) reverse traffic
  through `World.billingFees` and a real `JournalEntry` posting every step — confirmed
  non-vacuous by deliberately breaking the posting leg and observing the invariant fail, then
  reverting. The full 300-seed happy-path sweep (`DstSimulationTest`) is green with the scenario
  wired in.
- **None of phase 2c/2c-ii/2d/2e has been deployed to or verified in a real environment
  (sandbox) yet.** All verification so far is unit/integration-level (Testcontainers Postgres +
  Redis) and the DST harness (pure-JVM, in-memory). Sandbox e2e verification of a charged, a
  waived, and a reversed fee all reconciling to the ledger is a required go-live gate.
- **Annual fee-summary (ADR-0248, scheduler-disabled, now credentialed but still
  sandbox-unverified):** the scheduler is disabled by default
  (`openbank.billing.annual-fee-summary.scheduler.enabled=false`) for the same reason the
  discovered cycle sweep is double-gated — an accidental fire publishes a real PAD Art. 5 push-duty
  event per active account, with no cheap way to retract it once document-service's consumer has
  acted. Residual risks distinct from the charge path above:
  - This is billing-service's **first outbound Kafka publish** — a new class of trust boundary
    (message broker, not OIDC+mTLS REST) that nothing else in this service's data flow uses. The
    boundary was *designed* 2026-08-07 (ADR-0248) but its runtime identity was never provisioned —
    the Deployment carried no `KAFKA_*` env vars, `KafkaUser`, or cert `Secret` at all, so
    `application.yaml`'s config had nowhere to connect (readiness probe: reactive-messaging channel
    `DOWN`, `Connection to node -1 (localhost/127.0.0.1:9092) could not be established`). Closed
    2026-08-19 (see change log) by provisioning the `billing-service` `KafkaUser` (Write+Describe on
    `openbank.billing.fee.event` only) and the matching mTLS cert projection. **This gap did not
    cause the 2 `billing.fee.post-intent.v1` rows dead-lettered in #4701** — that event type is
    dispatched via `LedgerOutboxEventPublisher.publishCharge`, a REST call to ledger-service that
    never touches this Kafka channel at all; #4701's actual cause was malformed JSON from
    unescaped fee names (fixed separately, #5642). The scheduler itself is still off, so this
    channel carries no live traffic yet.
    Delivery is at-least-once (standard outbox semantics); document-service's consumer is
    responsible for its own idempotent handling of `(accountId, year)` — billing only guarantees
    it appends the outbox row at most once per `(accountId, year)` (deterministic `aggregateId`
    + a transactional existence check), not that Kafka delivers it exactly once.
  - `interestRate` is **always `null`** — billing-service has no source for a debit/credit
    interest rate anywhere in its domain. A downstream consumer that silently treats `null` as
    `0.00` rather than "unknown" would misrepresent the PAD Art. 5 document; this is a data-gap
    risk for the consuming side (document-service), not something billing-service can close
    itself, and is called out explicitly so it is not missed at integration time.
  - `AnnualFeeSummaryLine.category` currently reuses the fee's display `name` — there is no PAD
    Annex II taxonomy mapping in this service yet (ADR-0248's own open item). Not a security risk,
    but a correctness/compliance one worth tracking alongside the interest-rate gap: the rendered
    document should not go to production before that mapping (and legal review of it) lands.
  - `AccountPartyLookupPort` reads the SAME account-service endpoint
    (`GET /api/v1/accounts/{id}`) `RestAccountContextPort` already calls, over the SAME existing
    OIDC M2M trust boundary — no new inbound/outbound relationship, just a second field
    (`partyId`) read off an already-trusted response.

## 6. Change log

- **2026-08-24** — Synthetic-journey taint now propagates over this service's existing internal REST clients through `SyntheticTaintClientFilter` (ADR-0252, #4348). This adds no caller, endpoint, network-policy edge, privilege or control bypass. It preserves the marker before a downstream persistence/event boundary; a fleet gate now requires every new client to choose propagation or a reasoned external boundary.

| Date | Change |
|---|---|
| 2026-08-19 | Operationalizes the 2026-08-07 entry below: `KafkaUser billing-service` (Write+Describe on `openbank.billing.fee.event` only, no incoming channels) + `ExternalSecret`-projected mTLS keystore/truststore + the matching `KAFKA_*` env vars on the Deployment (#4701 investigation). The trust boundary itself was already documented; nothing about its shape changed, only that it now has a live identity instead of falling through to `localhost:9092`. Does **not** address #4701's dead-lettered `billing.fee.post-intent.v1` rows — that path is REST-to-ledger, not Kafka; see the corrected §5 bullet and #5642. |
| 2026-08-07 | Trust-boundary change (ADR-0248, Refs #4109): new outbound Kafka publisher — billing-service's first — for the `billing.annual-fee-summary.ready` event (topic `openbank.billing.fee.event`, PAD Art. 5 annual statement of fees, consumed by document-service). New `AnnualFeeSummaryScheduler` (disabled by default), `AnnualFeeSummaryService` use case, and `AccountPartyLookupPort` (a second read of the already-trusted `account-service GET /api/v1/accounts/{id}` response, no new boundary). See §2 step 7 and the new §5 residual-risk bullet: `interestRate` is always `null` (no source in this service's domain) and `AnnualFeeSummaryLine.category` reuses the fee name pending the PAD Annex II taxonomy mapping ADR-0248 itself flags as an open item. |
| 2026-08-05 | Trust-boundary change (#3734): `operator-billing-write` now excludes `service-account-*` principals, and a new `prohibited` veto closes all three billing writes (`post`, `reverse`, `approval.decide`) to `service-account-openbank-edge` — the role_action_matrix grants all three to ROLE_OPERATOR and matrix-allows bypasses rule-level exclusions. Billing has no in-repo M2M caller at all (verified 2026-08-05: no edge URL, no backend REST client; account-service's billing-discovery read is INBOUND from billing), so no identity-scoped grant needed preserving. Ext moved from generator heredoc to standalone `billing_rest_ext.rego` with an 8-test opa suite. |

- 2026-06-29 — initial model for the phase-2b skeleton (ADR-0143).
- 2026-07-07 — phase 2c/2c-ii/2d landed: persistence, transactional outbox, ledger `@RestClient`
  posting, the scheduled cycle trigger, four-eyes `billing.post` (ApprovalStore-backed), and the
  `billing-fee-conservation` DST invariant. Documented the `billing.post` vs. the ADR's literal
  `ledger.post` action-name deviation (§3) and the account-discovery / four-eyes-enforcement /
  DST-scenario gaps (§5).
- 2026-07-08 — phase 2e landed: `POST /api/v1/fees/reverse` posts a compensating journal under
  the four-eyes `reverse` verb, reusing the existing `AuthorizeInterceptor`/`ApprovalStore`
  infrastructure; own idempotency key distinct from the charge's; idempotent re-reversal; clean
  404/409 failure modes. Phase 2d's DST invariant wired to a new seeded `FeeBillingScenario`
  (previously vacuous — confirmed and fixed, see §5). Updated the residual-risks list; removed the
  now-resolved DST-scenario gap and the phase-2e-not-built gap.
- 2026-07-10 — account discovery landed (issue #548 follow-up): the cycle sweep can page
  account-service's fleet-wide `GET /api/v1/accounts/active` via `BillableAccountDiscoveryPort`.
  No new trust boundary (same billing→account-service OIDC M2M client as the existing account
  read). Double-gated opt-in (`discovery-enabled` on top of `enabled`, both default off); CSV
  override wins; fail-closed page reads. Rewrote the §5 "no discovery port" residual accordingly.
- 2026-08-01 — input validation on `POST /api/v1/fees/assess` and `/fees/post` (#3038). No trust
  boundary change: same callers, same authn/authz, same downstream calls. The change is that
  caller input is now validated *before* persistence rather than by the schema, closing a 500 and
  the case-variant idempotency-key gap described in §4.
