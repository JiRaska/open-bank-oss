# 28. Lending / credit bounded context: origination, servicing, collateral, IFRS 9 provisioning

Date: 2026-05-30
Status: Accepted
Delivery-Status: Partial

**Delivery note (updated 2026-07-09):**
- **Phase 0 (domain primitives in libs)** — ✅ Shipped: `Amortization` (three methods: annuity, equal-principal, bullet), `Ifrs9` (three-stage ECL), `Delinquency` (DPD, CRR Art. 178 90-day trigger), typesafe identifiers (`LoanApplicationId`, `LoanId`, `CollateralId`); `LendingPrimitivesTest` green.
- **Phase 2 (service + ledger integration)** — ✅ Shipped: `openbank-lending-service` with origination four-eyes flow (maker/checker/disburser), `RestLedgerPostingAdapter`, scheduled servicing posting, interest accrual scheduler, write-off (`WriteOffLoanUseCase`), and **collateral registration** (`Collateral`/`CollateralRequest`, `collateral` table, `CollateralRepository`, `CollateralUseCase`, `POST/GET /api/v1/lending/loans/{id}/collateral`) — AnaCredit protection-category data (type, declared value, haircut) recorded against a loan. Until Phase 3 increment 2 below, this data was recorded but not consulted by the ECL calculation. As of the collateral four-eyes follow-up below, registration is also maker-checker gated, mirroring origination.
- **Phase 3 (provisioning + AnaCredit/FINREP feeds)** — 🟡 Partial, incremental:
  - ✅ **Shipped (increment 1):** IFRS 9 stage bucketing (DPD-based, `Ifrs9.stage`) and a simplified ECL (EAD × flat-PD-by-stage × flat-LGD) computed over the existing repayment-schedule data (no new column needed for DPD); a persisted per-loan-per-period history (`loan_provisioning`, ADR migration V4); a scheduled monthly provisioning cycle (`ProvisioningCycleScheduler`, mirroring `InterestAccrualScheduler`'s Clock/`@Scheduled` structure) that re-buckets every ACTIVE loan and posts only the **delta** ECL versus the prior period to the ledger via the existing `RestLedgerPostingAdapter`/`LendingJournalFactory` (new `PROVISIONING` `PostingKind`, new `loan-loss-allowance` GL account); idempotent per `(loanId, period)`.
  - ✅ **Shipped (increment 2 — collateral-adjusted LGD):** **collateral** moves from "registered but not consulted" to **"registration + LGD reduction wired into the ECL calc"**. The pure `Ifrs9.collateralAdjustedLgd(lgd, haircutAdjustedCollateralValue, exposureAtDefault)` (`openbank-libs-domain`) reduces the flat/unsecured LGD by the loan's haircut-adjusted collateral cover relative to EAD — `effectiveLgd = max(0, lgd - (Σ marketValue·(1-haircut)) / EAD)`, clamped to `[0, lgd]` so over-collateralization floors loss at (near) zero rather than going negative, and a loan with no registered collateral is byte-identical to the pre-collateral calculation (no regression). `LendingService.snapshotFor` — shared by both the on-demand `GET .../provisioning` read and the scheduled cycle — fetches the loan's collateral and applies the adjustment before calling `Ifrs9.assess`; PD is untouched by this increment.
    **Explicitly still NOT built** (real follow-ups, not this increment): real-time collateral revaluation/mark-to-market (LGD reduction uses whatever `marketValue`/`haircut` was last declared or revalued at registration time — staleness risk); legal perfection-of-security-interest verification (a registered collateral row is a data claim, not a confirmed enforceable legal priority); collateral substitution/release workflows; cross-collateralization across multiple loans.
  - ✅ **Shipped (collateral four-eyes follow-up, issue #621):** increment 2's own threat-model update flagged that collateral registration had no maker-checker control, unlike origination/disbursement, even though a registration now directly moves LGD. Closed: `Collateral` gained `status` (`PENDING`/`APPROVED`/`REJECTED`), `registeredBy`, `decidedBy`, `decidedAt` (Flyway `V5`, existing rows backfilled to `APPROVED` — no behavior change for the pre-existing book); `applyCollateral` now sums only `APPROVED` items; a new `POST /api/v1/lending/collateral/{id}/decision` endpoint lets a DIFFERENT principal decide a `PENDING` registration, enforcing `decidedBy != registeredBy` domain-side (409 on violation) — the same shape as `decide()`'s existing `decidedBy != proposedBy` guard on origination. `lending.collateralRegister` was added to `rules.yaml: four_eyes.verbs` so the existing ADR-0155 `ApprovalStore`/`AuthorizeInterceptor` REST-pause mechanism (already wired for `lending.disburse`) also recognizes it — `authz.four-eyes.enforce` stays `false` (unchanged fleet convention; a separate, deliberate rollout flip), but the domain-level PENDING-until-decided gate is independent of that flag and is always on, so a single maker cannot make their own registration count toward ECL either way. See `docs/threat-models/openbank-lending-service.md` §7/§8 for the full STRIDE treatment.
  - ⚠️ **Explicitly not production-grade:** the PD/LGD are flat, conservative, non-calibrated placeholder constants (`RiskParameterSource.DEFAULT_PD_12M/DEFAULT_PD_LIFETIME/DEFAULT_LGD` — unchanged from Phase 1/2, not newly introduced here), not a behavioral/statistical PD model; no macroeconomic overlay or forward-looking scenario weighting. Per-`CollateralType` haircut percentages are declared by the caller at collateral registration (`CollateralRequest.haircut`) — a first-pass placeholder table (e.g. real estate 20%, vehicle 40%, cash 0%, securities 30% are reasonable starting assumptions used by tests/examples), **not actuarially or regulator-calibrated figures**. Actuarial/risk-team calibration is required before any production use — see `06-compliance.md`.
  - ⬜ **Still pending:** AnaCredit/FINREP F-18/F-12 field-level mapping and the cross-service wiring to `openbank-anacredit-service` (which today has no stage/DPD logic of its own — a natural, small follow-up: lending could publish a `loan.provisioned`/stage-changed outbox event for anacredit to consume, but that integration is not built here); real-time collateral valuation feeds and legal perfection/registration with external registries (see increment 2 above); full behavioral PD models remain deferred until a live loan book with real loss history exists (tracked in ADR-0097).
- **Reschedule / restructuring (forbearance, issue #667/#668)** — ✅ Shipped: `RescheduleLoanUseCase` replaces an ACTIVE loan's remaining UNPAID installments with a new contractual plan (new rate/term/first-due-date), generated from the outstanding balance via the same `Amortization.schedule` primitive `bookLoan` uses at origination — `POST /api/v1/lending/loans/{id}/reschedule`, role-gated `ROLE_CREDIT_RISK`/`ROLE_COMPLIANCE`/`ROLE_ADMIN`, same shape as `writeOff` (single trusted actor from the JWT subject, no cross-identity four-eyes check — a restructuring decision by one credit-risk officer, not a two-party maker-checker flow). Already-paid installments are never touched; new rows continue the installment numbering after the last paid one so a future repayment's ledger reference can never collide with an already-posted one. An optional `principalForgiveness` (debt relief, defaults to none) is deducted from the outstanding balance before the new schedule is built and, when positive, books a new `RESCHEDULE_FORGIVENESS` `PostingKind` — the same GL accounts as `WRITE_OFF` (loan-loss-expense / loans-receivable), kept as a distinct kind purely so an audit trail can tell a partial restructuring-forgiveness apart from a full write-off. The loan's `version` is bumped and persisted on every reschedule — the durable half of the forgiveness posting's idempotency key, so two separate reschedules of the same loan never collide on the same ledger reference. No new migration (reuses the existing `installment` table/columns).

## Context

OpenBank models deposits, payments, cards, FX and the supporting compliance domains, but it has **no
lending**. There is no loan account, no repayment schedule, no arrears tracking, no impairment. The
only traces of credit in the codebase are a product-catalog enum value and a few comments. This is the
single largest absent *business* domain, and its absence blocks more than the obvious "we can't book a
loan":

- **AnaCredit (ECB/2016/13, CNB reporting).** The granular credit register is a mandatory CNB return
  for any institution above the threshold. It is sourced almost entirely from lending data —
  instrument, financial, accounting (IFRS 9 stage, accumulated impairment), protection (collateral),
  arrears. With no lending domain there is nothing to report and nothing to reconcile against.
- **ICAAP / Pillar 2.** Credit risk is the dominant RWA contributor for a retail/SME bank. Without an
  exposure book — EAD, PD, LGD, stage, DPD — there is no credit-risk capital number to stress, so the
  ICAAP gap flagged in the domain-gap review cannot be closed downstream either.
- **FINREP F 18 (performing/non-performing exposures) and F 12 (impairment movements)** are populated
  from loan stage, DPD bucketing and ECL — the same primitives this ADR introduces.

The hard regulatory anchors a lending domain must honour from day one:

- **IFRS 9 §5.5** three-stage expected-credit-loss (ECL) impairment — 12-month ECL while performing,
  lifetime ECL on significant increase in credit risk (SICR) or default.
- **CRR Art. 178 / EBA GL on the definition of default** — the 90-days-past-due default trigger and
  the unlikely-to-pay signals, which also drive the IFRS 9 Stage 3 boundary.
- **EBA GL on loan origination and monitoring (EBA/GL/2020/06)** — origination is a governed,
  creditworthiness-assessed, four-eyes process, not a single write.
- **CNB / AnaCredit** granularity and the per-aggregate reconcilability the rest of the platform already
  demands (ADR-0026).

This ADR records **how** lending is carved into the platform: the bounded-context boundary, what is
pure domain math in `openbank-libs` versus what is a stateful service, how the loan book posts to the
ledger without owning balances it has no right to, and the phased rollout. It deliberately follows the
established realization pattern — **pure, unit-tested primitives in `openbank-libs`; a service built in
the hexagonal house style with ports whose `@Default` bindings are offline-buildable no-ops and real
integrations landing as build-time-gated `@Alternative @Priority` adapters** (ADR-0045, ADR-0022,
ADR-0026). The first slice — the domain primitives — is already built and tested; the rest is scoped
here.

Status legend: 🟢 GREEN = built + tested; 🟡 YELLOW = scaffolded, no-op default bound (no regression,
not yet live); ⬜ PLANNED = scoped here, not yet code.

## The decision

### D1 — One bounded context `lending`, four capabilities, one owning service

Lending is **one bounded context** owned by **one service, `openbank-lending-service`**, internally
organized around four capabilities rather than split into four micro-services:

| Capability | Responsibility | Core aggregate |
|---|---|---|
| **Origination** | Application intake, creditworthiness assessment, decision, four-eyes approval, disbursement | `LoanApplication` |
| **Servicing** | The live loan: contractual repayment schedule, installment posting, payoff, early repayment | `Loan` |
| **Collateral** | Security registered against a loan, valuation, haircut, allocation | `Collateral` |
| **Provisioning** | IFRS 9 staging + ECL per exposure, stage transitions, impairment movements | (read-model over `Loan`) |

These share one transactional database and one deployable. Splitting them would scatter a single
consistency boundary (a disbursement must atomically close the application, open the loan, register the
schedule and emit the ledger posting) across network calls and sagas for no ownership benefit — they
are one team's one book. The split that *does* matter — credit book vs. cash ledger vs. customer
identity — is a **service** boundary and is respected (D3, D4).

The three typesafe identifiers (`LoanApplicationId`, `LoanId`, `CollateralId`) are **already in
`openbank-libs`** (`domain.identifiers`, with JPA `AttributeConverter`s), following the platform
`EntityId` convention so they serialize and persist identically to every other aggregate id. 🟢

### D2 — Pure domain math lives in `openbank-libs` (`libs/lending`); the service owns state and I/O

The credit *mathematics* is pure, deterministic, examiner-auditable, and identical whether it runs in
the service, in a batch job, in a test, or in a future reporting module. It therefore lives in
`openbank-libs`, not behind the service boundary, exactly as the analytics reconciliation primitives do
(ADR-0026 D2). Three primitives, **built and unit-tested** (`LendingPrimitivesTest`, 11 cases): 🟢

- **`Amortization`** — repayment-schedule generation for three methods: `ANNUITY` (constant payment,
  French), `EQUAL_PRINCIPAL` (constant principal, German), `BULLET` (interest-only until maturity).
  Money is kept to the currency minor unit throughout; per-period rounding drift is absorbed by the
  final installment so the schedule **closes to exactly zero** — no lost or phantom cents. The schedule
  (`RepaymentSchedule` / `Installment`) is the contractual cash-flow plan a loan is booked against and
  the outstanding-balance source IFRS 9 reads.
- **`Ifrs9`** — three-stage staging (`stage(daysPastDue, sicr, creditImpaired, …)`) and ECL
  (`PD · LGD · EAD`, 12-month PD in Stage 1, lifetime PD in Stages 2/3), plus an `assess(…)`
  convenience that derives stage then computes ECL. `EclInputs` validates PD/LGD ∈ [0,1] and EAD ≥ 0
  at construction. Discounting to the effective interest rate is left to the caller (pass an
  already-discounted EAD when material) — the primitive stays a pure product, not a present-value
  engine.
- **`Delinquency`** — days-past-due from the oldest unpaid due date, the standard arrears buckets
  (`CURRENT … DPD_90_PLUS`), and the CRR Art. 178 `isDefaulted` trigger (> 90 DPD). DPD is the single
  input that drives both IFRS 9 staging and the default definition, so it is computed once, here.

What stays **in the service** (not in libs): persistence (Panache entities, Flyway schema), the
application/approval workflow and its four-eyes state machine, the ledger-posting outbox, credit-bureau
and collateral-valuation integrations, REST resources and their authorization. Libs has **no**
framework, persistence or network dependency — it is the same offline-buildable core every other module
depends on.

### D3 — The loan book does not own cash: it posts to the ledger via synchronous REST, and streams its events via the outbox

`openbank-lending-service` owns the **credit book** (the loan, its schedule, its stage). It does **not**
own customer balances or the general ledger — those are `openbank-balance-service` and
`openbank-ledger-service`. Every cash event in a loan's life (disbursement, each installment's
principal/interest split, fees, write-off) is a **ledger posting the lending service emits, never a
balance it mutates itself**.

Two distinct paths carry these facts off the loan book, and they are not the same channel:

1. **Ledger posting → synchronous REST.** `openbank-ledger-service` ingests double-entry journals
   through exactly one surface: `POST /api/v1/journals` (role `ROLE_OPERATOR`) — it has **no Kafka
   posting consumer**. So the loan book posts the way the reference money-mover (transaction-service)
   posts: a typed `@RegisterRestClient(configKey="ledger-service")` call behind a `LedgerCallGuard`
   resilience boundary (`@Retry` + `@Timeout` + `@CircuitBreaker`), authenticated by the
   `OidcClientRequestReactiveFilter` (client-credentials). An outbox→Kafka path was considered and
   rejected: it could not reach the ledger end-to-end, because nothing on the ledger side consumes a
   topic. The pure double-entry mapping lives in `LendingJournalFactory` (no Quarkus deps, fully
   unit-tested for balance/leg-direction per `PostingKind`); the HTTP plumbing is build-time gated by
   `@IfBuildProperty(name="lending.ledger.backend", stringValue="rest")` so the `@Default` no-op stays
   bound and the service builds/boots offline (the realization pattern, ADR-0045). **Reliability** is
   the journal's `idempotencyKey` (the posting `reference`) + a deterministic `transactionId`
   (`UUID.nameUUIDFromBytes(reference)`): a replay posts the same journal id, so the ledger dedupes —
   at-least-once delivery is safe.
2. **Event stream → transactional outbox.** The loan aggregate's state changes still flow through the
   existing transactional-outbox pattern (ADR-0003) for the **analytics / downstream** plane — the
   state change and the outbox row commit in one local transaction. This keeps the Kappa "single
   extraction path" property the analytics layer relies on (ADR-0022/0026): lending changes reach the
   rest of the platform only through its event stream, never a second read path into its database.

The outbox emits the loan aggregate's real `@Version` and a canonical `aggregateType` from the start,
so lending is **born reconciliation-ready** — it does not repeat the producer-side version/identity
parity gap ADR-0026 had to retrofit on four existing producers (that ADR's "Phase 2 precondition").

### D4 — Credit-risk inputs (PD/LGD) and bureau data come in through ports, default to no-op

PD, LGD and external credit-bureau / scoring data are **inputs** to the pure ECL math, not something
lending computes. They arrive through `application/port/out` ports whose `@Default` binding is an
offline no-op (a conservative constant or a "no bureau data" answer), so the service **builds and boots
with zero external dependency** — the platform invariant. Real integrations (a bureau adapter, a
risk-parameter source, a collateral-valuation feed) land later as build-time-gated `@Alternative
@Priority` adapters, exactly like the ClickHouse/Vault/Apicurio adapters. The IFRS 9 primitive consumes
whatever `EclInputs` the bound adapter supplies; swapping a no-op for a real PD model is a wiring
change, not a domain change.

### D5 — Security: every endpoint role-gated, four-eyes on credit decisions, never `@PermitAll`

- All lending endpoints are `@RolesAllowed` with **raw string literals** (e.g. `"ROLE_LENDING_OFFICER"`,
  `"ROLE_CREDIT_RISK"`, `"ROLE_COMPLIANCE"`), per the standing repo convention — never `@PermitAll`,
  never the imported `Roles` constants.
- **Credit decisioning is four-eyes (maker-checker).** Per EBA/GL/2020/06 and consistent with the
  analytics maker-checker control (ADR-0023 F3), the officer who assesses/proposes an approval cannot
  be the one who authorizes disbursement. This is a state-machine guard on `LoanApplication`
  (PROPOSED → APPROVED requires a *different* principal), enforced server-side, not a UI nicety.
- Loan, schedule, arrears and impairment data are customer financial data under GDPR; read endpoints
  are gated to lending/risk/compliance/audit roles and the access is audit-logged like every other
  sensitive read.

### D6 — Phased rollout

- **Phase 0 — domain primitives. 🟢 DONE.** `Amortization`, `Ifrs9`, `Delinquency` + the three
  identifiers and converters in `openbank-libs`, with `LendingPrimitivesTest` green and the Kover floor
  held. No service yet, so zero platform surface change.
- **Phase 1 — service skeleton (this ADR's scaffold). ⬜→🟡.** `openbank-lending-service` in the house
  pattern: build file from the `interest-service` template, `application.yaml`, Flyway V1 schema
  (`loan`, `repayment_schedule`/`installment`, `collateral`, `lending_outbox`), Panache entities, the
  `port/out` ports + `@Default` no-op adapters (ledger posting, credit bureau, collateral valuation),
  application services that orchestrate the libs primitives, and REST resources (role-gated). Registered
  in `settings.gradle.kts` and the CI workflow. The no-op defaults mean it boots and builds offline with
  **no regression risk** — nothing else depends on it yet.
- **Phase 2 — real ledger posting + origination workflow. ⬜→🟡.** Real ledger posting **🟢 DONE**:
  the `@Alternative` `RestLedgerPostingAdapter` (gated `lending.ledger.backend=rest`) posts every cash
  event to `ledger-service` as a balanced double-entry journal via synchronous REST behind
  `LedgerCallGuard`, with the pure `LendingJournalFactory` mapping each `PostingKind` to its debit/credit
  legs (unit-tested). The full cash lifecycle is now wired through that adapter: disbursement
  (DEBIT loans-receivable), per-installment repayment split (PRINCIPAL_REPAYMENT + INTEREST), and the
  collections terminal step — **write-off 🟢 DONE**: `WriteOffLoanUseCase` posts the loan's *remaining*
  exposure (not the original principal) as a `WRITE_OFF` journal (DEBIT loan-loss-expense, CREDIT
  loans-receivable), transitions the loan to `WRITTEN_OFF`, and emits a `loan.written_off` outbox event;
  it is role-gated to credit-risk/compliance and guards the state transition (refuses a non-ACTIVE loan
  or a fully-repaid one). The four-eyes application→approval→disbursement flow is now an explicit
  maker-checker REST cycle **🟢 DONE**: maker/checker/disburser identity is bound to the authenticated
  JWT subject (`SecurityIdentity`), never a client-supplied request-body string, and disbursement refuses
  a principal equal to the approver (segregation of duties).

  The **scheduled servicing posting loop 🟢 DONE** — and deliberately *accrual-basis*, not a naive
  "installment due → post cash". `InterestAccrualScheduler` runs a `@Scheduled` pass
  (`concurrentExecution = SKIP`, default every 24h) that recognizes each due installment's interest income
  on its due date independent of collection (IAS 1 accrual basis), via a new `INTEREST_ACCRUAL` posting
  (DEBIT interest-receivable / CREDIT interest-income — income earned, no cash leg). A new
  `interest-receivable` GL account carries the recognized-but-uncollected balance. Cash arriving later is
  split by whether the installment was already accrued: if so, repayment posts `INTEREST_SETTLEMENT`
  (DEBIT funding-clearing / CREDIT interest-receivable — the cash merely clears the receivable, income is
  *not* re-recognized); if the loan is repaid before the accrual pass runs, repayment posts the original
  `INTEREST` (direct cash-basis recognition). Interest income is thus recognized **exactly once**, gated
  by an idempotent `interest_accrued` flag on the installment (a `markAccrued` mutation guarded
  `WHERE interest_accrued = false`, plus a partial index for the accruable scan). Zero-interest legs are
  flagged without a ledger posting. Each accrual emits a `loan.interest_accrued` outbox event.
- **Phase 3 — provisioning + AnaCredit/FINREP feeds. 🟡 first increment DONE, feeds pending.** The
  scheduled IFRS 9 staging/ECL pass over the live book is **built**: `ProvisioningCycleScheduler` (mirrors
  `InterestAccrualScheduler`'s Clock-injected `@Scheduled` structure) re-buckets every ACTIVE loan monthly
  using the existing `Ifrs9`/`Delinquency` primitives over the existing repayment-schedule data (DPD
  needed no new column), persists the per-exposure stage + ECL to a new `loan_provisioning` history table
  (one row per loan per `yyyy-MM` period — both the delta baseline and the idempotency guard), and posts
  only the **delta** ECL versus the prior period as a new `PROVISIONING` journal (DEBIT loan-loss-expense /
  CREDIT loan-loss-allowance on an increase, reversed on a release) via the existing
  `RestLedgerPostingAdapter` + an extended `LendingJournalFactory` — no second ledger client. PD/LGD remain
  the flat, conservative placeholder constants from Phase 1 (`RiskParameterSource`), **explicitly not
  recalibrated or made production-grade by this increment** — see the Delivery note and `06-compliance.md`
  for the caveat. **Not yet built:** AnaCredit/FINREP F 18/F 12 field-level mapping and the cross-service
  event wiring to `openbank-anacredit-service` (which has no stage/DPD logic of its own today) — this is
  the remaining payoff that closes the downstream reporting/ICAAP gaps, tracked as a follow-up.

## Consequences

**Positive.** The platform gains its largest missing business domain with the credit *mathematics*
already built, tested and examiner-auditable in libs (the hard, get-it-exactly-right part — annuity
that closes to zero cents, IFRS 9 ECL, CRR-178 default — is done). The design respects the service
boundaries that matter: the loan book posts to the ledger and never owns cash, reaches the rest of the
platform only through its outbox stream, and is born reconciliation-ready (no retrofit of the
version/identity parity ADR-0026 had to do). It unblocks AnaCredit, FINREP F 18/F 12 and the credit-risk
input to ICAAP downstream. It is offline-buildable with no new external dependency, and four-eyes credit
decisioning is enforced server-side from the start.

**Negative / trade-offs.** This ADR delivers Phase 0 (libs) as code and *scopes* Phases 1–3 — the
service skeleton boots but does nothing real until the ledger-posting and origination adapters land, so
it is an honest 🟡, not a false green. ECL quality is only as good as the PD/LGD the bound adapter
supplies; the no-op default is deliberately conservative, and a real PD model / bureau integration is a
separate, sizeable piece of work (and its own model-governance concern) not solved here. AnaCredit/FINREP
field-level mapping is genuinely large and deferred to Phase 3 — this ADR provides the primitives
(stage, DPD bucket, impairment) those returns need, not the returns themselves. Introducing a new
deployable adds one more service to the CI/build matrix and the operational footprint.

## References
- ADR-0045 — lightweight ports + offline-buildable no-op defaults (the realization pattern)
- ADR-0003 — transactional outbox / Kafka (the single extraction path; lending streams its events this way — but posts to the ledger via synchronous REST, see D3)
- `openbank-transaction-service` — the reference money-mover whose `POST /api/v1/journals` ledger-posting contract (rest-client + `OidcClientRequestReactiveFilter` + circuit-breaker guard) lending mirrors
- ADR-0009 — postgres-per-service (lending owns its own DB, not balances/ledger)
- ADR-0022 — event-fed analytics layer (the Kappa single-extraction-path property lending preserves)
- ADR-0026 — OLTP source-side reconciliation (the per-aggregate version/identity contract lending is born compliant with)
- ADR-0023 — analytics regulatory hardening (the maker-checker control mirrored for credit decisioning)
- IFRS 9 §5.5 (three-stage ECL); CRR Art. 178 + EBA GL on the definition of default (90-DPD);
  EBA/GL/2020/06 (loan origination and monitoring); ECB Reg. 2016/13 + CNB AnaCredit; FINREP F 18 / F 12
- `openbank-libs` — `lending.Amortization`, `lending.Ifrs9`, `lending.Delinquency`;
  `domain.identifiers.{LoanApplicationId, LoanId, CollateralId}` (+ converters);
  `LendingPrimitivesTest` (the Phase 0 evidence)
- `openbank-interest-service` — the service build/template this scaffold is cloned from
