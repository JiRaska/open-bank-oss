# AnaCredit granular credit-exposure reporting (overdrafts)

Date: 2026-05-30
Status: Accepted
Author(s): OpenBank core-banking

## Context

The multi-currency current account now grants **credit** in two shapes that did not exist when
the analytics layer (ADR-0022/0023) was drawn up: arranged + unarranged **overdrafts** on the
current account (`openbank-balance-service`, products `CURRENT_*`, `OVERDRAFT_PERSONAL`) and the
lending bounded context (ADR-0028). Any euro-area credit institution that books credit to legal
entities falls under **AnaCredit** — the ECB's *Analytical Credit Datasets* — established by
**Regulation (EU) 2016/867 (ECB/2016/13)** and collected nationally by the **ČNB** under its
statistical-reporting powers (zákon č. 6/1993 Sb. o ČNB; vyhláška o předkládání výkazů ČNB).
We have no AnaCredit feed at all, which is a hard go-live blocker the moment we book a reportable
corporate exposure.

AnaCredit is **granular, per-instrument** reporting — not an aggregate return. Its forces make it
a distinct bounded context rather than a view inside `openbank-analytics-sink`:

1. **It is a regulatory *return*, not a warehouse query.** `analytics-sink` (ADR-0022) is a generic
   event-fed ClickHouse sink with schema governance, WORM and erasure. AnaCredit needs domain logic
   the sink deliberately does not carry: scope tests, a reporting threshold, a fixed reference-date
   snapshot, and the ECB credit/financial *dataset* attribute mapping. Putting return-specific
   business rules into the generic sink would violate its single responsibility.
2. **Scope is narrow and counter-intuitive.** Under the current ECB scope AnaCredit covers credit
   to **legal entities only** — instruments whose debtors are *all natural persons* (households,
   sole consumers) are **out of scope**. So a consumer overdraft on `CURRENT_PERSONAL` is *not*
   reported, but a `CURRENT_BUSINESS` overdraft is. This exclusion is a pure, must-test rule.
3. **A per-debtor materiality threshold gates everything.** An instrument is reportable only if the
   debtor's **total commitment** at the institution is **≥ €25 000** at the reporting reference
   date. The threshold is evaluated *per debtor across all their instruments*, not per instrument —
   so the decision needs the debtor's whole book, not one row.
4. **Reference-date snapshot semantics.** The credit dataset is reported monthly as the state on the
   *last day of the month* (the reference date). Outstanding nominal, undrawn off-balance-sheet
   commitment, arrears and default status are all "as-of" values. This is a deterministic projection
   of exposure state onto a date — exactly the kind of pure function that must be unit-testable
   offline, independent of any warehouse.

We build the **overdraft credit dataset** first (the G7 plan item) because overdrafts are the credit
exposure the multi-currency account introduces directly; loans/mortgages (ADR-0028) reuse the same
machinery and follow.

## Decision

We will add a dedicated bounded context **`openbank-anacredit-service`** (hexagonal per ADR-0002,
port 8130), a **derive-only regulatory-reporting** service that builds the AnaCredit credit dataset
for a monthly reference date from credit-exposure state, starting with overdraft instruments.

**A) Pure domain owns the regulatory logic.** Framework-free (`domain` has zero framework imports):
- `CreditExposure` — one credit instrument as the feed sees it: `instrumentId`, `debtorId`,
  `debtorType ∈ {LEGAL_ENTITY, NATURAL_PERSON}`, `instrumentType` (v1 `OVERDRAFT`), native
  `currency` + `committedAmount` (the arranged limit / commitment) + `drawnAmount` (outstanding
  nominal), `arrearsAmount`, `defaulted`, and `committedAmountEur` (the EUR-equivalent commitment
  used for the threshold; FX sourcing is the caller's responsibility via `openbank-fx-service`).
- `AnaCreditEligibilityPolicy.assess(exposure, debtorTotalCommitmentEur)` → `Reportable` |
  `Excluded(reason)` with crisp reason codes: `HOUSEHOLD_OUT_OF_SCOPE` (all-natural-person debtor),
  `BELOW_THRESHOLD` (debtor total commitment < €25 000), `NO_EXPOSURE` (no commitment and no draw).
- `AnaCreditMapper.toRecord(exposure, referenceDate)` → the credit/financial dataset row:
  `outstandingNominalAmount = drawnAmount`,
  `offBalanceSheetAmount = max(committedAmount − drawnAmount, 0)` (undrawn commitment),
  `arrearsAmount`, `defaultStatus`, `instrumentType`, `currency`, `referenceDate`.
- `AnaCreditReturnBuilder.build(exposures, referenceDate)` — groups by debtor, computes each
  debtor's total EUR commitment, applies the eligibility policy per instrument, maps the reportable
  ones, and returns an `AnaCreditReturn(referenceDate, records, exclusions)` carrying both the
  reportable rows and an audit trail of *why* each excluded instrument was dropped.

**B) Thin hexagonal shell, derive-only, no money movement.** The service holds exposure state via an
out-port (`CreditExposureRepository`); v1 backs it with an in-memory store (the established
`openbank-product-catalog` pattern) and a REST in-port to upsert exposures and to render a return.
It **emits no events, posts no money, and opens no ledger entry** — it is a read/derive projection,
so it stays **off the money-path gate** (no threat model / 2-approval requirement under ADR-0030).

