# openbank-risk-engine — Documentation

> **What it is:** a read-only balance-sheet risk engine (ADR-0313, ADR-0314). A snapshot run freezes the ledger trial balance, the deposit sub-ledger and the contract-level instruments (loans, treasury money-market deals) for an as-of date; every risk figure is derived from that run on request and never stored. **What it is NOT:** a system of record. It writes only the frozen runs and the operator-uploaded curve sets.

This documentation is published by the service at the management endpoint `/q/openbank/docs` (Docs-as-Service, ADR 0019).

The [architecture chapter](./02-architecture.en.md) traces snapshot inputs, tie-out and projections. The generated **API surface** chapter is rebuilt from this service's `openapi.yaml` in CI for every image; the full contract remains available at `/q/openapi`.

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

## Nostro balances in the standardised credit-risk capital calculation

GL accounts 1001 (nostro CZK) and 1002 (nostro EUR) are classified `nostro` in
`openbank.risk.capital.sa.classification.gl-accounts` (previously `bank`). The new `NOSTRO` capital GL class
(wire value `nostro`) is applied as follows:

- **Debit balance:** a claim on a correspondent bank, weighted exactly as the `bank` class (exposure class
  BANK, risk weight from the configured `bankScraGrade`).
- **Credit balance:** the nostro is overdrawn, i.e. owed to the correspondent. This is a liability, not a credit
  exposure, so it contributes no exposure and no longer makes the capital ratios not evaluable (the generic
  rule for a credit balance on an exposure account is not applied to it). It is not netted against any other
  exposure.

This is a policy choice (#11107).

### Parameter set versions

| Set | Version | Change |
|-----|---------|--------|
| `bcbs-d424-sa` | 2 to 3 | GL 1001/1002 classified `nostro` |
| `eu-crr3-sa` | 1 to 2 | GL 1001/1002 classified `nostro` |

### Tests

The change adds `SnapshotServiceTest` cases for the loan-book tie-out on GL 1200 (an empty loan book while the ledger holds loans is untied; an empty loan book against loans netting to zero ties out; a failing loan-book read fails the snapshot and stores nothing), and extends
`CreditRiskCapitalTest` to cover debit and credit nostro balances.

## Liquidity classification of residual GL accounts

Five GL accounts were previously unclassified for liquidity, which made LCR, NSFR and their limits not evaluable.
They are now classified in `openbank.risk.liquidity.classification.gl-accounts` (policy choices, #11107):

| GL account | Class | Treatment |
|------------|-------|-----------|
| 1100 Customer Cash Clearing | `technical-or-clearing` | by balance sign, see below |
| 1990 / 1991 FX Position CZK / EUR | `technical-or-clearing` | by balance sign, see below |
| 1995 FX Position Counter-Value EUR (CZK) | `technical-or-clearing` | by balance sign, see below |
| 2200 Withholding Tax Payable | `other-liability` | liability due within the 30-day horizon: 100% LCR outflow, 0% ASF |

The new `TECHNICAL_OR_CLEARING` class (wire value `technical-or-clearing`) is for balances whose counterparty,
maturity and direction are not recorded. It is resolved by the sign of the balance, each side at the most
conservative factor the engine has, so neither side can improve a ratio:

- **Debit (asset):** handled as `other-asset`: not HQLA, no LCR inflow, 100% RSF.
- **Credit (liability):** handled as `other-liability`: 100% LCR outflow, 0% ASF. A credit balance never becomes a
  negative RSF line that reduces the required stable funding.

### Parameter set versions

| Set | Version | Change |
|-----|---------|--------|
| `bcbs-d238-d295` | 4 to 5 | GL 1100, 1990, 1991, 1995, 2200 classified |
| `eu-2015-61-crr2` | 3 to 4 | GL 1100, 1990, 1991, 1995, 2200 classified |

### Tests

`ResidualGlLiquidityClassificationTest` covers the new classification; the parameter-set version assertions in the
existing liquidity tests and `RiskLiquidityApiIT` are updated.

## ČNB minimum reserves

The risk engine consumes `openbank.fx.cnb-policy-rate.published` into its local policy-rate fact table. A redelivery is idempotent on instrument and effective date; a revised rate updates the stored fact. The minimum-reserves endpoint derives its result from a tied-out snapshot and the reserve ratio and remuneration effective on that run's as-of date. Missing facts produce `424 NOT_EVALUABLE` with a reason instead of a default numeric rate; an unknown run remains 404 and an untied run remains 409. The analysis is calculated on read and is not persisted as a separate result.
