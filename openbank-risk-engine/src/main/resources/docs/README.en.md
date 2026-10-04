# openbank-risk-engine

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
