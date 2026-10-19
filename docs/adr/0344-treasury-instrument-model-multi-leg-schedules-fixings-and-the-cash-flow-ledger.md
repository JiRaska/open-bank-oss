---
date: 2026-10-19
decision-status: proposed
delivery-status: planned
authors: [Jiri Raska]
supersedes: []
superseded-by: []
delivery-repos: []
tags: [architecture, ledger, payments, accounting-close]
summary: "Treasury deals become multi-leg instruments over effective-dated reference data; a stored, versioned, overridable cash-flow ledger drives fixings, payment orders and posting rules, proven field by field against golden fixtures."
---

# ADR-0344 — Treasury instrument model: multi-leg schedules, fixings, an overridable cash-flow ledger and the deal-to-payment-to-posting chain

## Context

ADR-0313 brought treasury and balance-sheet risk in scope, ADR-0314 fixed the risk engine's
snapshot and cash-flow data model, and ADR-0315 decided the treasury service's domain: one
`Deal` aggregate per product, a four-eyes lifecycle, GL posting through the ledger API, nostro
reconciliation and minimum reserves. All three are `partial` today. This ADR is needed because
the shape ADR-0315 D2 chose for a deal cannot carry the products it lists, and because the
functional baseline of a bank treasury system — what treasury, back office and accounting need
before they can run the bank's own book on a platform — contains capabilities none of the three
ADRs decide. Measured on `origin/main` `bbfc132d7d` (2026-10-19):

