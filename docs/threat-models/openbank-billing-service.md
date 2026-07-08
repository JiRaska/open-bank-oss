<!--
SPDX-License-Identifier: Apache-2.0
Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
-->
# Threat model — billing-service

- **Status:** Lightweight STRIDE/DFD (ADR-0030 D2). Money-path bounded context. Phase 2c/2c-ii
  (persistence, transactional outbox, ledger posting, scheduled trigger) and phase 2d (DST
  invariant) have landed against the target design this model already described; phase 2e (fee
  reversal/refund) is a required follow-up before production go-live.
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

Trust boundaries: every inbound/outbound hop is service↔service over mTLS with OIDC bearer tokens.

## 3. Authn/Authz

- Service-to-service: OIDC client-credentials + mTLS (including the ledger posting call itself).
- The posting endpoint (`POST /api/v1/fees/post`) carries `@Authorize(action = "billing.post")`
  and is subject to the four-eyes `post` verb (`rules.yaml: four_eyes`); maker ≠ checker (enforced
  transparently by `AuthorizeInterceptor` + a Redis-backed `ApprovalStore`, ADR-0155);
  `postedBy` is bound to the JWT `sub` (`SecurityIdentity.principal.name`).
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
  `openbank-simulation`) is unit-tested in isolation (`BillingFeeConservationInvariantTest`) but
  is **not yet wired to a `BillingScenario`** that drives it through the seeded
  `SimulationRunner` — no such scenario exists yet (unlike `SepaSettlementScenario`). Until one is
  added, the invariant is registered in `MoneyPathInvariants.ALL` and trivially holds (empty
  `World.billingFees`) rather than exercising real assess/post traffic end-to-end in the harness.

## 6. Change log

- 2026-06-29 — initial model for the phase-2b skeleton (ADR-0143).
- 2026-07-07 — phase 2c/2c-ii/2d landed: persistence, transactional outbox, ledger `@RestClient`
  posting, the scheduled cycle trigger, four-eyes `billing.post` (ApprovalStore-backed), and the
  `billing-fee-conservation` DST invariant. Documented the `billing.post` vs. the ADR's literal
  `ledger.post` action-name deviation (§3) and the account-discovery / four-eyes-enforcement /
  DST-scenario gaps (§5).
