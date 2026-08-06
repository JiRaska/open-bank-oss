---
date: 2026-08-03
decision-status: proposed
delivery-status: planned
authors: [Jiri Raska]
supersedes: []
superseded-by: []
delivery-repos: []
tags: [notifications, analytics, privacy-gdpr]
summary: "Campaign conversion is a versioned product-domain event correlated to the party within an attribution window — never a click, never a send outcome; the goal becomes typed, and goalReached becomes an honestly-sourced journey stop."
---

# ADR-0240 — Campaign conversion attribution: a product event correlated to the goal

## Context

ADR-0200 D1 gives every campaign a `goal` — as free text. Nothing in the estate can observe it:
#3585's audit found no `conversion`/`goalReached` producer anywhere, so the campaign funnel
measures sends, not outcomes, and the remaining #3585 sequence (branch conditions, then ADR-0220
surfaces with a measurable loop) has nothing to branch or measure on. The same issue names the
trap for the fix: adding a `goalReached()` signal that nothing ever emits would be "the same
class of defect as the SERVICE principal type and the IN_APP stub channel" — a capability that
reads as real and delivers nothing (ADR-0220 D5: *an inauthentic placeholder is worse than a
missing feature*).

Two adjacent signals already exist and are deliberately NOT this one: ADR-0239's delivery-outcome
events (did notification-service deliver/suppress/fail the message — a handoff fact, not a
customer outcome) and ADR-0220 D2's in-app `impression | click | dismiss` loop (attention on a
surface, not a business result). What is missing is the third kind: **did the customer
subsequently do the thing the campaign exists to promote.**

## Decision

**D1 — A conversion is a versioned product-domain event correlated to the party, never a click
and never a send outcome.** The catalogue of convertible events is exactly the set of product
events the platform already emits — e.g. `AccountOpened`, `CardIssued`, `SavingsGoalSet` — and a
goal is only expressible as a catalogue member. A campaign's `goal` stops being free text and
becomes a typed `goalEventType` reference plus a display name; existing free-text goals map onto
the catalogue at migration, and an unmappable one becomes `NONE` (the campaign measures sends
only) rather than a guessed mapping.

**D2 — Attribution is last-touch within a bounded window, decided once.** A conversion counts
for a campaign when the product event occurs for the same party (a) after that party's first
counted contact in the campaign (SENT, per ADR-0219's send log) and (b) within the attribution
window (default 30 days, platform config). If several campaigns touched the party inside the
window, the *most recent counted contact* wins — last-touch, because it is deterministic,
explainable to a conduct reviewer, and immune to credit-splitting disputes. Attribution is
associative, not causal, and the console must say so (see D5).

**D3 — The signal is a real, consumable event.** campaign-service consumes the product-event
stream, and on a match emits `CampaignConversionRecorded` via its transactional outbox (same
pattern as every other domain event in the estate). Consumers: the ADR-0221 dashboards (funnel
with a real terminal), the ADR-0140 feature catalogue (NBA training labels — conversion is the
label ADR-0201's ranking needs), and the analytics silver layer (ADR-0210). The Temporal journey
consumes it too: **`goalReached` becomes a journey stop condition** — terminating the journey the
way `consentRevoked` does, but now honestly sourced — which is what unblocks #3585's branch
conditions.

**D4 — What is not a conversion, by construction.** (1) Email opens/clicks — no tracking pixels;
attention is not outcome and the privacy cost is real. (2) Delivery outcomes (ADR-0239) — a
handoff fact. (3) Manually recorded conversions (an operator or RM "marking" a party as
converted) — fabricatable by construction, exactly the inauthentic signal #3585 forbids. (4)
Anything inferred by a model. Every entry in the catalogue must be a domain event emitted by the
owning service; adding one is a catalogue PR, not a config edit.

**D5 — Attribution never gates contact.** Conversion recording is read-side measurement only:
it is never an input to the ContactPolicyGate (ADR-0219), never a targeting input without a
future ADR, and attribution rows are wiped by `PARTY_ERASED` at event speed. A consent
revocation mid-window stops further attribution for the party — measuring after an objection is
the same breach as contacting after one, just quieter.

## Alternatives considered

- **Click/open tracking as the conversion proxy.** Rejected: it measures attention, not outcome;
  tracking pixels carry a real privacy cost in a bank; and a "conversion" that means "opened the
  email" would inflate every dashboard while moving no business number — vanity metrics with a
  GDPR line item.
- **Correlation as a ClickHouse-only batch job.** Rejected as the *only* mechanism: the journey
  stop condition (D3) needs the signal at event speed, and ADR-0220 D1 already rules ClickHouse
  out of the app-open hot path for the same latency reason. ClickHouse stays the audit/backfill
  and reconciliation source; the online signal is the event stream.
- **Keep goal as free text + operator-recorded outcomes.** Rejected: free text cannot be
  correlated, and manual marking is fabricatable — together they reproduce the defect this ADR
  exists to avoid, one level down.

## Consequences

**Positive**
- Funnels measure outcomes, not just sends; ADR-0221's dashboards and #3585's branch conditions
  become buildable on honest data.
- NBA (ADR-0201) gets real training labels from D3's event instead of proxy targets.
- The catalogue discipline keeps "conversion" a reviewed, versioned concept — adding a
  convertible event is a visible, auditable change.

**Negative**
- Attribution is last-touch and associative: it will over-credit the most recent campaign and
  cannot prove causation. Accepted and surfaced in the console rather than hidden in a model.
- Catalogue coverage is bounded by what product services actually emit today; a goal without an
  emitted event is `NONE` until the owning service emits one — coverage grows per service, not by
  declaration.

**Neutral**
- Free-text goals migrate once; the display name keeps the prose, the type carries the semantics.
- ADR-0239's delivery outcomes and this ADR's conversions are separate facts about the same
  journey — the console shows both, labelled ("delivered" vs "converted"), never conflated.

## Compliance impact

- PCI DSS: not applicable — no cardholder data in the attribution path.
- DORA: no new third party; the signal rides the existing event backbone.
- GDPR: attribution rows are personal data (partyId + outcome) — purpose-limited to campaign
  measurement (D5), erased by `PARTY_ERASED` at event speed, and stopped at consent revocation.
  No new processing purpose is created.
- PSD2 / SCA: not applicable.
- AML / 5AMLD: not applicable.
- CNB: measurement only; the contact-side rules (480/2004 §7) are untouched and stay in
  ADR-0219's gate.

## References

- [#3585](https://github.com/JiRaska/open-bank-oss/issues/3585) — the gap audit and the sequence
  this ADR is step 2 of (stop conditions shipped in #3635).
- [ADR-0200](0200-campaign-journeys-as-temporal-workflows-with-consent-gated-delivery.md) — D1's
  campaign definition this types; the journey `goalReached` now stops.
- [ADR-0239](0239-delivery-outcome-events-for-notification-requests.md) — delivery outcomes; a
  different fact, deliberately not conflated.
- [ADR-0220](0220-in-app-engagement-surfaces-gamification-and-pre-approved-offers.md) — D2's
  in-app loop (attention) and D5's anti-placeholder rule this design follows.
- [ADR-0201](0201-customer-segmentation-and-next-best-action-on-the-ml-decisioning-platform.md) —
  NBA, the label consumer.
- [ADR-0210](0210-customer-360-as-a-query-over-the-analytics-silver-layer.md) and
  [ADR-0221](0221-campaign-studio-the-campaign-authoring-operator-experience.md) — the read-side
  consumers.
- [ADR-0219](0219-platform-contact-policy-gate-contact-classes-durable-counters-suppression.md) —
  the gate attribution must never feed (D5).
