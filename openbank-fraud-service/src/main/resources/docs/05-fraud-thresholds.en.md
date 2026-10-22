# Fraud rule thresholds

The amount-based rules (`large-single-transaction`, `velocity-h1-amount`, `new-payee-high-amount`) compare payments against money thresholds per currency. Since ADR-0347 those thresholds are versioned data in the `fraud_threshold_set` table, not Kotlin constants. The rule logic is still code.

## Which set is in force

Each score reads the active set from the table: the highest `EXECUTED` version whose `active_from` is not in the future. A higher version always supersedes a lower one, even one scheduled for a later date. Nothing is cached, so an approval applies on every replica at its `active_from`. Every `fraud_scores` row records the version it was evaluated against in `threshold_set_version`.

Version 1 is seeded by `V9__fraud_threshold_set.sql`. It holds exactly the figures the rules had as constants (CZK 500 000 / EUR 20 000, CZK 1 000 000 / EUR 40 000 per hour, CZK 250 000 / EUR 10 000 for a new payee), so verdicts did not change on rollout.

## Changing a threshold (four-eyes)

1. A risk or compliance user (`ROLE_RISK`, `ROLE_COMPLIANCE`) sends `POST /api/v1/fraud/thresholds/proposals` with the complete new version: all three rules, decimal strings, a version above every live one, and optionally `effectiveFrom`. This changes no verdict.
2. A different user calls `POST /api/v1/fraud/thresholds/proposals/{id}/decide`. The proposer cannot approve their own proposal: the service returns 422, and a database CHECK constraint refuses it too.
3. On approval the set applies from the later of `effectiveFrom` and the approval time. A set cannot be back-dated.

`GET /api/v1/fraud/thresholds/proposals/pending` lists open proposals. `GET /api/v1/fraud/thresholds/active` shows the set in force. Operators and machine principals cannot use these endpoints.

## The record cannot be erased

Every proposal and decision stays in the table. The service's own database role can read it, add a proposal and record a decision, and nothing else. It cannot delete a version, empty the table, or change an approved set's amounts or who proposed it.

## Failing closed

- A currency missing from a rule's map makes that rule return REVIEW (`…-unmapped-currency`) for that currency.
- With no active set, every amount rule returns REVIEW and the verdict carries `threshold-set-unavailable`. `GET …/active` then answers 404.

REVIEW sends the payment to an analyst. The engine never answers DECLINE here, because a configuration gap should not refuse every customer payment.
