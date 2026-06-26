# 116. Dispute and complaint handling — PSD2 statutory deadlines, evidence chain, breach detection

Date: 2026-06-25
Author: Claude (paired with Jiří Raška)
Status: Accepted

## Context

`openbank-dispute-service` contains two separate domain models:
- `DisputeService` — card disputes and payment chargebacks (transaction-linked, evidence-backed)
- `ComplaintService` — general customer complaints with statutory deadline tracking

ADR-0085 (complaints handling) exists as a high-level intent document but does not specify the
implementation, the PSD2 deadline mechanics, or the architectural split between disputes and
complaints. This ADR captures the implemented decisions.

## Decision

**1. Disputes and complaints are separate aggregates in one bounded context.**

| Dimension | Dispute | Complaint |
|-----------|---------|-----------|
| Trigger | Transaction chargeback / card dispute | Any customer grievance |
| Transaction link | Mandatory (`accountId`, optionally `transactionId`) | Optional |
| Evidence | Append-only `DisputeEvidence` attachments | Not applicable |
| Escalation path | `ESCALATED` → external arbitration | `interimReply` → final resolution |
| Regulatory basis | EPC SCT Rulebook, card scheme rules | PSD2 Art. 101 + EBA Guidelines |

**2. Complaint statutory deadlines (PSD2 Art. 101 transposition).**

Deadline calculation is **domain logic** in `ComplaintService`, using `BusinessCalendar.forCurrency("CZK")`
(Czech bank holidays, CERTIS calendar) — not calendar days:

- **Standard deadline:** `dueDate = receivedDate + 15 business days`
- **Extended deadline (interim reply):** `dueDate = receivedDate + 35 business days`
  (operator records a reason and sets the extended deadline via `interimReply`)
- **Breach:** `today > dueDate AND status NOT IN {RESOLVED, CLOSED}`

The `Clock` is injected (ADR-0100) and defaults to `Europe/Prague` timezone for "today" — an
off-by-one in business-day math is a regulatory breach, so the calculation must be deterministically
testable.

Constants in code: `STANDARD_DEADLINE_DAYS = 15`, `EXTENDED_DEADLINE_DAYS = 35`.

**3. Breach derivation is lazy (per-read), not event-driven.**

`withBreach(complaint)` is a pure function called in every read path (`getComplaint`, `listAll`,
etc.). It computes `breached = LocalDate.now(clock) > dueDate AND status is open`.

The breach flag is **informational**: it does not block operations or trigger automatic escalation.
`ComplaintDeadlineGauge` publishes three Prometheus gauges with a 30-second refresh tick (service-local,
no libs fleet rebuild required):
- `openbank_complaints_open`
- `openbank_complaints_due_soon`
- `openbank_complaints_due_breach`

**4. Dispute evidence chain is append-only.**

`DisputeEvidence` records are inserted, never updated or deleted — they form an immutable audit
trail. Withdrawal (`withdraw`) and escalation (`escalate`) are status transitions, not evidence
deletions.

**5. Escalation.**

A dispute in `ESCALATED` state signals that internal resolution failed. In production this would
trigger routing to the Financial Arbitrator (Finanční arbitr ČR) or the relevant card scheme
dispute resolution process. The integration is not yet implemented — escalation currently only
changes status and emits an event.

## Alternatives considered

- **Merge dispute and complaint into one aggregate.** Loses type safety; chargeback lifecycle
  (evidence, scheme deadlines) is structurally different from a general complaint.
- **Event-sourced dispute.** Appropriate for high-volume schemes; adds operational complexity for
  current scope. Can be revisited if dispute volume requires it.
- **Eager breach recomputation (scheduled job).** Would set `breached = true` in the DB on
  deadline breach. Simpler to query, but introduces async lag and requires a separate scheduler.
  Lazy per-read is simpler and correct for current volume.

## Consequences

**Positive**
- PSD2 statutory deadlines are automatically tracked without manual SLA spreadsheets.
- Business-day calculation uses the Czech banking calendar — regulatory accuracy.
- `ComplaintDeadlineGauge` enables proactive alerting before a breach, not only after.

**Negative**
- `withBreach()` is called on every complaint read. At high complaint volumes (millions of records)
  this adds per-request date arithmetic. Acceptable now; revisit at scale.
- No automatic notification or escalation on breach — only a Prometheus gauge. An operator must
  act on the alert manually.
- Financial Arbitrator integration (for escalated disputes) is not implemented.

**Neutral**
- Complaint `reference` is currently `"CMP-${System.currentTimeMillis()}"` — not idempotent. A
  future hardening should switch to a deterministic reference (e.g. `CMP-{UUIDv7}`).
- Dispute-to-transaction linkage is reference-only (`transactionId: UUID?`) — no FK constraint.

## Compliance impact

- PSD2 Art. 101: complaints resolved within 15 BD (standard) / 35 BD (extended) — enforced.
- EBA Guidelines on Complaints Handling (EBA/GL/2012/07): evidence retention and reporting required.
- GDPR: dispute and complaint data are PII-adjacent → retention per ADR-0117 (typically 5–7 years,
  longer of AML Act and contract statute of limitations).
- ČNB: quarterly reporting of unresolved complaints to ČNB is required for licensed institutions.
- DORA: not applicable to this ADR.

## References

- `openbank-dispute-service/src/main/kotlin/.../usecase/ComplaintService.kt`
- `openbank-dispute-service/src/main/kotlin/.../usecase/DisputeService.kt`
- `openbank-dispute-service/src/main/kotlin/.../observability/ComplaintDeadlineGauge.kt`
- ADR-0085 (complaints handling — high-level intent)
- ADR-0100 (clock injection — deterministic `Clock` for deadline calculation)
- PSD2 Art. 101 (complaint handling statutory deadlines)
- EBA/GL/2012/07 (EBA Guidelines on Complaints Handling)
