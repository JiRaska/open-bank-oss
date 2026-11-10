# Threat model - openbank-finrep-service

## Scope

FINREP preview and return assembly (F01.01, F01.02, F01.03, F02.00 and others) from the ledger trial balance. It posts nothing; the asset is the correctness of a regulatory figure.

## Accepted limitations (#13189)

- **Shared accrual accounts.** GL 1520/1521 (accrued interest receivable) and 2310/2311 (accrued interest payable) are shared between ČNB and interbank counterparties. The mapper cannot split them, so ČNB accrued interest receivable is reported with loans and advances (F01.01 r0181/r0183) and accrued interest payable is reported under credit institutions (F01.02 r0150) rather than partly under central banks (r0130). Impact: misclassification between sub-lines of the same parent, totals unaffected. Resolution: separate ledger accounts per counterparty class, carried by the product revision `regulatoryClass` (ADR-0341).
- **Nostro overdraft granularity.** Overdraft is detected per ledger line of GL 1001/1002 (account and currency), not per correspondent bank, so two nostros of one bank in different currencies are not netted before the check.
- **Unverified datapoint ids.** F01.01 r0030 and F01.02 r0130/r0150 are emitted with the row coordinate from the EBA template but without a datapoint id verified against the pinned annotated templates (fixture entries carry null). XBRL-CSV preflight blocks any return containing them (`UNVERIFIED_DATAPOINT_ID`), so they cannot be silently submission-ready. Tracked in #13603.

## Submission lifecycle

finrep now persists one submission per (template, reference period, revision) in its own Postgres (`finrep-db`), with every transport attempt and the supervisor's acknowledgement. Threats and controls:

- **A dry run read as a filing (repudiation / integrity).** No live supervisory transport exists in this repository. The default `SupervisoryReturnTransport` answers `DRY_RUN`, a value of its own that never advances the return past PACKAGED and is never shared with `SUBMITTED`. `ACKNOWLEDGED` is reachable only from `SUBMITTED`, so a dry run cannot be acknowledged into an apparent filing.
- **Packaging an ineligible return.** Packaging re-runs the XBRL-CSV preflight and refuses with 409 while any blocker is present. The stored package is a hashed manifest (identity plus framework and taxonomy versions), not a rendered XBRL-CSV archive.
- **Duplicate filing of one revision.** The unique key on (template_id, reference_period, revision) makes open idempotent; a re-submit of a return already SUBMITTED, ACKNOWLEDGED or REJECTED returns it without another transport call.
- **Unauthorised state change.** All routes require ROLE_ADMIN or ROLE_OPERATOR (JWT roles, as for the rest of finrep); the acting principal is recorded on every attempt and acknowledgement.
- **Accepted limitation.** The acknowledgement is recorded by an operator, not received from the supervisor's channel, so its authenticity rests on that operator. Replacing the dry-run adapter with a live one needs institution-held credentials and its own threat-model update.
