---
date: 2026-07-23
decision-status: accepted
delivery-status: n-a
authors: [Jiri Raska]
supersedes: []
superseded-by: []
delivery-repos: []
tags: [architecture]
summary: "Bank treasury and liquidity management (funding, nostro/vostro, intraday liquidity, ALM) are deliberately out of scope; the ledger is the customer-position golden source, not a treasury book, and a real deployment adds treasury externally."
---

# ADR-0185 — Treasury and liquidity management out of scope

## Context

A production bank runs a treasury and liquidity-management function that the OpenBank reference
platform does not model:

- **Funding and cash management** — sourcing and placing funds, managing the bank's own cash
  position across correspondent (nostro/vostro) accounts.
- **Intraday liquidity** — ensuring enough settlement liquidity is prefunded at each scheme/CSM at
  each moment (RT1/TIPS prefunded positions for SCT Inst, TARGET2/T2 for large-value settlement),
  including liquidity buffers under BCBS 248 intraday-liquidity monitoring.
- **Asset-liability management (ALM)** — interest-rate and maturity-gap risk, the banking-book view
  the platform's per-customer ledger does not carry.
- **Regulatory liquidity ratios** — LCR / NSFR reporting under CRR.

The platform's ledger is the *golden source of customer positions* (ADR-0039) and settles payment
rails against an in-house scheme simulator (ADR-0104), which by construction has no real funding or
prefunding constraint. There is no bank treasury book, no nostro/vostro modelling, and no intraday
liquidity engine. Nothing in the codebase claims otherwise, but the absence has never been recorded
as a deliberate scope boundary — so an evaluator cannot tell "not built yet" from "intentionally
external". This ADR draws that line.

## Decision

We record that **treasury and liquidity management are out of scope for the reference platform**, as
a deliberate boundary rather than a gap:

1. **The ledger is a customer-position book, not a treasury book.** `openbank-ledger-service` is the
   golden source of what customers hold (ADR-0039); it is not the bank's own funding position and
   will not be extended into one. FX revaluation (ADR-0046) reprices customer positions against the
   ČNB fixing — it is not a treasury FX-risk model.

2. **Settlement is simulated, so there is no prefunding constraint to manage.** Because the payment
   rails settle against the scheme simulator (ADR-0104), the platform never holds or runs down a
   prefunded RT1/TIPS/T2 position. Intraday liquidity is therefore not modelled and does not need to
   be; when a real scheme is connected, prefunding management is part of that integration, external
   to these services.

3. **A real deployment must supply treasury as an external function.** ALM, LCR/NSFR reporting,
   nostro/vostro reconciliation and intraday-liquidity monitoring are the responsibility of a
   treasury system the operator brings; the platform exposes the customer-position and transaction
   data such a system would consume (through the analytics layer, ADR-0022) but does not implement
   the treasury logic itself.

## Alternatives considered

- **Model a minimal treasury/liquidity book inside the ledger.** Rejected: it would conflate the
  customer-position golden source with the bank's own book, undermining ADR-0039's single-source
  invariant, and any treasury model faithful enough to be useful is a large domain in its own right
  that the reference platform's purpose (demonstrate a licensable retail core) does not require.
- **A stub treasury service that fakes liquidity positions.** Rejected: a stub that produces
  plausible-but-meaningless LCR/intraday numbers is worse than an honest absence — it invites the
  reading that the platform has a treasury function when it does not.

## Consequences

**Positive**
- The scope boundary is explicit; evaluators and auditors see that treasury is external by design,
  not missing by oversight.
- The ledger keeps its single, well-defined meaning (customer positions), protecting ADR-0039.

**Negative**
- The platform is not, on its own, a complete bank: an operator cannot run instant/large-value
  settlement for real without adding treasury and prefunding management.
- Intraday-liquidity and LCR/NSFR obligations are unaddressed here and must be met elsewhere.

**Neutral**
- If a future decision brings any treasury capability in-house, it supersedes this ADR rather than
  editing it, keeping the boundary's history visible.

## Compliance impact

- PCI DSS: not applicable — no card data.
- DORA:    not applicable to this scope decision; treasury systems an operator adds carry their own
           ICT-risk obligations.
- GDPR:    not applicable — treasury deals with the bank's own positions, not personal data.
- PSD2:    not applicable.
- CNB:     LCR/NSFR (CRR) and BCBS 248 intraday-liquidity monitoring are explicitly *not* provided by
           this platform and are the operator's responsibility in a real deployment.

## References

- ADR-0039 — ledger as golden source, balance as projection
- ADR-0046 — daily FX revaluation mechanics and ČNB rates
- ADR-0104 — production-faithful payment rails + scheme simulator
- ADR-0022 — analytics layer (the data a treasury system would consume)
