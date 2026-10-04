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
