---
date: 2026-07-22
decision-status: accepted
delivery-status: partial
authors: [Jiri Raska]
supersedes: []
superseded-by: []
delivery-repos: []
tags: [tax, regulatory-reporting, compliance]
summary: "The §38d withholding-tax statement (Vyúčtování daně vybírané srážkou) is owned by a new openbank-tax-reporting-service consuming interest.withholding.remitted.v1, not finrep-service; monthly cadence, GFŘ EPO XML transport."
---

# ADR-0180 — Withholding-tax statutory filing (§38d Vyúčtování) — owner, cadence, transport

## Context

`openbank-interest-service` withholds tax on capitalized interest (§38d zákona o daních z příjmů) and,
since ADR-0038, assembles and remits the **cash leg** to the state budget: `capitalize()` records a
`WithholdingTax` row and emits `interest.withholding.recorded.v1`; `WithholdingRemittanceService`
assembles a monthly batch (`RECORDED → REMITTED`, `interest.withholding.remitted.v1`); a settlement
consumer books the DEBIT through the usual external-payment path. That closes the *money movement*.

It does **not** close the *statutory filing*. §38d odst. 3 requires the plátce daně to file a periodic
**Vyúčtování daně vybírané srážkou podle zvláštní sazby daně** with the finanční úřad — a tax return, in
the GFŘ **EPO** XML format, distinct from paying the money. ADR-0038 §Decision.3 explicitly delegated
this "to the downstream payment/reporting consumer" but **never assigned an owner**, and self-flagged:
*"actual settlement correctness now depends on a downstream consumer … until that consumer exists the
cash is assembled but not yet remitted"* (issue #999). Today no service consumes
`interest.withholding.remitted.v1` for filing; `finrep-service` has zero references to withholding/§38d.
Booking the cash without filing the return is itself a compliance gap: the FÚ needs the periodic
statement, not just money arriving.

This ADR decides **who owns the §38d filing, on what cadence, over what transport** — the blocker in
front of #999, ahead of any filing code. (The separate gap that nothing *triggers* capitalization —
`capitalizeAll` was a stub — is decided mechanics under ADR-0033/0038 and is fixed independently of this
ADR by an `InterestCapitalizationScheduler`; a filing with nothing to file is moot, but the owner
question stands on its own.)

## Decision

We will file the §38d statement from a **new bounded context, `openbank-tax-reporting-service`**, not
from `finrep-service` and not from `interest-service`.

