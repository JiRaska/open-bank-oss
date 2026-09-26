---
date: 2026-09-24
decision-status: accepted
delivery-status: planned
authors: [Jiri Raska]
supersedes: []
superseded-by: []
delivery-repos: []
tags: [architecture, analytics, database, regulatory-reporting]
summary: "The risk engine builds an as-of balance-sheet snapshot from events plus pulled reference data, expands each position into cash flows in one canonical model, and fills three source gaps: rate configs, FX fixings and loan rate terms."
---

# ADR-0314 — Risk engine: balance-sheet snapshot and cash-flow data model

## Context

ADR-0313 (D2, D3, D14) decided that a read-only risk engine computes ALM, liquidity, capital and
forecasts from a bitemporal contract-level snapshot and a single cash-flow engine, and that this
data model needs its own ADR before code. Measured on `origin/main`, 2026-09-24:

- **Ledger** holds a real chart of accounts (`gl_accounts`: ASSET/LIABILITY/EQUITY/INCOME/EXPENSE,
  hierarchical, per-currency; nostro 1001, scheme nostro 1110–1113, capital 6000–6060). Customer
  positions are sub-ledger balances on deposit-control legs (`subAccountId`), exposed by
  `GET /sub-ledger-balances`. `JournalPosted` / `JournalReversed` go to
  `openbank.ledger.journal.posted`.
- **Lending** stores a full installment schedule per loan (due date, principal, interest, balance)
  and IFRS 9 stage + ECL per reporting period, and publishes `loan.disbursed`, `loan.rescheduled`,
  `loan.stage_changed`, … on `openbank.lending.events`. A loan has a single `nominal_annual_rate`:
  **no fixed/floating flag, no reset dates, no index**.
- **Interest** models deposit/account rates as FIXED / VARIABLE / TIERED configs with effective
  dating and day count, but **no index reference or spread**, and **rate changes are not
  published as events**.
- **FX** stores spot fixings (ČNB, ECB) with validity dating. **No fixing event, no tenor, no
  curve.**
- **Analytics** (ADR-0022) keeps an append-only bronze log of the same domain events in ClickHouse,
  so it can only ever know what is published — which excludes rate configs and FX fixings.

So an event-only snapshot is impossible today, and a floating-rate position cannot be repriced
correctly because nothing says it floats.

## Decision

1. **Snapshot = events for positions, pulled reads for reference data.** The engine consumes
   position events (ledger, lending, account) into its own store, and pulls reference data —
   rate configs, FX fixings — through each owner's read API at snapshot time. Reference data is
   small and changes rarely; a pull stamped with the owner's `effective_from` is as reproducible
   as an event. New events for rate changes and fixings are added (D5) but the snapshot does not
   wait for them.

2. **Bitemporal store.** Every position row carries `valid_time` (business as-of) and
   `recorded_time` (when the engine learned it). A run is identified by
   `(as_of_date, cut_off_recorded_time)`, and its manifest stores the input hash, model versions,
   curve-set id and a `provenance` of `production` or `synthetic` (ADR-0313 D13). Rerunning the
   same manifest yields byte-identical results — tested in CI.

3. **Reconciliation to the ledger is a hard gate.** A snapshot is publishable only if the sum of
   its positions per GL account and currency equals the ledger trial balance at the same as-of
   within tolerance zero. A snapshot that does not tie out is stored, flagged and never rendered
   — the same rule as ADR-0097's data gaps. This is what makes every downstream number trustworthy.

4. **One canonical instrument model.** Every position maps to one of a closed set of instrument
   kinds — `AMORTISING_LOAN`, `BULLET`, `NON_MATURITY_DEPOSIT`, `TERM_DEPOSIT`, `CURRENT_ACCOUNT`,
   `FX_POSITION`, `CASH_NOSTRO`, `BOND`, `MONEY_MARKET_DEAL`, `DERIVATIVE_LEG`, `EQUITY_CAPITAL` —
   with a common core (id, GL account, currency, notional, value/maturity dates, rate terms,
   counterparty, IFRS 9 stage) and a kind-specific extension. Treasury instruments (ADR-0315)
   enter through the same model.

5. **Fill three source gaps in their owning services**, each its own PR:
   - lending: `rate_type` (FIXED / FLOATING), `index` (e.g. PRIBOR_3M), `spread`, `reset_frequency`,
     `next_reset_date`; existing loans migrate as FIXED — correct today, since nothing floats;
   - interest: optional `index` + `spread` on VARIABLE configs, and a `rate.changed` event;
   - fx: a `fx.fixing.published` event on ČNB/ECB ingest.

6. **Cash flows are derived, never stored as truth.** The cash-flow engine expands a snapshot into
   dated, currency-tagged flows (principal, interest, fees) per instrument, for contractual and
   for each behavioural scenario separately. Flows are cached per run id and discarded with it;
   the snapshot is the record. Floating legs project from the curve set of the run.

7. **Storage.** PostgreSQL (CNPG, as every service) for snapshot and run manifests; results
   aggregated per run to ClickHouse gold marts for admin-ui and finrep. No second copy of
   positions lives in ClickHouse.

8. **Execution.** End-of-day run as a Temporal workflow (snapshot → tie-out → cash flows →
   measures → publish), because each step must be resumable and the tie-out gate must block
   publication. Intraday runs (ADR-0313 D10) reuse the workflow on the event-maintained
   position store with the latest pulled reference data, labelled `intraday`, never regulatory.

## Alternatives considered

- **Build the snapshot from ClickHouse bronze.** Rejected: bronze lacks rate configs and fixings,
  and would put an analytics store on the path of regulatory numbers. It stays the consumer of
  results, not the source.
- **Read every owner's database directly.** Rejected: breaks service ownership (ADR-0002) and
  couples the engine to eleven schemas.
- **Pure event sourcing, no pulls.** Rejected for now: blocked on events that do not exist; D5
  adds them, and the pull can be retired per source once its event has history.
- **Store cash flows as the record.** Rejected: behavioural and curve assumptions change them;
  the contract is the fact, flows are a projection.

## Consequences

**Positive**
- Every figure ties back to the ledger by construction; an untied snapshot cannot publish.
- Floating-rate risk becomes measurable once D5 lands.

**Negative**
- Three schema changes in money-path lending and in interest/fx before phase 1 is meaningful.
- Snapshot storage grows with contracts × days; retention per run type must be set in phase 0.

**Neutral**
- The instrument model is the contract between the engine and every source, so changes to it are
  API changes under ADR-0048.

## Compliance impact

- PCI DSS: not applicable — no cardholder data.
- DORA: not applicable to this data-model decision.
- GDPR: the snapshot holds contract identifiers and counterparty references; party identity stays
  in party-service and is not copied.
- PSD2: not applicable.
- CNB: not applicable to the data model itself; reporting obligations belong to ADR-0313 phases.

## References

- ADR-0313, ADR-0002, ADR-0022, ADR-0025, ADR-0048, ADR-0097, ADR-0315
