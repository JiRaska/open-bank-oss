---
date: 2026-07-19
decision-status: accepted
delivery-status: shipped
authors: [jiri.raska]
supersedes: []
superseded-by: []
delivery-repos: []
tags: [ledger, accounts, transactions]
summary: "Booked-balance reconciliation moves onto the ledger's value-date basis by subtracting the future-value-dated tail from the materialized sub-ledger sum, with a value-date-aware effective balance and daily roll as later phases."
---

# Value-date-correct balance reconciliation and projection

## Context

ADR-0039 made the ledger the golden source of the booked balance and turned balance-service into a
projection of it: the ledger emits `AccountBookedChangedEvent(accountId, currency, delta,
journalEntryId, entryDate, version)` and balance-service applies `delta` to `bookedAmount`. A standing
per-currency reconciliation (Phase A) ties the ledger deposit-control GL balance out against the sum of
balance-service booked amounts. Phases A–D are shipped.

The two sides recognise a booked movement on **different temporal bases**, and ADR-0039 never
reconciled them:

- The ledger **trial balance is value-dated**. `TRIAL_BALANCE_SQL` sums journal lines
  `where je.status in (…booked…) and je.entry_date <= :asOf`. A journal with a future `entry_date`
  (a value-dated credit — a welcome bonus, a scheduled interest posting, a forward-value transfer) is
  POSTED but **not counted in the control balance until its value date arrives**.
- The **projection is receipt-dated**. `LedgerProjectionService.apply` adds `delta` to `bookedAmount`
  the moment the event is consumed, regardless of `entryDate`; `balances.bookedAmount` is a running
  total that carries **no value-date dimension**. `entryDate` is persisted to the dated projection
  audit (`ledger_projection_event`) but never gates the booking.

Consequence: the Phase A tie-out compares the ledger control **as of a value date** against the
sub-ledger **current running total**. Any future-value-dated journal therefore surfaces as
per-currency "drift" for the entire window between its posting and its value date — a **false
positive** that resolves itself on the value date, with the money never having been wrong on either
side. The difference equals, to the penny, the sum of the not-yet-effective journals.

This is a distinct failure mode from the two-writer divergence ADR-0039 fixed. It is not a lost or
phantom movement; both sides are internally consistent. It is a **measurement-basis mismatch** in a
detective control. The ADR-0160 sustained-drift `for:` dampener does **not** contain it: a value-date
mismatch is sustained across the whole pre-value-date window (potentially days), not a transient
snapshot, so it would page for the full window on a benign, fully-explained figure.

## Decision

Reconcile — and eventually project — the booked balance on the **ledger's own value-date basis**, in
three independently shippable steps.

1. **Value-date-correct reconciliation (Phase 1 — this ADR's first ship).** The tie-out computes the
   sub-ledger sum on the same basis as the ledger control: per currency, the **materialized** booked
   balance minus the future-value-dated tail — `Σ bookedAmount − Σ(projected delta with
   entry_date > asOf)`, the tail read from the dated projection audit (`ledger_projection_event`).
   This mirrors the ledger's `entry_date <= :asOf` exactly, so the two sides compare like-for-like and
   future-value-dated journals are excluded on **both** sides until their value date (verified against
   production: current `3,719,764 − 500,000 = 3,219,764`, equal to the ledger control to the penny).
   Anchoring on the materialized `balances` rather than on the audit sum is deliberate: it preserves
   the integrity coverage of the plain aggregate tie-out — a write-path defect that desynchronized
   `balances` from the projection audit still surfaces as drift instead of being masked by summing the
   audit alone. (An explicit `Σ balances == Σ audit-delta` cross-check is Phase 3.) No write-path
   change — this is read-only control correctness.

2. **Value-date-aware booked balance (Phase 2 — follow-up).** Decide the customer-facing semantics: a
   future-value-dated credit should be *visible* but must not count toward **effective booked /
   available** funds until its value date, matching the ledger and standard value-date practice.
   Introduce an effective-booked read (`entry_date <= today`) plus a daily **value-date roll** that
   promotes matured entries, and derive the spendable figure from effective-booked rather than the raw
   running total. This closes the window in which a customer could see or spend a not-yet-effective
   credit.

3. **Control quality (Phase 3 — follow-up).** The reconciliation surfaces the future-value-dated
   pipeline (the expected upcoming movements per currency) so an operator can see *why* a value-date
   figure differs, and alerting fires only on drift that is **not** explained by value-date timing —
   value-date-correct basis (Phase 1) plus sustained duration (ADR-0160), never on the benign
   pre-value-date window.

## Alternatives considered

- **Widen the reconciliation tolerance.** Hides genuine drift as readily as value-date noise; a
  control account must tie out to zero. Rejected.
- **Rely on the ADR-0160 `for:` dampener alone.** Only suppresses transient snapshots; a value-date
  mismatch is sustained for the whole pre-value-date window, so the alert still fires on a fully
  benign, self-explaining figure. Rejected as insufficient.
- **Defer the projection write itself until value date.** The correct long-term shape for the read
  model, but it needs the value-date roll and a product decision on effective-booked semantics.
  Deferred to Phase 2 rather than folded into the read-only control fix.

## Consequences

**Positive**
- The standing tie-out ties to the penny on the ledger's value-date basis; future-value-dated
  postings no longer raise false drift.
- The value-date pipeline becomes explicit rather than an unexplained control-account difference.
- Stronger CNB control-account ⇄ sub-ledger evidence (both sides reconciled on one basis) and less
  alert noise (DORA signal quality).

**Negative**
- Phase 2 changes read-model semantics on a money-path service and lands in phases, each with 2
  approvals + a threat-model refresh.

**Neutral**
- Phase 1 changes no write path and no customer-visible balance API; holds / available are unchanged.
- The reconciliation still runs for `asOf = today` on schedule; the basis change only affects which
  projected deltas it counts.

## Compliance impact

- PCI DSS: not applicable.
- DORA:    reconciliation is an ICT integrity control; a value-date-correct basis removes false
           positives and sharpens incident detection.
- GDPR:    not applicable (no new personal data; reads existing projection audit).
- PSD2:    Phase 1 does not change the cover/available decision. Phase 2 makes effective-booked
           value-date-correct, tightening available-funds semantics — tracked with its own review.
- CNB:     zákon o účetnictví 563/1991 Sb. a vyhláška 501/2002 Sb. — control account and its
           analytická evidence (sub-ledger) now tie out on a single value-date basis.

## References

- ADR-0039 — ledger as golden source; balance-service as a ledger projection (this ADR extends it).
- ADR-0160 — sustained-drift alerting (`for:` clause); necessary but not sufficient here.
- `openbank-ledger-service` `PanacheJournalRepository.TRIAL_BALANCE_SQL` (`entry_date <= :asOf`).
- `openbank-balance-service` `BalanceReconciliationService`, `LedgerProjectionService`,
  `ledger_projection_event`.
- Threat model (ADR-0030 D2): `docs/threat-models/balance.md`.
