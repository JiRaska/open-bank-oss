# 38. Withholding-tax remittance and lifecycle advance

Date: 2026-05-30

Status: Accepted

## Context

ADR-0033 made `openbank-interest-service` **withhold** Czech final tax (§36/§38d zákona č.
586/1992 Sb.) when credit interest is capitalized: the customer is credited net and a paired
`WithholdingTax` row is recorded with status `RECORDED`. ADR-0033 §F explicitly **deferred** the
downstream remittance (the monthly *Vyúčtování daně vybírané srážkou*) and the lifecycle advance to
the reporting capability (finding **G7**).

That leaves an open correctness gap: **withheld tax sits at `RECORDED` forever and is never remitted
to the tax authority.** The money is owed to the státní rozpočet — §38d odst. 3 obliges the plátce
daně to pay the withheld tax to the finanční úřad **by the end of the calendar month following the
month in which the withholding obligation arose**. A bank that withholds but never odvádí is in
breach. The `WithholdingTaxStatus` enum already anticipates this (`RECORDED → REMITTED →
RECONCILED`, plus `REVERSED`), but no code path advances a record past `RECORDED`.

G7's reporting workstream is otherwise complete (ADR-0037 AnaCredit; CZK/umbrella products). This
ADR closes the last derive-and-advance loop: assemble the monthly remittance and move the paired
records to `REMITTED`.

## Decision

We will add a **withholding-tax remittance** capability **inside** `openbank-interest-service` (it
owns the withholding aggregate, repository and outbox), assembling one monthly remittance batch per
tax period and advancing the paired records `RECORDED → REMITTED`.

1. **Pure policy (`WithholdingRemittancePolicy`, domain, framework-free).** Given the withholding
   records and a target `(year, month)`, it selects the **remittable** ones — `status = RECORDED`,
   `treatment = WITHHELD` (only actually-withheld tax is owed; `NOT_WITHHELD` / `EXEMPT` /
   `DEFERRED_FX` carry no liability and are left as the audit record), `currency = CZK`, and the
   **withholding month** (`periodTo`, the §38d credit date) equal to the target month. It sums the
   tax, counts the items, and derives the **due date** = last day of the month following the
   withholding month (§38d odst. 3). The result is a `WithholdingRemittance` aggregate.

2. **Idempotent assembly (`WithholdingRemittanceService`, application).** One batch per
   `(year, month, authority)` — re-running returns the existing batch and re-marks nothing. On first
   assembly it persists the batch, flips the selected records to `REMITTED` (stamping
   `remittance_id`), and emits a versioned `interest.withholding.remitted.v1` outbox event.

3. **Actual payment is delegated, off the money-path gate.** Like ADR-0033's recording step and the
   anacredit/SDD services, interest-service **assembles and records** the remittance and emits the
   event; the cash leg to the finanční úřad (and the XML *Vyúčtování* filing) is performed by the
   downstream payment/reporting consumer. interest-service is **not** a money-path service
   (`rules.yaml: money_path_services`), so this stays a derive-and-record change — no posting, no
   threat model, single approval.

4. **REST + contract.** `POST /api/v1/interest/withholding/remittances?year=&month=` assembles (or
   returns) the period batch; `GET …/remittances` lists; `GET …/remittances/{year}/{month}` fetches.
   `openapi.yaml info.version` bumps to **1.2.0** with the new paths documented and a contract test.

5. **Schema.** Flyway `V4__withholding_remittance.sql` adds the `withholding_remittance` table
   (unique per `period_year, period_month, authority`) and a nullable `remittance_id` FK on
   `withholding_tax`, with a rollback note.

## Alternatives considered

- **A new `openbank-tax-reporting-service`.** A dedicated bounded context, consistent with the
  one-service-per-regulatory-context house style (statement/sdd/anacredit). Rejected for v1: the
  withholding aggregate, its repository, entity and outbox already live in interest-service; a new
  service would need a read-replica or a chatty cross-service query just to flip a status it doesn't
  own. The remittance is a thin lifecycle advance over an existing aggregate, not a new context.
  Revisit if/when annual reconciliation + the SDMX/EPO filing transport grow into their own surface.
- **Advance the record on a scheduled job, no batch aggregate.** Flip `RECORDED → REMITTED` per
  record at month-end. Rejected: the *Vyúčtování* is a per-period **aggregate** return (one sum, one
  due date, one filing); without a batch row there is nothing to file, reconcile or pay against, and
  idempotency/late-record handling has no anchor.
- **Post the cash leg here.** Rejected: that would put interest-service on the money-path gate
  (2 approvals + threat model, ADR-0030) for what is otherwise a derive step. Delegating via event
  keeps the posture identical to ADR-0033 recording and ADR-0037 anacredit.

## Consequences

**Positive**
- The withholding lifecycle is no longer a dead-end: recorded tax is assembled into a monthly return
  and advanced to `REMITTED`, closing the §38d odvod obligation in the model.
- The batch aggregate is exactly the *Vyúčtování daně vybírané srážkou* shape (period, sum, due
  date), so the downstream filing/payment consumer has a first-class record to act on.
- Idempotent, period-keyed assembly is safe to re-run from a scheduler or by hand.

**Negative**
- Actual settlement correctness now depends on a downstream consumer of
  `interest.withholding.remitted.v1`; until that consumer exists the cash is *assembled* but not yet
  *paid*. This is the same delegation seam ADR-0033/0037 already rely on.

**Neutral**
- `REVERSED` records are excluded from assembly (only `RECORDED` is remittable); reversal pairing
  remains as ADR-0033 §F describes. `RECONCILED` (annual return) stays out of scope.
- Late withholding for an already-assembled period (a back-dated capitalization) is **not** swept
  into the existing batch in v1; a supplementary batch is a documented fast-follow.

## Compliance impact

- PCI DSS: not applicable (no card data).
- DORA:    not applicable (no new external ICT dependency; same outbox seam).
- GDPR:    minimal — the batch is aggregate financial data; `party_ref` on the underlying records is
           unchanged.
- PSD2:    not applicable.
- CNB:     supports the §38d odvod obligation (zákon č. 586/1992 Sb., daňový řád č. 280/2009 Sb.);
           the assembled batch is the basis for the *Vyúčtování daně vybírané srážkou podle zvláštní
           sazby daně*.

## References

- ADR-0033 — Withholding tax on credit interest at capitalization (§36/§38d ZDP); §F deferral.
- ADR-0037 — AnaCredit credit-exposure reporting (derive-and-record posture).
- ADR-0030 — Money-path governance (why delegation keeps this off the heavy gate).
- Zákon č. 586/1992 Sb., o daních z příjmů — §36 (zvláštní sazba), §38d (srážka a odvod).
- Zákon č. 280/2009 Sb., daňový řád — zaokrouhlování, lhůty.
