# openbank-risk-engine — Documentation

> **What it is:** a read-only balance-sheet risk engine (ADR-0313, ADR-0314). A snapshot run freezes the ledger trial balance, the deposit sub-ledger and the contract-level instruments (loans, treasury money-market deals) for an as-of date; every risk figure is derived from that run on request and never stored. **What it is NOT:** a system of record. It writes only the frozen runs and the operator-uploaded curve sets.

This documentation is published by the service at the management endpoint `/q/openbank/docs` (Docs-as-Service, ADR 0019).

## TL;DR

- **Tech stack:** Kotlin / Quarkus 3.x / PostgreSQL (Flyway migrations `V1`..)
- **Ports:** 8159 (app), 8085 (management)
- **Auth:** reads for operators, `ROLE_RISK` and `ROLE_FINANCE`; writes (snapshot runs, curve sets) for `ROLE_RISK`, `ROLE_OPERATOR` and `ROLE_ADMIN` humans only.

## Risk reads

All reads hang off a snapshot run: `GET /api/v1/risk/snapshots/{id}/...` — `positions`, `instruments`, `cash-flows`, `irrbb`, `liquidity`, `liquidity-forecast`, `capital`, `min-reserves`, `limits`. A run that is not TIED_OUT answers 409; an unknown run or curve set answers 404.

## IRRBB (`GET /snapshots/{id}/irrbb`)

Repricing gap, ΔEVE under the six BCBS d368 scenarios, ΔNII over 12 months under parallel up/down, the worst case and the assumptions behind them.

- **Curve set.** `curveSetId` is optional. Without it, the newest curve set recorded as of exactly the run's own date is used, never a neighbouring day's; 400 when there is none. A set of another date is always a 400.
- **Treasury money-market deals.** Placements, ČNB deposits, borrowings and ČNB lombard enter the book as fixed-rate deals: the whole principal reprices on the maturity date (assets positive, liabilities negative, as the snapshot signs them), and the flows are principal plus ACT/360 interest at maturity. They are INCLUDED in the gap, ΔEVE and ΔNII. The `treasury[]` array breaks out their share per currency (deal count, placements, borrowings as a magnitude, base PV and ΔEVE per scenario); it is a breakdown, never an addition. It is empty for a run without deals.
- **Shock sizes.** The shipped configuration carries, for both CZK and EUR, parallel 200 / short 250 / long 100 bp (Delegated Regulation (EU) 2024/856, Annex Part A). A currency without sizes is listed in `shockNotConfigured` and gets no scenarios.
- **Supervisory outlier test.** Tier 1 is the caller's `tier1Capital` when supplied, otherwise the run's own-funds Tier 1 (CZK), the same figure the `irrbb-eve-outlier` limit uses; the own-funds figure is used only for a CZK aggregate. The threshold and early warning come from the declared `irrbb-eve-outlier` limit (15 % / 12 % shipped), and the result carries `status` (`OK`, `EARLY_WARNING`, `BREACH`, `NOT_EVALUABLE`), `tier1Source` (`caller` or `own-funds`) and `tier1Gap` (why no Tier 1 is usable). A missing figure is `NOT_EVALUABLE`, never `OK`.
- **Data gaps.** `dataGaps[]` states what the figures do not capture, with a stable `code`: `CURVE_EXTRAPOLATED_FLAT` (flows after a curve's last pillar are priced at its last zero rate held flat; reports the flows beyond and their base PV), `PREPAYMENT_NOT_MODELLED`, `NMD_BEHAVIOUR_SIMPLIFIED`, `COMMERCIAL_MARGIN_INCLUDED` and `INSTRUMENTS_NOT_PROJECTED`. The last one is raised only for instrument kinds the projection still does not read (loans and money-market deals are read), and its detail names them. These are modelling simplifications or missing inputs, not errors; the numbers are still computed.

## Contract

The API contract is `openapi.yaml` (`info.version` follows ADR-0048). The IRRBB changes above are additive (1.23.0 and 1.24.0).
