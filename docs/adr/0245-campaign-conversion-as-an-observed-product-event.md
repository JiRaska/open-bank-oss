---
date: 2026-08-07
decision-status: proposed
delivery-status: planned
authors: [Jiri Raska]
supersedes: []
superseded-by: []
delivery-repos: []
tags: [analytics, kafka, privacy-gdpr, admin-ui]
summary: "A campaign goal becomes a machine-checkable rule over product events the platform already publishes, so conversion is observed rather than declared — no tracking pixel, no click telemetry, no invented signal."
---

# ADR-0245 — Campaign conversion as an observed product event

## Context

A campaign carries a `goal` (ADR-0200 D1). It is free text on the definition. Nothing in the
platform can tell whether it happened.

That absence is load-bearing, and it is the reason three separate pieces of work stop where they do:

- **Branch conditions** (ADR-0200 D1, built in #3585) can only name a delivery status —
  `IF_PREVIOUS_CONFIRMED` / `IF_PREVIOUS_NOT_CONFIRMED`. Their KDoc says why in as many words:
  *"no impression, click or conversion signal exists anywhere in the platform, and a condition
  nothing can ever make true is the 'inauthentic placeholder' ADR-0220 D5 refuses."*
- **In-app surfaces** (ADR-0220) declare that *"the feedback loop is the product"* (D2), and cannot
  be built honestly without a loop to close.
- **Campaign reporting** can show sends, suppressions and deliveries. It cannot answer the only
  question a marketer is asked afterwards: did it work.

Meanwhile the platform already publishes the facts a bank actually cares about. Real topics, in the
tree today: `openbank.accounts.account.created`, `openbank.cards.events`, `openbank.consent.events`,
`openbank.dispute.events`, `openbank.balance.events`. And campaign-service already consumes two Kafka
streams — `ConsentEventConsumer` and `NotificationOutcomeConsumer` — so a third consumer introduces
no new orchestration concept.

The force that decides this ADR is what we are **not** willing to build. The industry default is
click and open tracking: a pixel in the email, a redirect wrapper on every link, an SDK in the app.
That measures attention, needs a lawful basis of its own under GDPR, and — for an email open — is
mostly noise, because image proxies fetch pixels the recipient never saw. A bank does not need to
know that someone looked. It needs to know that someone opened the savings account.

## Decision

**We will define a campaign's conversion as a rule over product events the platform already
publishes, evaluated by campaign-service, and never as engagement telemetry.**

**D1 — A goal becomes a typed, closed `ConversionRule`, not free text.** A campaign definition gains
an optional `conversionRule`, drawn from a catalogue in domain code exactly as `SegmentCatalog` and
`TemplateCatalog` are (ADR-0201 D1, ADR-0176 D4). The first entries name events that exist today:
`ACCOUNT_OPENED` (`openbank.accounts.account.created`) and `CARD_ISSUED` (`openbank.cards.events`).
Adding a rule is a pull request against the catalogue, so every conversion definition is reviewed,
versioned and diffable — a marketer cannot invent one from a text box, and neither can an agent.
The existing free-text `goal` stays as the human sentence; it is documentation, and this ADR does not
promote it to a machine input.

**D2 — Conversion is attributed by enrolment, in an explicit window.** A `ConversionConsumer`
subscribes to the catalogued topics. For an event about a party, it looks for an ACTIVE enrolment of
that party in a campaign whose `conversionRule` matches, whose first send has occurred, and where the
event is within the rule's `attributionWindow` (a duration on the rule, not a global constant). A
match records one `CONVERTED` row in the send log per enrolment, idempotent on
`(campaignId, partyId)` — the same key that already makes double-enrolment impossible.

**D3 — Attribution is last-touch within the window, and the ADR says so out loud.** If two campaigns
both match, both record a conversion. We do **not** attempt multi-touch attribution or fractional
credit. A number that is honest about being "this party converted while enrolled here" is worth more
than a modelled one nobody can reconstruct, and the console will label it in those words rather than
as "campaigns caused N accounts".

**D4 — Conversion never gates delivery in the first slice.** The rule produces a *measurement*.
`IF_GOAL_REACHED` as a step condition, and stopping a journey on conversion, are deliberately out of
scope until D2 has run against real traffic — a condition wired to a signal nobody has yet watched
misfire is how a campaign silently stops mailing everyone. The follow-up is named here so it is not
mistaken for an oversight.

**D5 — No click, open or impression tracking, in this ADR or as a follow-up to it.** No tracking
pixel, no link-wrapping redirector, no per-message beacon. This is a decision, not an omission: such
telemetry needs its own lawful basis, is unreliable for opens by construction, and would let a
campaign infer behaviour a customer never consented to share. If a future ADR wants in-app
interaction signal, ADR-0220 D2's surface feedback is the sanctioned route, under its own consent.

**D6 — Honest dependency statement.** ADR-0220's surfaces and any personalised ranking depend on
this ADR; this ADR depends on nothing that does not exist. The two catalogued events are published
today, and campaign-service already runs two Kafka consumers.

## Alternatives considered

- **Click and open tracking (the industry default).** A pixel plus a redirect wrapper. Rejected on
  D5: it measures attention rather than outcome, opens are unreliable because image proxies fetch
  them, and it acquires personal behavioural data the platform has no need for and no basis to hold.
- **Let the marketer write a conversion query.** Maximum flexibility, and it recreates exactly what
  ADR-0201 D1 removed for segments: ad-hoc SQL against production, unreviewed, unversioned, and
  impossible to reason about after the fact.
- **Have crm-service own conversion.** Defensible — it is the customer-relationship domain. Rejected
  for the first slice because attribution needs the enrolment and its send log, both of which live in
  campaign-service; putting the rule elsewhere means shipping a cross-service read for the join. If
  conversion later serves more than campaigns, this is the natural place to revisit.
- **Do nothing and keep goals as prose.** Rejected: it is the current state, and it is what blocks
  ADR-0220 and leaves every campaign unmeasurable. Naming that cost is why this ADR exists.

## Consequences

**Positive**
- A campaign can be answered for. The send log gains an outcome that means an outcome.
- ADR-0220's feedback loop becomes buildable without fabricated signal.
- Conversion definitions are code: reviewed, versioned, and readable a year later.

**Negative**
- Attribution is last-touch and will overcount when campaigns overlap. Stated in D3 rather than
  modelled away.
- The catalogue starts with two rules, so most campaigns will have no conversion rule at all. That is
  visible emptiness, and it is preferable to a rule that matches nothing while looking configured.
- A third Kafka consumer is a third thing that can lag. Its failure mode is a missing conversion, not
  a wrong send — but the console must not render "0 conversions" and "conversion tracking is behind"
  as the same thing.

**Neutral**
- Where conversions are displayed (campaign detail, a portfolio view, both) is not decided here.
- Whether a conversion should also suppress further sends is deliberately deferred by D4.

## Compliance impact

- PCI DSS: not applicable — no cardholder data. `CARD_ISSUED` records that a card was issued to a
  party, never a PAN or any card detail.
- DORA: not applicable — no change to operational resilience, incident reporting or third-party risk.
  The consumer is internal and its failure degrades reporting, not service.
- GDPR: engaged. Conversion attribution links a product event to a marketing enrolment, which is
  profiling-adjacent, so the data minimisation argument is the decision itself — D5 refuses click,
  open and impression telemetry, and D1 keeps the observable set to a reviewed, closed catalogue of
  events the bank already processes to run the product. Retention follows the send log's existing
  policy; this ADR introduces no new personal-data category and no new store.
- PSD2: not applicable — no payment initiation, account information or SCA surface is touched.
- CNB: not applicable — no regulatory report consumes campaign conversion.

## References

- [ADR-0200](0200-campaign-journeys-as-temporal-workflows-with-consent-gated-delivery.md) — D1 defines
  a campaign as steps, delays, branch conditions and stop conditions; `goal` is free text.
- [ADR-0220](0220-in-app-engagement-surfaces-gamification-and-pre-approved-offers.md) — D2 "the
  feedback loop is the product"; D5 "an inauthentic placeholder is worse than a missing feature".
- [ADR-0201](0201-customer-segmentation-and-next-best-action-on-the-ml-decisioning-platform.md) — segments as versioned code, the
  catalogue pattern this ADR reuses for conversion rules.
- [ADR-0239](0239-delivery-outcome-events-for-notification-requests.md) — delivery status, the only outcome
  signal that exists today and the reason branch conditions can name nothing richer.
- Issue #3585 — branch and stop conditions, and the gap this ADR closes.
