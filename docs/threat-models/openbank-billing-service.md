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

Trust boundaries: every inbound/outbound hop is service↔service over mTLS with OIDC bearer tokens.

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
  same ledger journal rather than amplifying.
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

## 5. Residual risks / assumptions

- Monthly turnover is derived from the ledger projection (not a first-class read port); projection
  lag is a correctness assumption to be reconciled.
- Fee reversal/refund is **not** in the initial charge path (milestone 2e) and is required before
  any production go-live.
- **No fleet-wide "list every billable account" read port exists yet.** `BillingCycleScheduler`'s
  account batch is therefore operator-configured (`openbank.billing.scheduler.account-ids`), not
  autonomously discovered — disabled by default (`openbank.billing.scheduler.enabled=false`) so it
  cannot charge anyone by accident before that follow-up lands. Mirrors the same honestly-scoped
  gap in `InterestService.accrueAll`/`capitalizeAll`.
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

## 6. Change log

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
