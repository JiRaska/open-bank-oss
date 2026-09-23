---
date: 2026-09-23
decision-status: accepted
delivery-status: planned
authors: [Jiri Raska]
supersedes: ["0185"]
superseded-by: []
delivery-repos: []
tags: [architecture, regulatory-reporting, analytics, ai-agents]
summary: "Bring treasury, ALM, liquidity, market and credit-capital risk in scope: a read-only, event-driven balance-sheet engine with forecasting and revaluation, a treasury book for own dealing, and AI agents around, never inside, the numbers."
---

# ADR-0313 — Balance-sheet risk, capital and treasury platform with forecasting, revaluation and AI agents

## Context

ADR-0185 declared treasury and liquidity management out of scope: the ledger is a customer-position
book, and a real deployment was expected to bring ALM, LCR/NSFR and treasury from an external
vendor. Benchmarking against a representative Czech vendor suite for banks — one product for market
risk / ALM / liquidity, one for credit-risk capital under the CRR framework — shows what that
external function contains and how much of it the platform already holds the inputs for.

What the platform has today (measured on `origin/main`, 2026-09-23):

- **Revaluation** only of customer FX positions against the ČNB fixing (ADR-0025, ADR-0046).
- **IFRS 9 staging** inside `openbank-lending-service` (ADR-0028), used for provisioning only.
- **COREP rendering** in `openbank-finrep-service` (templates C 01.00, C 02.00, C 05.01) — with
  nothing upstream that *computes* risk-weighted exposure amounts; the cells are placeholders for a
  calculation that does not exist.
- Contract-level data for every balance-sheet position — accounts, loans, deposits, interest
  terms, FX pockets — spread over the ledger, lending, interest, account and fx services, and
  published as events.

What is missing, grouped by function:

| Function | Missing capability |
|---|---|
| Credit capital (Pillar 1) | Standardised-approach RWA engine: exposure classes, risk weights, CCF, credit-risk mitigation; regulatory expected loss; IRB model testing |
| Economic capital (Pillar 2) | Credit and market economic capital at configurable confidence, stress scenarios, ICAAP inputs |
| Concentration | Large-exposure and single-name / sector concentration measurement and limits |
| IRRBB / ALM | Repricing gap, duration, NII and EVE sensitivity, the supervisory rate-shock scenarios |
| Behavioural modelling | Loan prepayment, non-maturity deposit core/volatile split and repricing, credit-line drawdown |
| Valuation | Yield-curve library, discounting, caps/floors (optionality in client rates), credit-spread sensitivity |
| Market risk | Bank's own open FX position, VaR, sensitivity analysis and back-testing |
| Liquidity | LCR, NSFR, maturity ladder / survival horizon, intraday liquidity monitoring, contingency funding plan |
| Forecasting | Dynamic-balance-sheet projection of NII, EVE, liquidity and capital under scenarios |
| Limits | Declarative risk limits with early-warning and breach events |
| **Treasury front office** | Money-market dealing: interbank overnight and term deposits/loans, ČNB standing facilities (deposit / lending), repo and reverse repo with ČNB and counterparties, FX spot / forward / swap for the bank's own book |
| **Treasury balance sheet** | Nostro/vostro accounts and reconciliation, minimum reserve requirement at ČNB (holding, averaging), prefunding of RT1/TIPS/T2 positions once a real scheme is connected |
| **Investment portfolio** | Bond portfolio (government bonds, T-bills) with IFRS 9 business-model classification (amortised cost / FVOCI / FVTPL), accrual, amortisation of premium/discount, mark-to-market |
| **Hedging** | Interest-rate swaps and FX swaps as hedges of the banking book; hedge accounting and effectiveness testing |
| **Collateral** | Collateral and eligible-asset (HQLA / ČNB-eligible) inventory, haircuts, encumbrance |
| **Funding and pricing** | Funds transfer pricing (FTP) curve charging/crediting every product for liquidity and rate risk; funding plan; MREL tracking |
| **Counterparty** | Counterparty limits and exposure for interbank and derivative counterparties, CVA |
| **Profitability** | Product / segment profitability after FTP, cost of capital and expected loss (RAROC); budget vs actual |

Why now: the fleet has the data but cannot answer the three questions every bank's ALCO asks each
month — *are we liquid, is our margin at risk, do we have enough capital* — nor price a product on
its true cost of funds. The COREP templates already ship with nowhere for their numbers to come from.

## Decision

We will bring balance-sheet risk, capital and treasury **into scope**, including a dedicated
treasury service. This ADR supersedes ADR-0185.

1. **A read-only risk engine, off the money path.** A new bounded context
   (`openbank-risk-engine`, split into ALM/liquidity and capital modules; hexagonal, ADR-0002)
   consumes events and snapshots and never writes to the ledger. Risk models therefore never sit
   on a money path, and a model defect cannot move money.

2. **Bitemporal contract-level snapshot.** A daily (and on-demand) as-of snapshot of every
   balance-sheet position, each run stamped with the hash of its inputs, the model versions and the
   curve set. Any published number is reproducible and drillable down to the contract.

3. **One cash-flow engine under everything.** Each contract is expanded into contractual cash
   flows; a behavioural layer (prepayment, deposit decay, drawdown) adjusts them. Gap, duration,
   NII/EVE, LCR/NSFR outflows, valuation and forecast all consume the same flows — no second model
   of the balance sheet.

4. **Revaluation.** A curve library (ČNB, PRIBOR/CZEONIA, €STR; bootstrapping and interpolation),
   discounting, cap/floor pricing, and credit-spread shocks. Customer FX revaluation (ADR-0046)
   stays where it is; the engine adds rate and spread revaluation and the bank's own FX position.

