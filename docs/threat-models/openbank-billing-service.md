<!--
SPDX-License-Identifier: Apache-2.0
Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
-->
# Threat model — billing-service

- **Status:** Lightweight STRIDE/DFD (ADR-0030 D2). Money-path bounded context. Phase 2b is the
  service skeleton (no posting yet); this model covers the target design from ADR-0143 so the
  posting leg (phase 2c) lands against an agreed threat picture.
- **Related:** ADR-0143 (fee posting design), ADR-0138 (waiver engine), ADR-0039 (ledger golden
  source), ADR-0133 (audit chain), ADR-0100 (DST).

## 1. Scope & purpose

billing-service assesses a customer account's product fees for a billing cycle and posts the
chargeable ones to the ledger as balanced journals. It reads (not owns) account/balance context
and product fee definitions; it owns the `AssessedFee` record and the posting intent. It moves
money (debits the customer, credits fee income), so it is a money-path service.

## 2. Data flow (DFD)

1. Trigger (scheduled cycle, phase 2c) → `FeeAssessmentService.assess(cycle, account, currency)`.
2. Reads: account-service + balance-service → `FeeContext` (balance, turnover, segment, currency);
   product-catalog → billable fee definitions.
3. Evaluates each fee with the shared `WaiverEvaluator` (openbank-libs).
4. Persists `AssessedFee` per fee; appends a fee-journal command to the transactional outbox.
5. Outbox dispatcher → ledger `POST /api/v1/journals` (DEBIT customer fee-receivable GL /
   CREDIT fee-income GL), keyed `fee-{cycleId}-{accountId}-{feeId}-{currency}`.

Trust boundaries: every inbound/outbound hop is service↔service over mTLS with OIDC bearer tokens.

## 3. Authn/Authz

- Service-to-service: OIDC client-credentials + mTLS.
- The posting action carries `@Authorize(action = "ledger.post")` and is subject to the four-eyes
  `post` verb (`rules.yaml: four_eyes`); maker ≠ checker; `postedBy` is bound to the JWT `sub`.

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

- Phase 2b moves no money: the read/post adapters are no-op stubs (resolve → null ⇒ skip), so there
  is no live charging surface yet. The threats above apply once phase 2c wires the real clients.
- Monthly turnover is derived from the ledger projection (not a first-class read port); projection
  lag is a correctness assumption to be reconciled.
- Fee reversal/refund is **not** in the initial charge path (milestone 2e) and is required before
  any production go-live.

## 6. Change log

- 2026-06-29 — initial model for the phase-2b skeleton (ADR-0143).
