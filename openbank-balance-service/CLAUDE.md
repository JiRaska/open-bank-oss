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
- **The projection still applies each delta on event receipt, ignoring `entryDate` — the stored
  `bookedAmount`/`availableAmount` are receipt-dated running totals, and always will be.**
  `LedgerProjectionService.apply` books immediately; `entryDate` is persisted to `ledger_projection_event`
  (dedup + audit) and never defers the booking. **Do not read `availableAmount` and call it spendable.**
  The value-date-correct figures are `Balance.effectiveAvailable()` / `effectiveBooked()`, which subtract
  `notYetEffectiveCredit` — the Σ of *strictly positive* projected deltas with `entry_date > today`,
  hydrated per read by `BalanceService.withValueDateBasis` (ADR-0178 Phase 2, #1745). `withReservation`
  guards on the effective figure, so the cover decision is safe; anything else that reads the raw column
  is not.
  - **Why this is not exotic.** `SettlementDateResolver` books a payment to the **next business day**
    whenever it arrives at/after the 16:00 Prague cut-off or at a weekend, and `PaymentActivitiesImpl`
    passes that `bookingDate` to the ledger as `entryDate`. So a forward-dated credit is the normal
    shape of every after-hours and weekend incoming payment, not a welcome-bonus edge case.
  - **Credits only, deliberately.** Netting the tail would add future-dated *debits* back into the
    spendable figure — money already committed to an outbound payment. The query filters `delta > 0`
    and `Balance`'s `init` rejects a negative tail so that can't be reintroduced by accident.
  - **Derived, never materialized.** There is no `effective_booked` column: the figure is recomputed
    per read, so it becomes correct on its own when the accounting day passes the value date.
    `ValueDateRollScheduler` therefore only *announces* maturity to downstream consumers. Keep it that
    way — materializing it would turn a missed daily run from a delayed notification into a wrong
    money figure.
  - **Still open (product decision, not a bug):** whether `bookedAmount` should keep *displaying* the
    not-yet-effective credit ("visible but unspendable") or hide it until the value date. Both
    candidates agree it must not be spendable, which is the half that shipped.
- **`ledger_projection_event` is the authoritative value-dated reconstruction of the booked total.**
  `Σ delta` over all dates equals the materialized `Σ bookedAmount` by construction; rely on it for any
  as-of booked figure rather than trying to re-derive dates from the `balances` aggregate (which has none).