**C) Render, do not transmit (v1).** The service produces the dataset; it does **not** implement the
ČNB submission transport (the SDMX / ČNB statistical-reporting channel), counterparty *reference*
dataset golden-sourcing, or the quarterly accounting dataset. Those are explicit non-goals for v1.

## Alternatives considered

- **A view/projection inside `openbank-analytics-sink`** — reuse the ClickHouse sink and express
  AnaCredit as a materialised view. Rejected: pushes return-specific scope tests, the €25k
  per-debtor threshold and dataset mapping into a service whose charter (ADR-0022) is a *generic*
  schema-governed sink; couples a regulatory return's release cadence to the warehouse; and the
  logic still has to live somewhere unit-testable offline, which a SQL view is not.
- **A library in `openbank-libs` consumed by each credit service** — every credit service builds its
  own slice of the return. Rejected: AnaCredit's threshold is *cross-instrument per debtor*, so no
  single source service has the whole picture; you would still need an aggregator. A dedicated
  context is that aggregator.
- **Defer until lending (ADR-0028) ships loans** — wait and build loans + overdrafts together.
  Rejected: overdrafts are *already* bookable on the multi-currency account, so the obligation is
  already live; the machinery built here is exactly what loans plug into later.

## Consequences

**Positive**
- The granular reporting obligation is met by a single owner with pure, testable regulatory logic.
- Loans/mortgages/credit-card credit (ADR-0028) extend by adding `instrumentType`s and feeding the
  same `AnaCreditReturnBuilder` — no new return engine.
- Stays off the money-path gate: derive-only, no posting, fast to ship and to reason about.

**Negative**
- Another deployable service to operate (port 8130). Mitigated by the thin, in-memory v1 shell.
- v1 renders but does not transmit — a manual/just-in-time export step remains until the ČNB
  submission channel is wired (tracked as the explicit C non-goal).

**Neutral**
- Exposure state is fed in (REST upsert) in v1 rather than consumed from `balance.overdraft.*`
  events; event ingestion + persistence is a mechanical follow-up that does not change the domain.

## Compliance impact

- PCI DSS: not applicable (no card PAN data; debtor/exposure reference data only).
- DORA:    in scope as a reporting system — covered by the platform's existing ICT controls.
- GDPR:    legal-entity scope means natural-person debtors are *excluded* from the feed by design;
           any counterparty reference data inherits the analytics layer's erasure/WORM controls.
- PSD2:    not applicable.
- CNB:     **AnaCredit** under Reg. (EU) 2016/867 (ECB/2016/13), collected by ČNB; zákon č. 6/1993
           Sb. o ČNB. This service is the granular credit-dataset producer; transmission channel is
           a v1 non-goal.

## References

- Regulation (EU) 2016/867 of the ECB of 18 May 2016 on the collection of granular credit and
  credit-risk data (ECB/2016/13) — "AnaCredit Regulation".
- ECB AnaCredit Reporting Manual, Parts I–III (instrument, financial, counterparty datasets;
  €25 000 reporting threshold; legal-entity scope; reference-date semantics).
- ADR-0022 / ADR-0023 — analytics sink and its regulatory hardening (why this is *not* in the sink).
- ADR-0024 / ADR-0028 — multi-currency account (overdraft credit) and the lending bounded context.
- ADR-0030 — money-path gate (why a derive-only reporting feed sits outside it).
