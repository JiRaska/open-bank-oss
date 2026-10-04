# openbank-risk-engine

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

## Parameter set versions

| Set | Version | Change |
|-----|---------|--------|
| `bcbs-d424-sa` | 2 to 3 | GL 1001/1002 classified `nostro` |
| `eu-crr3-sa` | 1 to 2 | GL 1001/1002 classified `nostro` |

## Tests

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

## Parameter set versions

| Set | Version | Change |
|-----|---------|--------|
| `bcbs-d238-d295` | 4 to 5 | GL 1100, 1990, 1991, 1995, 2200 classified |
| `eu-2015-61-crr2` | 3 to 4 | GL 1100, 1990, 1991, 1995, 2200 classified |

## Tests

`ResidualGlLiquidityClassificationTest` covers the new classification; the parameter-set version assertions in the
existing liquidity tests and `RiskLiquidityApiIT` are updated.
