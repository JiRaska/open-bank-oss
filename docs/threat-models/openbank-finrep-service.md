# Threat model - openbank-finrep-service

## Scope

FINREP preview and return assembly (F01.01, F01.02, F01.03, F02.00 and others) from the ledger trial balance. It posts nothing; the asset is the correctness of a regulatory figure.

## Accepted limitations (#13189)

- **Shared accrual accounts.** GL 1520/1521 (accrued interest receivable) and 2310/2311 (accrued interest payable) are shared between ČNB and interbank counterparties. The mapper cannot split them, so ČNB accrued interest receivable is reported with loans and advances (F01.01 r0181/r0183) and accrued interest payable is reported under credit institutions (F01.02 r0150) rather than partly under central banks (r0130). Impact: misclassification between sub-lines of the same parent, totals unaffected. Resolution: separate ledger accounts per counterparty class, carried by the product revision `regulatoryClass` (ADR-0341).
- **Nostro overdraft granularity.** Overdraft is detected per ledger line of GL 1001/1002 (account and currency), not per correspondent bank, so two nostros of one bank in different currencies are not netted before the check.
- **Unverified datapoint ids.** F01.01 r0030 and F01.02 r0130/r0150 are emitted with the row coordinate from the EBA template but without a datapoint id verified against the pinned annotated templates (fixture entries carry null).
