---
date: 2026-06-29
decision-status: accepted
delivery-status: partial
authors: [Jiri Raska]
supersedes: []
superseded-by: []
delivery-repos: []
tags: [fees-billing, ledger, architecture]
summary: "Post product fees from a new money-path service, openbank-billing-service, which assesses fees per cycle with the shared waiver evaluator and posts balanced ledger journals through its own transactional outbox."
---

# ADR-0143 — Runtime product fee posting via a dedicated billing service

**Delivery note (updated 2026-07-10):**
- **Service skeleton and logic** — ✅ Shipped: `openbank-billing-service` Dockerfile + GitOps added (PR #2813); phases 1a/1b/2a/2b/2c-i merged; four-eyes `post` verb, `@RestClient` ledger posting, idempotency, balance/account context reads designed.
- **Phase 2c (persistence + outbox + ledger posting + scheduled trigger)** — ✅ Merged (PR #549,
  money-path review per ADR-0030): Flyway
  migrations (`billing_cycle_assessment`, `assessed_fee`, `billing_outbox`), a `@RestClient` ledger
  posting adapter (`LedgerPostingAdapter`/`BillingJournalFactory`, mirrors
  `openbank-settlement-service`/`openbank-lending-service`), the transactional outbox
  (`BillingOutboxDispatcher`/`LedgerOutboxEventPublisher`, built on `openbank-libs`'
  `AbstractOutbox*` primitives — assessment + intent-to-post commit atomically in one
  `sf.withTransaction`), and a `@Scheduled` monthly cycle trigger (`BillingCycleScheduler`,
  injected `Clock`, ADR-0100).
- **Phase 2c-ii (four-eyes authorization)** — ✅ Implemented: `POST /api/v1/fees/post` carries
  `@Authorize(action = "billing.post")` (**not** the literal `"ledger.post"` this ADR's step 4
  originally specified — see the threat model §3 for why `billing.post` is the action that
  actually activates `rest.rego`'s four-eyes gate for this service) + a Redis-backed
  `ApprovalStore` (ADR-0155); `authz.four-eyes.enforce` stays `false` by default pending a
  runbook-gated rollout, same as every other money-path service.
- **Phase 2d (DST invariant)** — ✅ Implemented: `billing-fee-conservation` in
  `openbank-simulation` (`MoneyPathInvariants`), asserting *Σ fees assessed == Σ fee journals
  posted* per cycle/account/fee/currency; unit-tested in isolation
  (`BillingFeeConservationInvariantTest`). **Now wired to a seeded `FeeBillingScenario`**
  (`SimulationRunner.runSeed`) that actually drives assess → post → (a seeded fraction) reverse
  traffic through `World.billingFees` every step — confirmed non-vacuous by deliberately breaking
  the posting leg (skipping the `recordPosted` call) and observing the invariant fail, then
  reverting; the full 300-seed happy-path `DstSimulationTest` sweep is green with the scenario
  wired in.
- **Phase 2e (fee reversal/refund)** — ✅ Implemented: `POST /api/v1/fees/reverse` posts a
  compensating journal (CREDIT customer fee-receivable GL, DEBIT fee-income GL — the exact
  reverse of the charge) for an already-POSTED `AssessedFee`, through the SAME transactional
  outbox + `LedgerPostingAdapter.postReversal` pattern as the charge leg. Own idempotency key
  (`fee-reversal-{cycleId}-{accountId}-{feeId}-{currency}`, distinct from the charge's) so the
  ledger never collapses a reversal into a charge replay. Gated by `@Authorize(action =
  "billing.reverse")` — `reverse` was already a registered `rules.yaml: four_eyes.verbs` entry,
  so this reuses the EXISTING `AuthorizeInterceptor` + `ApprovalStore` (ADR-0155) four-eyes
  infrastructure the charge path already wired, with no new approval mechanism. Deliberately does
  **not** call ledger-service's own `POST /journals/{id}/reverse` (itself four-eyes gated at the
  ledger's principal) — a service-to-service caller has no distinct human "checker" for that
  second gate, so it would orphan a pending approval forever; billing posts its own compensating
  journal via the plain `POST /journals` contract instead. New `PostingStatus` values
  `REVERSAL_PENDING`/`REVERSED`; new `assessed_fee` columns (`reversal_journal_id`,
  `reversal_reason`, `reversed_at`) via `V4__add_fee_reversal.sql`. Idempotent (re-reversing an
  already-REVERSAL_PENDING/REVERSED fee is a no-op replay) and fails cleanly — 404 for a fee never
  assessed, 409 for a fee never POSTED (waived/zero/still PENDING/FAILED) — never a generic 500.
- **Account discovery (issue #548 follow-up)** — ✅ Implemented: account-service exposes the
  fleet-wide `GET /api/v1/accounts/active` read (cursor-paginated, ACTIVE rows only, page cap
  200); `BillingCycleScheduler` consumes it via `BillableAccountDiscoveryPort` when the
  operator CSV is empty **and** `openbank.billing.scheduler.discovery-enabled=true` (its own
  opt-in on top of `enabled`, since a discovered sweep charges every active account in the
  fleet). An operator-configured CSV still wins as a deliberate manual override; default
  config remains a safe no-op.
- **Known gaps, honestly scoped (block production go-live):**
  `authz.four-eyes.enforce` needs a deliberate flip once the maker/checker runbook is reviewed;
  none of this has been deployed to or verified in a real environment (sandbox) yet.
- **Money-path go-live** — ⬜ Still deploy-gated pending: real-environment (sandbox) e2e
  verification of a charged + a waived fee + a reversed fee all reconciling to the ledger, and the
  four-eyes enforcement flip, per this ADR's Delivery milestones section.

## Context

ADR-0138 made the product fee **waiver** machine-executable: phase 1a added the parser/evaluator,
phase 1b promoted it to `openbank-libs` (`com.openbank.libs.product`: `WaiveConditionParser`,
`WaiverEvaluator.evaluate(condition, FeeContext) → WaiveReason`, fail-closed) and surfaced the
parsed rule on `GET /api/v1/fees`. What does **not** exist yet is the other half of a product
engine: nobody actually **charges** a fee. `openbank-product-catalog` holds fee *definitions*
only and must never post to the ledger (it is not a money-path service).

This ADR is ADR-0138 **phase 2** — the money-path step. It decides *where* and *how* fees are
posted to the ledger, so the implementation milestone can follow established conventions exactly
rather than invent them. It is a design/decision record; the implementation is gated behind the
money-path controls (ADR-0030: 2 approvals + threat model) and real-environment verification, so
it is explicitly **not** an autonomously-merged change.

Established facts the design must honour (verified against the codebase):

- **Ledger posting** is `POST /api/v1/journals` with a `PostJournalCommand` (idempotencyKey,
  transactionId, entry/value dates, balanced `lines` of DEBIT/CREDIT `JournalLineRequest` against
  GL accounts, optional `subAccountId` sub-ledger tie-out per ADR-0039 Phase B). The canonical
  consumer template is `openbank-settlement-service`'s `LedgerBookAdapter` (a `@RestClient` to the
  ledger) — and the deferred-posting template is `openbank-interest-service` (compute → outbox →
  dispatcher → Kafka).
- **`post`** is a four-eyes verb (`rules.yaml: four_eyes.verbs`) — ledger posting needs two principals.
- **Idempotency** is enforced by the ledger on `idempotencyKey`; the fleet convention is a
  business-natural key, e.g. settlement uses `settlement-book-{id}`.
- **`FeeContext`** inputs come from `openbank-balance-service` (booked balance, per-currency) and
  `openbank-account-service` (segment/type/currency); monthly turnover is derived from the ledger
  projection (it is not a first-class read port today).
- There is **no** existing fee/billing posting seam to extend.

## Decision

We will post product fees from a new money-path service, **`openbank-billing-service`**, and add it
to `rules.yaml: money_path_services`.

**Why a new service (not a bolt-on):** fee charging is a distinct bounded context (a periodic,
account-scoped, customer-*debiting* flow with its own cycle, its own waiver evaluation, its own
audit trail, and a clear growth path to recurring billing / standing charges / subscriptions). A
dedicated service gives it an isolated threat model, DST coverage, authorization surface and
release cadence, consistent with the repo's hexagonal-per-service convention (ADR-0002).

**Mechanism** (mirrors `settlement-service` for the posting leg, `interest-service` for the cycle):

1. **Assess** — `FeeAssessmentService.assess(cycleId, accountId, currency)`: gather `FeeContext`
   (balance via balance-service, segment/currency via account-service, monthly turnover via the
   ledger projection), read the product's fees from product-catalog, and run the shared
   `WaiverEvaluator` (libs) per fee. Persist one `AssessedFee` **per fee**. **Idempotent**: re-running
   a cycle for the same `(cycleId, accountId, currency)` returns the existing assessments, never new ones.
2. **Post** — for each non-waived, non-zero `AssessedFee`, post a balanced journal via the ledger
   `@RestClient`: **DEBIT** the customer fee-receivable GL (with `subAccountId = accountId` for
   sub-ledger tie-out), **CREDIT** the bank fee-income GL. Amount in the account currency
   (`baseAmount == amount`; **no FX in phase 2**). The posting is dispatched through the service's
   own **transactional outbox** (`AbstractOutbox*` in libs), so assessment and the intent-to-post
   commit atomically and the post is at-least-once with ledger idempotency as the dedup backstop.
3. **Idempotency key** — `fee-{cycleId}-{accountId}-{feeId}-{currency}` (one charge **per fee** per
   cycle/account/currency — a product with several fees posts several journals; a redrive replays
   each to the same ledger journal). The `feeId` dimension is essential: without it a multi-fee
   product (e.g. maintenance + excess-withdrawal, both CZK) would collapse to one key and silently
   under-charge — only the first journal would land, the rest deduplicated as replays.
4. **Authorization** — the assess/post path carries `@Authorize(action = "ledger.post")` and is
   subject to the four-eyes `post` verb; `postedBy` is the JWT `sub`.
5. **Fail-closed** — the waiver engine already charges when a condition cannot be evaluated; the
   billing service additionally **skips** (does not post) and flags any account whose `FeeContext`
   cannot be resolved, rather than charging on stale/absent inputs.

**Reused, not rebuilt:** `openbank-libs` `WaiverEvaluator`/`FeeContext` (ADR-0138 1b), the libs
outbox primitives (ADR-0013), the ledger journal contract, and injected `Clock` (DST).

## Alternatives considered

- **Extend `openbank-interest-service`.** Tempting: it already has the scheduled cycle, outbox,
  dispatcher, Kafka publisher, DB, money-path status and DST coverage — the cheapest infra path.
  **Rejected:** interest is income *credited* to the customer; a fee is a charge *debited* from the
  customer — opposite direction, opposite GL accounts. Folding charging into a service named
  "interest" makes the name lie and couples two bounded contexts that will diverge (the project
  prizes clean per-service contexts). The infra saving is real but does not justify the conceptual
  debt. Noted here because if recurring-billing scope never materialises, revisiting this is cheaper
  than the new service.
- **Embed in `openbank-account-service`.** Fees are account-scoped, so the account aggregate is a
  candidate home. **Rejected:** account-service already carries the account lifecycle + freeze/close
  state machines; billing is orthogonal and would bloat it and complicate its tests, against
  separation of concerns.
- **Post directly from `openbank-product-catalog`.** **Rejected outright:** the catalog is the fee
  *definition* system-of-record and is deliberately not money-path; it must not touch the ledger.

## Consequences

**Positive**
- Completes the product engine: a defined fee is now actually charged, by configuration, end to end.
- Clean, isolatable money-path service with its own threat model, DST invariant and authorization.
- Maximum reuse of the libs waiver engine and outbox; the posting leg copies a proven template.

**Negative**
- A new money-path service is real operational surface: DB-per-service + Flyway, GitOps manifests,
  NetworkPolicy, a free port (fleet port map), boot smoke test, OPA rego, release registration. This
  is exactly why the implementation is human-gated and verified in a real environment, not one-shot.
- Monthly turnover is not a first-class read port; phase 2 derives it from the ledger projection,
  which must be reconciled (a follow-up may promote it to a balance-service read port).

**Neutral**
- No change to existing services in this ADR (design only). The catalog's fee definitions and the
  libs engine are unchanged.

## Threat model (design-stage)

The full `docs/threat-models/openbank-billing-service.md` ships *with* the service (milestone 2b,
ADR-0030). The threats the implementation must mitigate, decided here:

- **Double-charge / replay** → business-natural idempotency key
  `fee-{cycleId}-{accountId}-{feeId}-{currency}` (the `feeId` dimension prevents several fees on the
  same account/cycle/currency collapsing to one key and under-charging) + ledger idempotency store;
  outbox redrive is safe. DST invariant: *Σ fees assessed == Σ fee journals posted* per
  cycle/account/fee/currency.
- **Charge-on-uncertainty** (waiver fail-open, stale/absent context) → waiver engine is fail-closed;
  billing **skips and flags** an account when `FeeContext` cannot be resolved rather than charging.
- **Unauthorized posting** → four-eyes `post` verb + `@Authorize(action = "ledger.post")`; `postedBy`
  bound to JWT `sub`; maker ≠ checker.
- **Unbalanced / wrong-direction journal** → builder always emits a balanced DEBIT(customer GL,
  subAccount=account)/CREDIT(fee-income GL) pair; ledger rejects unbalanced; DST ledger invariant
  *Σ debit == Σ credit*.
- **Currency mismatch** (no FX in phase 2) → a rule whose threshold currency ≠ account currency
  fails closed (already in `WaiverEvaluator`); cross-currency charging is out of scope.
- **Audit completeness** → every assessment and posting emits an audit record (tamper-evident chain,
  ADR-0133).

## Delivery milestones (post-acceptance, human-gated money-path)

- **2b** — service skeleton: module + domain (`AssessedFee`, `BillingCycle`) + `FeeAssessmentService`
  + ports + unit tests + boot smoke test + `docs/threat-models/openbank-billing-service.md` +
  `money_path_services` registration. No autonomous merge (2 approvals).
- **2c** — ledger `@RestClient` posting + transactional outbox + idempotency + context read clients
  (balance/account) + the assessment trigger (scheduled cycle).
- **2d** — DST fee-conservation invariant in `openbank-simulation`, wired to a seeded
  `FeeBillingScenario` that actually exercises it (see the delivery note); sandbox deployment and
  e2e verification of a charged + a waived fee reconciling to the ledger remain outstanding.
- **2e** — fee **reversal / refund** flow: a wrongly-charged fee (waiver bug or bad context) is
  reversible via a compensating ledger journal under the four-eyes `reverse` verb (see the
  delivery note for the implementation). Sandbox e2e verification remains outstanding before any
  production go-live.

## Compliance impact

- PCI DSS: not applicable (no cardholder data).
- DORA:    fee posting falls under existing ledger ICT controls; the new service inherits the
           money-path operational requirements (backup, DR, monitoring).
- GDPR:    minimal — `FeeContext` (balance/segment) is processed transiently for assessment and not
           newly persisted beyond the `AssessedFee` audit record.
- PSD2:    not applicable.
- CNB:     fee charging must be transparent and reconcilable; every charge is a ledger journal tied
           out via sub-ledger to the customer account, and assessments are auditable.

## References

- ADR-0138 — configuration-driven product fee rule engine (phases 1a/1b: the waiver engine reused here)
- ADR-0039 — ledger as golden source; sub-ledger tie-out (`subAccountId`)
- ADR-0013 — shared transactional outbox primitives in openbank-libs
- ADR-0030 — threat modeling requirement for money-path services
- ADR-0100 — deterministic simulation testing (fee-conservation invariant)
- ADR-0133 — tamper-evident audit chain
- Templates: `openbank-settlement-service` (LedgerBookAdapter / ledger @RestClient),
  `openbank-interest-service` (compute → outbox → dispatcher cycle)
