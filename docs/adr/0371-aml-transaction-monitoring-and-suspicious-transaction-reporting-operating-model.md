---
date: 2026-11-11
decision-status: proposed
delivery-status: planned
authors: [jiri.raska]
supersedes: []
superseded-by: []
delivery-repos: []
tags: [aml-sanctions, regulatory-reporting, compliance]
summary: "aml-service monitors booked money movement after posting with ADR-0354 packs over bucketed per-party counts, raises cases decided four-eyes, and owns a suspicious-transaction report that never reaches a customer surface."
---

# ADR-0371 — AML transaction monitoring and suspicious-transaction reporting operating model

## Context

ADR-0032 decided the synchronous sanctions gate in payment execution. ADR-0354 decided how
AML and fraud *strategies* are authored, approved, signed and activated. ADR-0367 decided
that every regulatory return is a declared spec and lists the suspicious-transaction report
as an `event` return. ADR-0304 decided the investigation lenses. None of them decides the
thing those four assume exists: what aml-service monitors, from which source, what an alert
becomes, who may decide it, and how a report leaves the bank.

Measured on `origin/main` f0216a5686:

- `openbank-aml-service/src/main/kotlin` holds 23 files: the `AmlCase` aggregate, onboarding
  screening, one `PartyEventConsumer`, an outbox. `ScreeningType.TRANSACTION_MONITORING`
  exists as an enum value with no producer.
- The service consumes one topic, `openbank.party.events`. No money-movement event reaches it.
- `docs/compliance/regulatory-obligations.md` rates the suspicious-transaction report
  (zákon č. 253/2008 Sb. § 18) as `bff-only`: a catalogue row computed in admin-ui.
- Issue #13775 delivers the pack kernel with one synthetic AML scenario in SHADOW. Issue
  #1035 asks for real consumers of sanctions and SWIFT events. Neither states a monitoring
  scope, an alert lifecycle or a reporting workflow.
- ADR-0268 records that the AML four-eyes control is a state machine plus a caller-supplied
  `decidedBy`, not a maker-checker gate.

The 2026-11-11 project audit ranked this the largest functional gap between the platform and
a bank that may operate. The owner asked for the plan to be completed and built.

## Decision

We will make aml-service the single owner of post-booking transaction monitoring and of the
suspicious-transaction report.