**What the treasury service is today.** `Deal` is a single tuple
(`product, counterpartyId, currency, principal, rate, tradeDate, valueDate, maturityDate`)
plus optional FX terms (`openbank-treasury-service/.../domain/model/Deal.kt:143`). Five of the
twelve products ADR-0315 D2 lists exist (`MM_PLACEMENT`, `MM_BORROWING`, `CNB_DEPOSIT_FACILITY`,
`CNB_LOMBARD`, `FX_SPOT`); the seven with a schedule, a second leg or a security behind them
(`REPO`, `FX_FORWARD`, `FX_SWAP`, `IRS`, bonds) do not, and nothing in the aggregate could hold
them: there is no leg, no schedule, no index, no fixing, no amortisation. Interest is one
number, `act360Interest(principal, rate, valueDate, maturityDate)`, computed by a private
`DayCount` object with a weekend-only calendar that ignores public holidays although the kernel
already ships `DayCount` and `BusinessCalendar` (#11670). Postings know three events —
`SETTLED`, `ACCRUED`, `MATURED` — plus a whole-deal reversal (`Postings.kt:16`). The
counterparty master is `id, name, kind ∈ {BANK, CENTRAL_BANK}, limits` with no LEI, no
settlement instructions and no netting agreement. There is no portfolio or book, no securities
master, and no payment order: `git grep -E 'pain\.001|PaymentOrder'` over the treasury and risk
sources returns nothing, so a deal's maturity moves nothing in any rail.

**What the risk engine is today.** Curves bootstrap from money-market quotes only
(`CurveBootstrap.kt:61`), interpolate linearly, and price nothing: `grep -E 'presentValue|npv'`
hits cash-flow aggregation and IRRBB only, there is no per-deal valuation, no sensitivity, no
VaR. Cash flows are `(date, currency, PRINCIPAL | INTEREST, amount)` with no fee, no fixing
state and no provenance. ADR-0314 D6 says cash flows are "derived, never stored as truth" —
correct for a customer loan the risk engine only observes, and wrong for the bank's own deal
whose next coupon has been fixed and will be paid.

**The functional baseline.** A bank treasury system is expected to provide at least:

1. *Static data is most of the work and it is a dependency graph*: parties and party groups,
   countries, accounts, settlement places, standard settlement instructions, netting
   agreements, product and loan types, bond issues (ISIN master), holiday calendars per
   currency, period lengths, portfolios and portfolio groups, time-bucket sets, limit settings,
   derivative conversion factors, collateral haircuts, ČNB code lists, DISIFE23 codes, posting
   configuration and balance-sheet sides. Entering a curve depends on calendars and tenor
   grids; entering a deal depends on currencies, accounts, instruments and portfolios.
2. *Deal capture for 8–10 instrument types, each with its own conventions*: cash, deposit,
   treasury bill, bond, bill of exchange, loan, FX spot/forward, FX swap, FRA, IRS, repo. An IRS
   ticket carries per leg: notional and its amortisation start and frequency, accrual start
   and frequency, reference rate with refixing frequency and fixing lead in business days,
   fixed rate or spread, accrual convention, rate until first fixing, floor and cap with and
   without spread, and a payment calendar; and per deal: counterparty, trade/effective/maturity
   dates, jurisdiction, holiday calendar, business-day convention, whether dates roll from the
   start or the end (stub placement), notional exchange, single-currency flag, deal kind
   (actual / planned / fictitious), EMIR reporting parameters, linked deals, collateral, fees,
   ČNB codes, LCR/NSFR parameters.
3. *Every cash flow must be editable at instrument level*: conventions differ per instrument
   (for some the payment rolls off a weekend but the accrual does not; for others both roll),
   rounding rules differ, and the system will sometimes get it wrong, so an operator must be
   able to correct a single flow — with a trail.
4. *Payments are generated from deals, and most amounts are known late*: tens of deals already
   generate weekly inflows and outflows; a floating payment's amount is known only at the
   fixing, about two business days before value date, and the payment order must then be
   produced with the computed amount and signed.
5. *Accounting is per event, per product*: trade date, settlement date, every day's accrual,
   every fixing, maturity — and a back-dated correction must put the deal "back on the rails",
   which means every downstream posting is re-derived, not patched.
6. *Market data arrives from a market-data feed, curves are built from it with conventions*,
   and the system flags suspicious and missing market factors before they are used.
7. *Reports at the end*: valuation, sensitivity, interest-rate gap in buckets, liquidity,
   FX position, VaR, limits — all runnable over a portfolio node.
8. *Operations*: access rights per role and workflow (dealer / back office), activity log,
   completeness logs that show every step ran, archival of data for years, a test environment,
   and a field-by-field reconciliation against an independent reference on test data before
   anyone trusts a number.

Why now: the next treasury product PR would otherwise either fork a second schedule engine
per product or stall, because ADR-0315's deal shape cannot describe a leg, a fixing or a
schedule. This ADR amends ADR-0315 D2 and D5 and ADR-0314 D6 for the bank's own deals;
everything else in those ADRs stands.

## Decision

We will model a treasury deal as a **typed instrument with one or more legs**, generate each
leg's schedule from **effective-dated reference data** through one shared schedule engine, and
keep the result as a **stored, versioned, overridable cash-flow ledger** that is the single
fact the fixing workflow, payment orders, posting rules, nostro matching and the risk engine
all consume. Concretely:

1. **One schedule engine, in a framework-free library.** `openbank-libs-finance` (new module
   under the ADR-0317 split) owns schedule generation, accrual, stub placement, rounding and
   pricing math; it depends on the kernel's `DayCount`, `BusinessCalendar`,
   `BusinessDayConvention` and the ADR-0318 rounding registry and on nothing else. The treasury
   service's private `DayCount` object and weekend calendar are deleted. No service may carry
   its own day-count, calendar or roll logic again; a checker enforces it (Consequences).

2. **Instrument = product type + legs; leg = schedule spec.** A `Leg` declares: pay/receive,
   currency, notional and an amortisation schedule (bullet, linear, annuity, custom), accrual
   start and frequency, payment frequency, stub placement (`FROM_START` / `FROM_END`), payment
   calendar set (one or more calendars, e.g. CERTIS + TARGET2), business-day convention, the
   *two* roll flags finance named — `adjustPaymentDate` and `adjustAccrualPeriod` — day count,
   rounding policy, and a rate: `Fixed(rate)` or `Floating(index, spread, fixingLagBusinessDays,
   fixingCalendar, resetFrequency, cap, floor, rateUntilFirstFixing)`. Product types close the
   set finance listed: `CASH`, `MM_DEPOSIT`/`MM_LOAN` (the existing placement/borrowing),
   `CNB_DEPOSIT_FACILITY`, `CNB_LOMBARD`, `TREASURY_BILL`, `BOND`, `BILL_OF_EXCHANGE`,
   `LOAN`, `FX_SPOT`, `FX_FORWARD`, `FX_SWAP`, `FRA`, `IRS`, `REPO`/`REVERSE_REPO`. A product
   is a *leg template* plus a posting-rule set, so adding one is data plus tests, not a new
   aggregate. Deal kind is `ACTUAL`, `PLANNED` or `HYPOTHETICAL`: only `ACTUAL` posts and pays;
   `PLANNED` enters the liquidity forecast; `HYPOTHETICAL` is priced and discarded.

3. **Reference data is a first-class, effective-dated, four-eyes context inside the treasury
   service.** Calendars (kernel-provided sets plus bank-maintained exceptions), indices with
   their fixing conventions, counterparties with LEI, settlement instructions and netting
   agreements, a securities master (ISIN, issuer, coupon leg template, issue/maturity,
   denomination), portfolios and books as a tree with an IFRS 9 category per book, time-bucket
   sets, and the regulatory classification lists (ČNB codes, DISIFE23, EMIR fields). Every row
   has `valid_from`/`valid_to`, an author and an approver, and a `verified` provenance flag in
   the sense of #11107, so an unverified code list can be used but never silently trusted.
   Deal capture validates against this graph: a deal cannot reference a calendar, index, SSI or
   book that is not effective on its trade date.

4. **The cash-flow ledger.** Submitting a deal materialises its schedule into `deal_cash_flows`:
   one row per leg per period with `kind ∈ {PRINCIPAL, INTEREST, FEE, NOTIONAL_EXCHANGE,
   PREMIUM}`, accrual start/end, payment date, projected amount, and a state machine
   `PROJECTED → FIXED → DUE → SETTLED → POSTED` (plus `CANCELLED`). Rows are versioned per
   deal (`schedule_version`), generated from a hash of the leg specs, the reference data
   effective at generation and the fixing set used. A human may **override** one flow (date or
   amount) with a reason; the override is a four-eyes action, keeps the original beside the
   override, marks the row `manual`, and survives regeneration unless the leg spec that
   produced it changed — then the conflict is surfaced, never resolved silently. This amends
   ADR-0314 D6 for own deals: the risk engine keeps *deriving* projections for customer
   contracts, and *consumes* treasury's stored flows for `MONEY_MARKET_DEAL`, `BOND` and
   `DERIVATIVE_LEG`, because a fixed flow is a fact, not a projection.

5. **Fixing is a workflow with a market-data quality gate.** The contractual reset and index
   conventions determine the fixing date: an ordinary in-advance coupon fixes at accrual start
   minus the leg's fixing lag; an explicitly in-arrears coupon fixes at accrual end minus that
   lag, counted in business days on the fixing calendar. Payment date and the rail's cut-off
   are separate from this fixing date. A floating flow becomes `FIXED` on its fixing date from an
   observed rate: automatically from the market-data port (ČNB and the FX service today, a
   market-data feed adapter later) with the source and value hashed onto the row, or manually with
   four eyes. Before any fixing, the quality gate runs two checks — *suspicious* (stale beyond its tenor, jump beyond a per-index threshold) and
   *missing* (any deal needing an index on that date for which no observation exists). A
   failed check blocks the fixing and opens a case; it does not fix from the last value.

6. **Deal-to-payment chain.** A `FIXED` outgoing flow whose payment date is within the rail's
   cut-off window produces a payment instruction through the existing rails — CERTIS via
   domestic-payment for CZK, swift-service's bank-to-bank message for cross-border, SEPA for EUR
   — addressed from the counterparty's standard settlement instruction, netted per netting
   agreement (same counterparty, currency, date), with idempotency key
   `treasury:<dealId>:<flowId>:payment`. Release is a human action under the same role split as
   booking; an agent principal can never release. Incoming `FIXED` flows are the expected
   items the nostro reconciliation (ADR-0315 D7) matches statement lines against; a match
   moves the flow to `SETTLED`. The treasury service never calls a scheme directly.

7. **Accounting is a posting-rule matrix, replayable, and corrections re-derive.** Posting
   rules are data: `(product, event, IFRS 9 category) → journal template`, versioned, with the
   event set `TRADE`, `SETTLEMENT`, `ACCRUAL`, `FIXING`, `PAYMENT`, `AMORTISATION`,
   `REVALUATION`, `MATURITY`, `EARLY_TERMINATION`, `CORRECTION`. A template line names an
   **accounting class and role**, never a GL account: under ADR-0341 the ledger owns the
   accounting-class → GL map, so the chart can change without a treasury release and the same
   matrix serves every legal entity's ledger (ADR-0337). Trade-date versus settlement-date
   recognition is declared per product in that matrix, not in code. A back-dated
   correction creates a new deal version, regenerates the cash-flow ledger, diffs the journals
   the previous version posted against the journals the new version implies, and posts the
   **delta journals** into the open accounting day — never an edit of a posted journal and never
   a posting into a day the ledger has closed. The treasury service is a **day-close
   participant** under ADR-0342: each accounting day it reports one record — `RAN` with the
   fixings, accruals, payments, revaluations and corrections it processed, `SKIPPED` with a
   reason, or `REFUSED` when the ledger refused a posting into a closed day — and the accounting
   day does not tie out until that record exists or an operator waives it.

8. **Valuation and sensitivity live in the risk engine; the treasury service supplies facts.**
   The risk engine adds per-deal and per-portfolio present value from its curve sets (per leg,
   discounting and forward projection on the same curve library), PV01 and DV01 by bucket, FX
   delta, and historical-simulation VaR over the own book — all runnable over a portfolio node
   and over `HYPOTHETICAL` deals for pre-trade pricing. The curve library gains bootstrapping
   from deposits, FRAs and swaps (not only money-market quotes) and a documented interpolation
   choice, versioned in `ModelVersions`. Nothing in the treasury service prices; nothing in the
   risk engine books, pays or fixes.

9. **Golden-fixture parity is the acceptance evidence.** A fixture format holds deals,
   reference data, fixings and expected outputs, computed independently of this code — by
   hand from the contract terms, from published worked examples, or exported from any existing
   system's **test** environment — never from production data. Golden tests
   generate the cash-flow ledger, accruals and present values for every fixture and compare
   field by field within the declared rounding; a discrepancy is a named finding per deal, not a
   failed assertion in a log. Every product type must have at least one fixture before its
   PR merges, and the fixture set is replayed on every treasury PR by the
   `treasury-parity-fixtures` gate. A business analyst reviews the discrepancies; the platform
   does not decide which side is right.

10. **Roles and the activity trail.** Dealer, approver, back office, accounting and read-only
    are roles in `rules.yaml`'s single source (ADR-0229) scoped to books; every state change,
    override, fixing and release writes the actor to the deal history that already exists and
    to the audit chain. The AI rule of ADR-0313 D12 is unchanged and extended to the new
    actions: draft, propose a fixing, propose an override — never fix, override, release.

Delivery phases, each an issue under #10618: (0) `openbank-libs-finance`, reference-data
context, the cash-flow ledger for the five existing products, parity fixtures for them;
(1) `IRS` and `FRA` with fixings, the quality gate and the payment chain; (2) `BOND`,
`TREASURY_BILL`, `REPO` with the securities master, the posting-rule matrix and corrections;
(3) `FX_FORWARD`, `FX_SWAP`, `BILL_OF_EXCHANGE`, `LOAN`, valuation, sensitivity and VaR in the
risk engine; (4) EMIR field capture and the regulatory exports, the full parity set, and the
day-end completion records.

## Alternatives considered

- **Keep ADR-0315 D2 — one aggregate per product — and add the seven missing products one at
  a time.** Rejected: each product would re-implement schedule, accrual and calendar logic (the
  first one already diverged from the kernel, #11670), and there would be no shared cash-flow
  fact for payments and accounting to consume; the posting code would grow a `when` per product
  per event. The typed aggregate ADR-0315 wanted is kept at the level of the leg template.
- **Keep an external treasury system as the system of record and integrate by import/export.**
  Rejected as the end state for the reference platform: the deal facts would live outside the
  ledger's reach and every correction would cross a file boundary. An external system remains a
  valid **parity oracle** (D9) during any migration.
- **Store the cash-flow ledger in the risk engine, which already derives flows.** Rejected:
  ADR-0313 D1 keeps the risk engine read-only and off the money path, and a fixed flow that
  produces a payment order is on the money path by definition. The boundary stays
  facts-in-treasury, projections-in-risk.
- **Derive flows on demand, never store them (ADR-0314 D6 as written), and let payments and
  postings recompute.** Rejected: a recomputation can change a number that has already been
  paid or posted when reference data or a model version changes; finance's requirement that a
  single flow be correctable with a trail cannot be met on a value that has no row.
- **Adopt a commercial front-to-back treasury system.** Rejected for the reference platform (the
  ADR-0313 argument); a real deployment can still put one behind the same ports, and the parity
  harness is exactly what such a migration would need.

## Consequences

**Positive**
- Every product finance listed has a place in the model, and adding one is a leg template, a
  posting-rule set and a fixture.
- Fixings, payments, postings, nostro matching and risk measures all read one fact, so a
  correction propagates by re-derivation instead of by five hand edits.
- Parity becomes a replayed test, not a one-off reconciliation.

**Negative**
- A second schema generation in a money-path service (deal versions, legs, cash-flow ledger,
  reference data), each change with two approvals and the threat model updated.
- Reference-data stewardship becomes a standing business duty with its own four-eyes queue.
- Fixtures computed by the same people who wrote the code prove self-consistency, not
  correctness; at least one independent source per product type is required before that product
  is called delivered.
- Historical-simulation VaR and per-deal valuation over the whole book add compute against the
  capacity ceiling ADR-0313 already flags; phase 3 is sized by measurement.

**Neutral**
- ADR-0315's lifecycle, four-eyes rule, nostro reconciliation and minimum reserves are
  unchanged; D2 (deal shape) and D5 (event set) are amended by D2, D4 and D7 above.
- ADR-0314 D6 is amended only for own deals; customer-contract flows stay derived.
- Gates: `treasury-parity-fixtures` (new, enforced on treasury PRs once phase 0 lands) and
  `no-local-daycount-or-calendar` (new checker under `.github/scripts/`, enforced fleet-wide:
  any `object DayCount`, `isWeekend(` or `nextBusinessDay(` defined outside
  `openbank-libs-domain` fails). Both are declared in `.github/gates/gates.yaml` in the phase 0
  PR; `adr-delivery-evidence` then forbids this ADR from staying `planned`.

## Delivery check

Run from the repository root on `origin/main`; each line's expected result is what proves the
corresponding decision was built, independent of the `delivery-status` field.

```bash
# D1: no service carries its own day-count or calendar logic
git grep -lE 'object DayCount|fun isWeekend\(|fun nextBusinessDay\(' -- '*/src/main' | grep -v openbank-libs-domain   # prints nothing
# D2: the product set is complete
grep -cE 'IRS|FRA|FX_FORWARD|FX_SWAP|REPO|BOND|TREASURY_BILL|BILL_OF_EXCHANGE' openbank-treasury-service/src/main/kotlin/com/openbank/treasury/domain/model/Deal.kt   # >= 8
# D4: the cash-flow ledger exists and is overridable
grep -E '^  /api/v1/treasury/deals/\{id\}/cash-flows' openbank-treasury-service/src/main/resources/openapi.yaml   # path present, with an override operation
# D6: a payment instruction leaves the deal
git grep -lE 'treasury:.*:payment' -- openbank-treasury-service/src/main   # at least one file
# D9: the parity gate runs on PRs
yq '.gates[] | select(.id=="treasury-parity-fixtures") | .mode' .github/gates/gates.yaml   # enforced
ls openbank-treasury-service/src/test/resources/parity/*.json | wc -l   # >= number of product types
```

## Functional baseline catalogue

The decision above names mechanisms; this catalogue names the **fields, views and code lists**
a treasury front office, back office and accounting function expect, so that no slice can call
itself complete while a column is missing. Every item is a generic requirement of a bank
treasury system; none is tied to any product. A slice that implements a product or a view must
check its PR against the matching list.

### Deal ticket — header (every product)

Legal entity (the booking entity), counterparty, counterparty's own deal reference,
communication channel (dealing platform, voice, electronic, internal), portfolio/book,
instrument, deal kind (`ACTUAL` / `PLANNED` / `HYPOTHETICAL`), trade date **and time**,
effective (start) date, maturity date, validity from/to (for framework or revolving deals),
jurisdiction / governing law, holiday calendar set, business-day convention, fixed-convention
flag (convention frozen at trade, not re-derived from reference data), schedule generation
direction (from start / from end), notional exchange flag, single-currency flag, free-text note,
and the regulatory block (trade-reporting fields for derivatives, central-bank codes,
liquidity-ratio categories).

### Deal ticket — per leg (interest-bearing products)

Notional and its currency; amortisation start date and frequency; accrual start date and
accrual frequency; payment frequency; reference rate (index) with refixing frequency and
fixing lead in business days; fixed rate or spread; accrual convention (day count); total
rate until the first fixing; floor and cap, each on the index and on index plus spread;
payment calendar; a "generate schedule" action that previews the cash-flow ledger before
submission.

### Deal ticket — product-specific

| Product | Extra fields |
|---|---|
| Deposit / loan / cash | principal account, interest account, early-withdrawal terms |
| Treasury bill / bond / bill of exchange | security (ISIN or internal id), nominal, clean price or yield, accrued interest, settlement place, custody account |
| FX spot / forward / swap | buy and sell currency and amounts, rate, forward points, near and far leg dates and rates |
| FRA | contract period (e.g. 3×6), reference index, fixing date, settlement convention |
| Interest-rate swap | two legs as above, netting of same-day flows, notional exchange |
| Repo / reverse repo | collateral security, nominal, dirty price, haircut, substitution rights, repo rate, margin calls |

### Deal ticket — tabs

State and timeline (state, timestamp, user, note per transition); cash and securities
accounts used for settlement; documents (confirmations, term sheets, signed scans); payment
orders generated from the deal; collateral; linked deals (hedge relationship, roll-over,
netting set); credit-exposure limit check (counterparty, product, country, result,
override); fees; central-bank codes; liquidity-ratio parameters. Actions: save, save and
close, submit, approve, send confirmation, cancel, reverse, and open change history.

### Deal overview

Columns: deal id, instrument, state, original deal (for amendments and roll-overs),
portfolio/book, deal kind, counterparty, currency, notional, rate, trade/value/maturity dates,
last change. Filters: by instrument, by state, by portfolio, hide matured, hide authorised,
free text, saved personal filter sets. Actions: export all, export selection, export in the
regulatory-reporting layout, change history of a deal, position close of day (and a special
close with chosen parameters). Record count visible on every list.

### Event calendar

For a period preset (yesterday, today, tomorrow, week, month, custom range): every upcoming
refixing, interest payment, principal payment, maturity, option exercise and margin call,
with date, event type, description, deal link and the responsible role; actionable (open the
deal, open the payment).

### Cash-flow analysis view

Parameters: calculation date, portfolio/book node, reference currency, convert-to-reference
toggle, result expression (gross / net / cumulative), payment scenario (contractual /
behavioural / stress), monthly split toggle, future-business scenario, bank account filter.
Output: dated flows by currency with drill-down to deal and leg.

### Reports and analyses (each runnable over a portfolio node, each with provenance)

Payment orders; cash flow; liquidity analysis (ladder, survival horizon, regulatory
ratios); interest-rate risk (repricing gap by bucket set, duration, NII and EVE
sensitivity); limits and utilisation; FX position; sensitivity (PV01, key-rate DV01,
FX delta); value at risk; valuation (clean, dirty, accrued, by book and IFRS 9 category);
accounting (journals by deal, by event, by period; unposted events; corrections).

### Market data

Import from a feed or file; market-factor browser (rates, FX, curves) with history;
portfolio of factors a curve is built from; export; **suspicious factors** (stale, jump
beyond threshold); **missing factors** defined in configuration but absent for a date;
internal rate sheets published by the bank.

### Reference data and configuration (code lists)

Parties (counterparties, issuers, central bank) and party groups; party types; countries;
document types; accounts (cash, securities, nostro); settlement places; standard settlement
instructions; netting agreements; product types; loan types; central-bank code lists;
communication channels; market factors; securities master (issues); own capital items;
holiday calendars; period lengths (tenors); scenarios; behavioural scenarios; calculation
settings (conventions, rounding); limit settings; conversion factors for derivative
exposure; haircuts for collateral valuation; regulatory classification codes; portfolios
and portfolio groups; time-bucket groups; accounting settings (posting rules, GL mapping);
balance-sheet sides; dealing-view filters; user activity log; per-user settings; application
version history.

Each code list is effective-dated, versioned, four-eyes approved and carries the `verified`
flag of D3; each has import and export; each exposes the dependency query of D3 so that a
change shows what it would affect before it is approved.

## Compliance impact

- PCI DSS: not applicable — no cardholder data is processed.
- DORA: the treasury service is already registered as a money-path service supporting a
  critical function; this ADR adds no new service. The parity harness and completion records
  are internal controls, not claimed as DORA evidence.
- GDPR: not applicable — counterparties are banks, the central bank and issuers; no natural
  persons' data enters the reference data or the cash-flow ledger.
- PSD2: not applicable — payment instructions produced by D6 are the bank's own bank-to-bank
  transfers, with no payment-service-user surface.
- CNB: ČNB code lists and DISIFE23 classifications are captured per deal as reference data
  with a `verified` flag (#11107); minimum-reserve mechanics are unchanged from ADR-0315. EMIR
  trade-reporting fields are captured at deal capture for derivatives; the reporting submission
  itself stays out of scope here.

## References

- ADR-0313, ADR-0314, ADR-0315 (amended in part), ADR-0317, ADR-0318, ADR-0229, ADR-0332,
  ADR-0337 (per-entity ledgers and custody holdings), ADR-0341 (accounting class → GL map),
  ADR-0342 (day-close participants),
  ADR-0046, ADR-0025, ADR-0104, ADR-0031
- Issues #10618 (programme), #11670 (day-count divergences), #11107 (unverified regulatory
  codes), #12115 (funding check), #13167 (day-end participation)
- [QuantLib `FloatingRateCoupon::fixingDate`](https://github.com/lballabio/QuantLib/blob/master/ql/cashflows/floatingratecoupon.cpp) — accrual-start/end fixing anchors and the index fixing calendar.
