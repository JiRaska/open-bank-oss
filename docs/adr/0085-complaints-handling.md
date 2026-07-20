---
date: 2026-06-12
decision-status: accepted
delivery-status: partial
authors: [Jiří Raška]
supersedes: []
superseded-by: []
delivery-repos: []
tags: [disputes, compliance, regulatory-reporting]
summary: "Complaints handling extends dispute-service into a two-aggregate bounded context rather than a new service, adding a regulatory taxonomy, a statutory deadline clock as domain logic, and a register for CNB reporting."
---

# Complaints handling — regulatory complaints as a first-class process

**Delivery note (updated 2026-06-30):**
- **Model and deadline logic** — ✅ Architected: `Complaint` aggregate + taxonomy (PAYMENT_SERVICE/FEES/etc.), statutory deadline clock (15/35 BD), `BusinessCalendar` domain logic, breach derivation, and `DomainMetrics` (open gauge, breach counters) all designed.
- **Queue and reporting** — ⬜ Pending: admin-UI complaints queue, quarterly ČNB reporting endpoint, arbiter-referral step, and register/analytics view not yet implemented.

## Context

A bank is legally required to operate a **complaints-handling process** — not a support inbox,
a regulated procedure with deadlines, statutory reporting and an escalation path to the
financial arbiter/ombudsman:

- **PSD2 Art. 101** + national transpositions: payment-service complaints answered within
  **15 business days** (extendable to 35 in exceptional cases, with an interim reply).
- **EBA/ESMA Joint Committee Guidelines on complaints-handling (JC 2018 35)**: a documented
  complaints-management policy, a complaints-management *function*, a register of complaints,
  root-cause analysis feeding product/process fixes, and statutory reporting to the regulator.
- **ČNB** expects a published complaints procedure (reklamační řád) and arbiter referral
  information in every final reply.

Today the platform has exactly one adjacent asset: **`openbank-dispute-service`** — payment
dispute lifecycle (open → evidence → escalate/withdraw → resolve, with timeline). It is the
right skeleton but the wrong scope: a *dispute* is one complaint category (contested payment),
while the regulatory perimeter covers any expression of dissatisfaction — fees, service
quality, onboarding/KYC outcomes, channel availability, data requests handled badly. There is
no deadline clock, no regulatory taxonomy, no register/reporting view, no arbiter-referral
step, and the admin-UI has no complaints queue. The compliance scorecard would honestly say:
**no control**.

## Decision

Extend **`openbank-dispute-service`** into the complaints bounded context — *one service, two
aggregates* (`Dispute`, `Complaint`) — rather than standing up a new service. Disputes keep
their specialized payment lifecycle; complaints add the regulatory wrapper. Concretely:

### 1. Complaint aggregate & taxonomy

`POST /api/v1/complaints` accepting a regulatory taxonomy (`PAYMENT_SERVICE`, `FEES`,
`ACCOUNT_SERVICE`, `LENDING`, `CONDUCT`, `DATA_PROTECTION`, `OTHER`), channel
(`APP`, `BRANCH`, `EMAIL`, `ARBITER`), free-text description and optional links to an
account/transaction/dispute. A payment complaint that is factually a contested transaction
*spawns* a linked `Dispute` and the two resolve together.

### 2. The deadline clock is domain logic, not ops discipline

Each complaint carries a statutory **due date computed at intake** (PSD2: 15 business days;
extension to 35 requires a recorded reason + interim-reply event). Approaching/breached
deadlines are DomainMetrics (`openbank_complaints_open`, `openbank_complaints_due_breach_total`,
age histograms) with a Grafana panel + alert — a breached statutory deadline is an operational
incident, not a backlog item.

### 3. Register & statutory reporting

The complaints register (a queryable projection: category × outcome × root cause × timing) is
the data source for the JC-guidelines statutory report, produced through the analytics layer
(ADR-0022/0023) like other regulatory reporting — derived, never hand-assembled.

### 4. Workflow & four-eyes

Operator handling reuses the cockpit pattern (ADR-0068): a complaints queue in admin-UI with
case detail, evidence, interim/final replies. **Final replies that grant financial redress
follow the four-eyes rule** (`rules.yaml: four_eyes`) — redress is money movement. Every final
reply must include the arbiter-referral information (a template concern, enforced by a
contract test on the reply payload).

### 5. Root-cause feedback loop

Closing a complaint requires a root-cause code; monthly aggregation lands in the governance
follow-up backlog (ADR-0052 issues) when a root cause crosses a threshold — the JC guidelines
explicitly require complaints to feed remediation, not just answers.

### Out of scope (explicitly)

Customer-app UI for filing complaints (follows the customer-app roadmap), the lending-specific
complaint regime, and FX/MiFID-style investment complaints (no investment products exist).

## Alternatives considered

- **New `openbank-complaint-service`.** Rejected: 80 % of the machinery (case lifecycle,
  evidence, timeline, escalation) already exists in dispute-service; a second service would
  duplicate it and force a cross-service link for the most common complaint type (payment).
  If complaint volume or team boundaries ever demand a split, the aggregate seam makes it
  mechanical.
- **Track complaints in the onboarding cockpit / a generic ticketing tool.** Rejected: the
  deadline clock, taxonomy, register and reporting are *domain* requirements with audit and
  statutory consequences; a ticket tool hides them in labels and makes the register
  hand-assembled (violates the derived-data house rule, ADR-0029/0074).
- **Disputes absorbed into complaints (one aggregate).** Rejected: dispute resolution has
  payment-specific states (chargeback-like evidence cycles, provisional credit decisions)
  that would bloat a generic complaint state machine.

## Consequences

- `openbank-dispute-service` grows a second aggregate, an API extension (OpenAPI minor bump on
  its own contract axis, ADR-0048), Flyway migrations, DomainMetrics and an admin-UI queue.
- A statutory clock starts existing in the domain — alerting on it makes the regulatory
  deadline visible to ops *before* it breaches, which is the entire point.
- `complaints-handling` enters `compliance-controls.yaml` as `planned`; flips to `partial`
  when the aggregate + queue land, `enforced` when statutory reporting is derived end-to-end.
- Dispute-service is not money-path today; complaints with redress put its four-eyes surface
  under the same review regime as cockpit actions (approval rules unchanged for the service
  itself until it executes money movement directly — redress executes through existing
  payment/ledger services).

## Compliance impact

- **PSD2 Art. 101** — complaints procedure + 15-business-day reply: the deadline clock and
  queue are the control.
- **EBA/ESMA JC 2018 35** — complaints-management policy, register, root-cause analysis,
  statutory reporting: the register projection + analytics report are the control.
- **DORA Art. 17** (major ICT-incident reporting obligation; Art. 20 harmonises formats) — complaint spikes are an incident-detection signal; the register feeds post-incident
  reporting.
- **GDPR** — complaints contain PII by nature; standard per-service data classification and
  retention applies (`data-classification` control).

## References

- ADR-0022 / ADR-0023 — analytics layer; regulatory reporting pipeline
- ADR-0048 — API contract version axis (dispute-service OpenAPI extension)
- ADR-0052 — issues as the actionable backlog (root-cause follow-ups)
- ADR-0068 — operations cockpit pattern (queue + four-eyes)
- ADR-0084 — fraud detection (sibling customer-protection context; fraud cases and complaints
  cross-reference each other)
- EBA/ESMA Joint Committee Guidelines on complaints-handling, JC 2018 35