- **Owner.** A dedicated tax-reporting service, house-style one-service-per-regulatory-context (as
  `sdd-service`, `finrep-service`, `aml-service` each own one). It **consumes
  `interest.withholding.remitted.v1`** (a second consumer group alongside the existing settlement
  consumer — no change to interest-service's contract), aggregates withheld amounts per §38d period, and
  is the platform's system of record for the filing.
- **Cadence.** Monthly assembly aligned to the existing `WithholdingRemittancePolicy` deadline (odvod due
  the last day of the month following the withholding month), plus the **annual reconciliation** that
  ADR-0038's `WithholdingTaxStatus.RECONCILED` already anticipates but left out of scope. Assembly is
  idempotent per period (as ADR-0038's remittance assembly already is), so it is safe to drive from a
  scheduler and to re-run by hand.
- **Transport.** Render the GFŘ **EPO** XML for *Vyúčtování daně vybírané srážkou podle zvláštní sazby
  daně*, and submit through the EPO channel. Because the finanční úřad exposes no public real-time filing
  API, v1 targets a **generated-and-exported** artifact (operator submits via the EPO portal / datová
  schránka), with an attestation of what was filed — the same "render a regulatory artifact + a transmit
  stage" shape ADR-0097 defines for EBA returns, without sharing its source or taxonomy.

This ADR was **Proposed / planned** when written: it assigned the owner and shape without building the
service. It is now **Accepted / partial** — see Delivery.

## Delivery

**Increment 1 — the consumer and the aggregation. Shipped as `openbank-tax-reporting-service`.**

The premise was re-checked against the live repo before building, and one part of it had changed:
issue #999 is **closed**, `InterestCapitalizationScheduler` exists, and
`interest.withholding.remitted.v1` is genuinely emitted. The "a filing with nothing to file is moot"
caveat above no longer applies — there is real data to file.

Shipped:
- `FilingPeriod` — the §38d month, with the statutory deadline derived as the last day of the
  following month, the same rule `WithholdingRemittancePolicy` uses for the payment, so the return
  and the cash leg cannot describe different months.
- `WithholdingRemittedConsumer` — a **second consumer group** on
  `openbank.interest.accrual.event`, no change to interest-service's contract. Event-type filtering
  is on the `ce-type` **header**, because the interest publisher does not duplicate `eventType` into
  the payload (ADR-0050 N3); a payload-field filter would have matched nothing, forever, silently.
- Idempotency keyed on the producer's remittance id (it is the primary key). Kafka is at-least-once
  and this is a second group, so redelivery is routine — counting a batch twice would overstate the
  tax on a statutory return.
- `TaxFilingRecord` — `OPEN → ASSEMBLED → FILED`, four-eyes at the filing (the assembler may not
  also record it as filed), a mandatory submission reference, and a `dueDate`/`overdue` view. A late
  return is a compliance failure that throws nowhere else; `GET /api/v1/tax/filings/overdue` is the
  alertable set.
- Assembly refuses a month that has not ended (via the ADR-0207 `AccountingClock` — a filing
  deadline is an accounting date) and refuses a mixed-currency period rather than summing across
  currencies, since §38d withholding is CZK-only (ADR-0033 §E).

**Not built: the EPO XML rendering.** `EpoRendererPort` is bound to `UnavailableEpoRenderer`, which
reports `available = false` and throws if called. The GFŘ EPO XSD for *Vyúčtování daně vybírané
srážkou* is a specific published schema; a guess at it would produce a file that passes every gate
in this repo and is wrong at the finanční úřad. A wrong tax return is worse than none — a missing
filing is a visible gap, a wrong one is a filed falsehood. `GET /export-capability` states this
rather than letting a caller infer a capability. The rest of the service is useful without it: the
aggregation is the part that had no owner, and an operator can read the assembled totals and submit
through the portal today.

**Also not built:** the annual reconciliation this ADR anticipates via ADR-0038's
`WithholdingTaxStatus.RECONCILED`, and the GitOps deployment (services graduate to a component in a
separate PR here, as ADR-0181/0193 phase 1 → phase 2 did).

## Alternatives considered

- **finrep-service owns it.** Rejected. ADR-0097 pins `finrep-service` as a **prudential-supervisory**
  capability whose *source is the attested statutory close* (ADR-0096), rendering **EBA XBRL/DPM to ČNB**.
  §38d is a **tax return to the finanční úřad** in **GFŘ/EPO XML**, sourced from withholding-remittance
  events — a different regulator, taxonomy, transport, and source of truth. Hosting it in finrep would
  violate finrep's "source = attested close" invariant and fuse two unrelated regulatory contexts; the
  only thing shared is the abstract "generate a regulatory XML + transmit" pattern, nothing concrete.
- **interest-service self-owns the filing.** Rejected. It already correctly owns withholding *calculation*
  and the *cash-leg* remittance. Adding statutory return assembly + EPO transport would fold a
  regulatory-reporting concern into the accrual/capitalization accounting context, and ADR-0038 already
  deliberately deferred the filing "downstream" rather than keeping it in interest-service. Keeping the
  consumer boundary (interest-service emits the event; a reporting service files) preserves separation of
  concerns and lets the filing cadence evolve independently of the money movement.
- **A new microservice per §38d only, no broader tax context.** Considered and folded in: the service is
  scoped as `tax-reporting` (statutory tax filings) rather than `withholding-38d` specifically, so future
  filings (e.g. §38d annual reconciliation, other srážková daň statements) have a home without a second
  new service — the same "revisit if the filing transport grows into its own surface" trigger ADR-0038's
  own Alternatives named when it rejected a tax-reporting service *for v1 of the cash leg only*.

## Consequences

**Positive**
- Closes the ADR-0038 §Decision.3 deferral with a named owner; #999's blocking design question is answered.
- Keeps `finrep-service`'s ADR-0097 source-invariant intact and the tax vs prudential contexts separate.
- The filing service consumes an event that already exists; interest-service's contract is unchanged.
- Gives future statutory tax filings a home without standing up another service.

**Negative**
- A new service to build, deploy, monitor and threat-model — real cost for one (initially) obligation.
- EPO has no real-time API, so v1 is semi-automated (generate + operator submit); full automation is later.

**Neutral**
- The service moves **no cash** (the cash leg stays in interest-service's settlement consumer), so it is
  a read/derive-and-report service, off the `money_path_services` gate — standard single-approval review,
  unlike interest-service's own money-path changes.
- `capitalizeAll` actually running (the trigger that produces withheld amounts to file) is a separate,
  independently-shipped interest-service change; this ADR does not depend on the order of the two.

## Compliance impact

- PCI DSS: not applicable — no cardholder data.
- DORA:    not applicable — no change to ICT risk posture or critical-service classification.
- GDPR:    not applicable — the §38d statement carries aggregate withheld amounts, not customer PII
  beyond what statutory tax filing inherently requires; PII handling stays under ADR-0118.
- PSD2:    not applicable — not a payment-services obligation.
- CNB:     not applicable — §38d is a finanční-úřad (tax) filing, not a ČNB supervisory return; the ČNB
  prudential returns are ADR-0097's scope, deliberately kept separate here.

## References

- ADR-0038 — Withholding-tax remittance and lifecycle (the deferral this ADR closes).
- ADR-0033 — Withholding tax at interest capitalization.
- ADR-0097 — Supervisory / prudential returns (FINREP/COREP) — why the tax filing does *not* belong there.
- ADR-0096 — Entity-level statutory accounting close (finrep's source of truth).
- Issue #999 — interest-service: withholding tax assembled but never filed to the finanční úřad.
- §38d zákona č. 586/1992 Sb., o daních z příjmů — daň vybíraná srážkou podle zvláštní sazby daně.
