---
date: 2026-09-06
decision-status: accepted
delivery-status: shipped
authors: [Jiri Raska]
supersedes: []
superseded-by: []
delivery-repos: []
tags: [resilience, payments]
summary: "Balance creation POSTs are idempotent on natural keys — credit/debit via the movement ledger, initialize via (account, currency), holds via (account, currency, referenceId); reconciliation re-runs are by design. No synthetic key."
---

# ADR-0287 — Balance creation POSTs are idempotent on natural keys, not synthetic keys

## Context

The #8351 idempotency inventory (M2 exit criterion) requires every money-path POST to
either enforce a declared idempotency handle or carry an ADR-linked exception. Five
balance-service POSTs declared no handle:

- `POST /api/v1/balances/{accountId}/credit` and `…/debit` — direct money movement (transaction saga)
- `POST /api/v1/balances/{accountId}/holds` — reserve funds (card/payment authorisation)
- `POST /api/v1/balances/{accountId}/initialize` — open the (account, currency) balance
- `POST /api/v1/balances/reconciliation` — on-demand tie-out run

## Decision

Keep the endpoints free of a synthetic key and make the natural keys the contract:

- **credit / debit** — already enforced since V8 (`balance_movement`): the caller-supplied
  `referenceId` plus (account, currency, operation) is the dedup marker, written in the same
  transaction as the balance mutation. This ADR documents the existing control.
- **initialize** — naturally idempotent on (accountId, currency): a repeat call returns the
  existing balance; the `UNIQUE (account_id, currency)` constraint backs the race.
- **holds** — the one real gap found by the burn-down: a retried placeHold reserved TWICE
  (the amount stayed unavailable until expiry or release — a real money effect). Fixed in the
  same change: `BalanceService.placeHold` checks the natural key
  (accountId, currency, referenceId) first and replays the original hold with no second
  reservation and no second event; `uq_balance_holds_reference` (V10) is the race backstop
  for two concurrent first attempts, recovered by re-reading the winner's row.
- **reconciliation** — an operator trigger, not a fact creation: it mutates no balance, and a
  re-run for the same date records a fresh report row BY DESIGN (the audit trail of runs).
  There is nothing to deduplicate.

## Alternatives considered

- **Synthetic `Idempotency-Key` header on all five** — rejected: four of the five already
  have a caller-visible natural key (or no fact to dedup), and a second key would give one
  business fact two identities. The saga and card-authorisation callers already supply
  `referenceId` precisely so retries are safe.
- **Dedup holds only via the unique index, without the check-first** — rejected: a unique
  violation surfaces as a 500-family failure to the caller; the check-first is what makes a
  replay return the original hold (2xx) instead of an error.

## Consequences

- Every balance creation POST now has a stated, enforced idempotency contract; the #8351
  baseline entries re-point here.
- Reusing a `referenceId` for a DIFFERENT hold on the same account and currency is now
  rejected by replay semantics (the original hold is returned). Callers must mint one
  referenceId per business fact — which is what the field already means to the ledger
  projection (referenceId == transactionId, ADR-0039 Phase D).
- The pre-V10 double-reservation window is closed for sequential retries by the check-first
  and for concurrent first attempts by the index.

## Compliance impact

Not applicable to any new obligation — the change strengthens an existing control (a hold is
a reservation of customer funds; double-reservation is an availability defect with
customer-visible impact), which matters for PSD2-style service-quality expectations rather
than introducing a new one.
