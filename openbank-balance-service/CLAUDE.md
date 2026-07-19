# openbank-balance-service — agent notes

Money-path service. Balance is a **projection** of the ledger (ADR-0039): the ledger is the golden
source of `bookedAmount`; balance-service applies `AccountBookedChangedEvent` deltas and owns only the
reservation layer (holds / available). On divergence the ledger wins — reconciliation flags, never
"fixes" toward the projection.

## Pitfalls

- **The reconciliation tie-out must compare on the ledger's value-date basis, not the current running
  total.** The ledger trial balance is value-dated (`entry_date <= :asOf`); a future-value-dated
  journal (welcome bonus, scheduled interest, forward-value transfer) is POSTED but not in the control
  balance until its value date. `balances.bookedAmount` is a receipt-dated running total with no
  value-date dimension, so summing it directly against the value-dated control raises a **self-resolving
  false drift** equal to the not-yet-effective journals, for the whole window until their value date.
  Compute the sub-ledger sum value-date-correct: `sumBookedByCurrencyAsOf(asOf)` = materialized
  `Σ bookedAmount − Σ(delta where entry_date > asOf)` (the future-dated tail from
  `ledger_projection_event`), which mirrors the ledger exactly (ADR-0178). **Anchor on the materialized
  `balances`, not on the audit sum** — summing the audit alone (`Σ delta where entry_date <= asOf`) gives
  the same number when the write path is healthy but would *hide* a `balances`⇄audit desync, so it
  trades away the integrity coverage the tie-out exists for. The ADR-0160 sustained-drift `for:` dampener
  does **not** save the value-date noise either: a value-date gap is sustained across days, not a
  transient snapshot.
- **The projection applies each delta on event receipt, ignoring `entryDate`.**
  `LedgerProjectionService.apply` books immediately; `entryDate` is persisted to `ledger_projection_event`
  (dedup + audit) but never defers the booking. So `bookedAmount` can include a future-value-dated credit
  a customer should not yet be able to spend — the effective-booked / value-date-roll semantics are the
  open Phase 2 of ADR-0178, not yet built.
- **`ledger_projection_event` is the authoritative value-dated reconstruction of the booked total.**
  `Σ delta` over all dates equals the materialized `Σ bookedAmount` by construction; rely on it for any
  as-of booked figure rather than trying to re-derive dates from the `balances` aggregate (which has none).