5. **Forecasting as scenarios, never a single number.** Baseline, the supervisory rate shocks, and
   user-defined macro scenarios, projected over 12–36 months on a dynamic balance sheet whose new
   business comes from plan assumptions. Outputs: NII, EVE, LCR, NSFR, capital ratios.

6. **Capital.** Pillar 1 standardised approach first, feeding real values into the existing COREP
   templates in `openbank-finrep-service`; IRB only once there is history to estimate on. Pillar 2
   economic capital by simulation with configurable confidence.

7. **A treasury book for the bank's own dealing.** A separate bounded context
   (`openbank-treasury-service`, money-path) records the bank's own deals — interbank overnight and
   term deposits, ČNB facilities, repo, FX forwards/swaps, IRS hedges, bond purchases — posts them
   to the ledger in the bank's own accounts (distinct from customer positions, so ADR-0039's
   golden-source role is preserved), and manages nostro/vostro reconciliation, minimum reserves and
   collateral inventory. It carries the money-path obligations (two approvals, threat model).
   Real-market connectivity (dealing platforms, confirmations) stays external; the sandbox books
   against simulated counterparties, as payments do against the scheme simulator (ADR-0104).

8. **FTP and profitability.** The engine publishes an FTP curve; product pricing in
   `openbank-product-catalog` and profitability reporting consume it.

9. **Limits as code.** Risk limits are declared in version-controlled configuration, evaluated
   on every run, and emit early-warning and breach events that route to alerting and to the
   responsible human.

10. **Event-driven, not batch-only.** Because positions arrive as events, liquidity and rate
    sensitivity are recomputed intraday; the regulatory end-of-day run is one scheduled case of
    the same engine.

11. **AI around the numbers, never producing them.** Every regulatory or accounting figure comes
    from the deterministic engine. AI works under ADR-0031 (charters, policy-gated MCP, human in
    the loop, AI-attributed audit):
    - *Behavioural models (classical ML)* for prepayment, deposit decay and drawdown, on the ML
      decisioning platform (ADR-0142) with champion/challenger, shadow mode and drift monitoring.
    - *Scenario agent*: turns a natural-language shock into a scenario definition, runs it, and
      explains the deltas; it proposes, a human approves.
    - *ALCO agent*: drafts the ALCO pack and explains movements from attribution output, not from
      its own arithmetic.
    - *Model-validation agent*: back-testing (VaR exceptions, prepayment realised vs predicted)
      and a draft validation report.
    - *Data-quality agent*: flags anomalies in the snapshot (missing rates, impossible maturities)
      before they reach a report.
    - *Treasury assistant*: proposes overnight placement or funding from the liquidity forecast;
      it never books a deal — booking is a human action in the treasury service.
    - *Copilot in admin-ui*: answers "why did LCR drop" over published results.

Delivery phases: (0) snapshot, cash-flow engine, curves; (1) IRRBB, LCR/NSFR, maturity ladder;
(2) Pillar 1 standardised approach into COREP, limits; (3) treasury book — money market, nostro,
minimum reserves, bond portfolio; (4) forecasting, FTP, Pillar 2, VaR, behavioural ML;
(5) hedging and hedge accounting, agents, IRB. Each phase is its own follow-up issue.

## Alternatives considered

- **Keep ADR-0185 and integrate an external vendor.** This is what 0185 decided. Rejected for the
  reference platform because it leaves the COREP templates with no source, cannot price products on
  cost of funds, and hides the most regulation-heavy part of a bank from anyone evaluating the
  platform. A real deployment can still plug a vendor behind the same ports.
- **Extend the ledger into a treasury book.** Rejected, for the reason 0185 gave: it would mix the
  bank's own positions into the customer golden source. The treasury service posts to the ledger
  through its normal API instead, in the bank's own accounts.
- **Let an LLM compute or forecast the figures.** Rejected: regulatory numbers must be
  deterministic, reproducible and explainable to a supervisor. AI stays in the proposing and
  explaining roles.

## Consequences

**Positive**
- The three ALCO questions (liquidity, margin at risk, capital) become answerable, intraday.
- COREP and liquidity reporting get computed values with contract-level lineage.
- Product pricing reflects funding cost via FTP.

**Negative**
- A large, multi-quarter programme and at least one new money-path service with its approvals,
  threat model and coverage obligations.
- Behavioural and IRB models need history the sandbox lacks; early phases run on synthetic data
  from `openbank-simulation`, so model quality claims must be labelled as such.
- Model risk management (inventory, validation, change control) becomes a standing obligation.

**Neutral**
- Customer FX revaluation (ADR-0046) is unchanged.
- Real-market connectivity for treasury stays external.

## Compliance impact

- PCI DSS: not applicable — no cardholder data is processed.
- DORA: the risk engine and treasury service join the register of critical functions as any new
  service does; no specific requirement is claimed here.
- GDPR: not applicable to aggregates; the snapshot holds contract-level data under existing
  controls, with no new categories of personal data.
- PSD2: not applicable — no payment-initiation or account-information surface changes.
- CNB: supervisory liquidity, interest-rate-risk and capital reporting become computed rather than
  externally supplied; the exact reporting obligations are to be enumerated per phase.

## References

- ADR-0185 (superseded by this ADR), ADR-0002, ADR-0022, ADR-0025, ADR-0028, ADR-0031,
  ADR-0039, ADR-0046, ADR-0104, ADR-0142