1. **Source: booked movement, after the fact.** Monitoring consumes events for money that
   has been booked. It never sits in a payment hot path; the synchronous control stays the
   ADR-0032 gate. The ledger event establishes that a movement is booked (ADR-0039); party,
   counterparty and rail come from the originating domain event. Slice 1 fixes the exact
   topic set by reading real payloads, and adds consumers to aml-service rather than a second
   ingestion platform (#1035). Every consumer is idempotent on the source event id and wires
   a dead-letter topic in the same change as its rethrow.

2. **Features: bucketed per-party counts, per currency.** aml-service persists rolling
   counters keyed by party, currency, direction, rail, window and amount band. Amounts are
   never converted or summed across currencies. Bands and windows are pack data. This shape
   is chosen because the ADR-0354 kernel evaluates `COUNT_AT_LEAST` today: structuring is
   "N movements in the band just under a threshold within a window", expressible without a
   new interpreter operation. Sum-based scenarios wait for the kernel operation in #13775.

3. **Scenarios are ADR-0354 packs.** No scenario logic, threshold, band or typology code is
   written in Kotlin. The first activated pack carries at least: structuring below a
   threshold, rapid pass-through, activity on a long-inactive account, movement volume above
   the party's declared turnover band, and a counterparty in a listed jurisdiction. The pack
   pins its vocabulary revision; an unknown code fails evaluation, it does not default.

4. **Alert to case.** A scenario hit opens an `AmlCase` of type `TRANSACTION_MONITORING`
   with the pack hash, scenario code and the counter snapshot as evidence. One open case
   exists per party, scenario and window; a repeat hit appends evidence. A missing
   evaluation is recorded as its own outcome, never as a clear.

5. **Decisions are four-eyes on the authenticated principal.** `decidedBy` is taken from the
   authenticated caller and never from a request body. Clearing or reporting a monitoring
   case needs a second, different approver through the fleet approval kernel. The sandbox
   `openbank.aml.auto-clear` flag (ADR-0268) does not apply to monitoring cases.

6. **The report is an aggregate, not a view.** aml-service owns `SuspiciousTransactionReport`
   with states DRAFT, APPROVED, FILED and WITHDRAWN. It links the cases and movements it
   rests on, is approved four-eyes, and records the filing reference and instant. The return
   is declared as the `fau-suspicious-transaction` spec (ADR-0367) and computed in the
   backend; the admin-ui computation is removed. Until the authority's submission channel is
   verified, FILED records a filing performed outside the system by a named officer. The
   system does not claim electronic submission it does not perform.

7. **No tipping-off, by construction.** The existence, state and reasoning of a monitoring
   case or report never appear in a customer-facing API, notification, statement or edge
   response (zákon č. 253/2008 Sb. § 38). Events on `openbank.aml.events` about a report
   carry identifiers and state only. Read access needs a dedicated compliance role declared
   in `rules.yaml`; operator and customer roles are denied by policy, and a gate keeps the
   report types out of customer-edge and notification contracts.

8. **Time to report is measured.** The service exposes the age of the oldest undecided
   monitoring case and of the oldest APPROVED-not-FILED report, plus a workflow-liveness
   gauge for the evaluation job. The alert is on the state "evaluating nothing while money
   moves", not on an error rate.

9. **Rollout in three states.** SHADOW records hits without opening cases. ACTIVE opens
   cases. Each move is an ADR-0354 activation epoch with independent approval. A party-level
   hold requested by a report reuses the hold and release contract of ADR-0247 and ships
   after it; it is not part of slices 1 to 3.

Delivery slices: (1) consumers and counters, (2) pack evaluation in SHADOW with cases behind
a flag, (3) four-eyes decisions and the report aggregate with the return spec, (4) ACTIVE.

## Alternatives considered

- **Run AML monitoring inside fraud-service.** It already builds behavioural aggregates from
  Kafka (ADR-0084), so intake would be free. Rejected: ADR-0354 keeps fraud verdicts and AML
  typologies under different authority, and the confidentiality rule in point 7 is far easier
  to hold when the data never enters a service that answers the payment path.
- **Evaluate synchronously in the payment path.** Would allow blocking before booking.
  Rejected: monitoring reasons over windows of history, a slow or failed evaluation would
  have to fail open or stop payments, and the only lawful pre-booking stop here is the
  sanctions gate.
- **Stream processing engine with windowed sums.** Expressive, and the standard shape for
  this problem at scale. Rejected for now: a new runtime and state store for a platform with
  no production traffic, and a second evaluator beside the ADR-0354 interpreter. Revisit when
  measured volume exceeds what persisted counters handle.
- **Keep the report as an admin-ui computation.** Smallest change. Rejected: no state, no
  approval, no filing evidence, and ADR-0367 already rules out BFF-computed returns.

## Consequences

**Positive**
- The platform gains the monitoring and reporting duty it currently lacks, on mechanisms it
  already owns: outbox, approval kernel, pack lifecycle, return specs.
- Confidentiality is a contract property checked by a gate, not a convention.

**Negative**
- Count-only scenarios are weaker than sum-based ones until the kernel gains the operation.
- Synthetic sandbox data cannot show that scenarios detect real laundering; effectiveness
  needs labelled outcomes that do not exist yet.
- aml-service gains persistent per-party state and a retention duty that overrides erasure
  (ADR-0118). The retention period is entered in `governance.yaml` after it is verified
  against the statute; this ADR does not assert one.

**Neutral**
- aml-service is not in `rules.yaml: money_path_services` and this ADR does not add it: it
  books nothing. A threat model for aml-service does not exist and is required before ACTIVE.
- Planned gate `aml-no-customer-surface`: fails when a report or monitoring-case type appears
  in a customer-edge or notification contract. Enforced before slice 3 merges.

### Delivery check

Not implemented in this revision:

```bash
ls openbank-libs/governance/returns/fau-suspicious-transaction.yaml
grep -c 'TRANSACTION_MONITORING' openbank-aml-service/src/main/kotlin -r    # > 1: a producer exists
grep -n 'id: aml-no-customer-surface' -A4 .github/gates/gates.yaml | grep 'mode: enforced'
./gradlew :openbank-aml-service:test --tests '*TransactionMonitoringIT' --tests '*SuspiciousTransactionReportIT'
```

The ITs must drive real HTTP and a real database and prove: a booked movement series opens
exactly one case; a replayed event opens none; a decision by the opener alone is refused; a
report cannot reach FILED without a second approver; a customer principal reading a report
is denied.

## Compliance impact

- PCI DSS: not applicable — counters hold party and amount-band data, no cardholder data.
- DORA: not applicable — no ICT third party or resilience control changes.
- GDPR: monitoring state is personal data kept under a legal duty; erasure is overridden as
  ADR-0118 already provides. Access is purpose-bound to the compliance role.
- PSD2: not applicable — no payment outcome, SCA step or exemption changes.
- CNB: zákon č. 253/2008 Sb. § 18 (report without undue delay) and § 38 (confidentiality)
  are the duties this ADR gives an owner and evidence. A gate or a metric is not an
  attestation of compliance.

## References

- ADR-0032, ADR-0118, ADR-0247, ADR-0268, ADR-0304, ADR-0353, ADR-0354, ADR-0367
- Issues #13775, #1035
- `docs/compliance/regulatory-obligations.md`, entry `fau-suspicious-transaction`
