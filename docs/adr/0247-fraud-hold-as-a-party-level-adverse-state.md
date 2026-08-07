---
date: 2026-08-07
decision-status: proposed
delivery-status: planned
authors: [Jiri Raska]
supersedes: []
superseded-by: []
delivery-repos: []
tags: [fraud, privacy-gdpr, kafka]
summary: "A fraud hold is a deliberate, expiring decision about a party recorded by fraud-service, never a transaction verdict promoted into one — published as a hold/release pair so consumers can both apply and lift the exclusion."
---

# ADR-0247 — Fraud hold as a party-level adverse state

## Context

ADR-0220 D1 requires in-app promotional surfaces to exclude vulnerable customers, naming the
ADR-0200 D6 adverse-state set: fraud hold, arrears, dispute opened, erasure requested. ADR-0220 D3.5
extends the same exclusion to gamification targeting, and says the exclusion must react at event
speed because "a vulnerable-customer exclusion that lags by a snapshot interval is a compliance
defect, not a freshness metric".

`openbank-engagement-service` has the rule and the type: `EligibilityRule` refuses promotional
targeting for any party with a non-empty adverse-state set, and `AdverseState.FRAUD_HOLD` is one of
its four values. Nothing produces it. `ResolveSurfaceUseCase` constructs every party with an empty
set, and its own comment calls that a launch blocker.

Three of the four states have, or now have, a real source: `PARTY_ERASED` on
`openbank.party.events`; `loan.stage_changed` once it carries `partyId` (#4071); `dispute.opened`
(#4087). Issue #4070 records that survey. **Fraud hold is different in kind, and that is the reason
for this ADR rather than a fourth pull request.**

`openbank-fraud-service` is a synchronous scorer. `POST /score` returns `ALLOW | CHALLENGE | REVIEW
| DECLINE` for **one transaction**, writes an immutable row to `fraud_scores`, and stops. It has no
outgoing Kafka channel at all. Its table records `account_id` and `counterparty_id` — **not
`party_id`**; the service does not currently know which person a payment belongs to.
`GET /review-queue` is a read-only view over rows whose verdict was `REVIEW`.

So a party-level fraud hold does not exist anywhere in the platform. A repository-wide search for
`FRAUD_HOLD`, `BLOCKED`, `FROZEN` or `SUSPENDED` across `*/src/main/kotlin` returns nothing but the
consumer-side enum in engagement-service — a value with no producer. There is nothing to publish,
and the missing piece is a definition rather than plumbing.

The force that decides this ADR is what the state is *for*. It is not an authorisation control:
declining a payment is already `DECLINE`, decided per transaction at the moment it matters. This
state exists so that the bank stops *marketing* to someone whose account is under suspicion — a
conduct duty, adjacent to the arrears and dispute exclusions rather than to payment security.
Getting that scope wrong in either direction is harmful: too narrow and a customer under
investigation keeps receiving upsell; too broad and a routine review silently blocks a person from
content they are entitled to see, with no notice and no appeal.

## Decision

**We will define a fraud hold as a deliberate, expiring, party-scoped decision recorded by
`openbank-fraud-service`, published as a hold/release pair, and never inferred from a transaction
verdict.**

**D1 — A hold is a decision, never a derived verdict.** `REVIEW` on one payment does not place the
party under a hold. A hold is created explicitly, by an analyst acting on the review queue or by a
named automated rule, and it carries who or what created it and a reason code from a closed
catalogue in domain code. Promoting `REVIEW` automatically is rejected in Alternatives — a single
reviewed payment is a normal event for an ordinary customer, and treating it as a signal about the
person would make the exclusion fire constantly and mean nothing.

**D2 — Every hold expires, and the expiry is part of creating it.** A hold carries `expiresAt`, set
at creation with a bounded default (proposed: 30 days). A hold that outlives its investigation
excludes a customer from content indefinitely, and the failure is silent — it reads as a customer
who never engages, not as a control that was never lifted. Expiry is enforced by the producer: an
expired hold is released, with a release event, not merely ignored on read. Deriving expiry on the
consumer side would put the same clock in every consumer and let them disagree.

**D3 — Hold and release are both published, on a new `openbank.fraud.events` topic, through the
transactional outbox.** `fraud.hold_placed` and `fraud.hold_released`, each carrying `partyId`,
reason code and timestamps. Both halves are required and this is the half most easily forgotten: a
consumer that sees only the hold can apply an exclusion and can never lift it. The pair brackets the
window, exactly as `dispute.opened` / `dispute.resolved` do (#4087).

**D4 — Fraud-service learns `partyId`, and only for holds.** The scoring path stays as it is:
`fraud_scores` remains account-scoped and immutable, because it is the RTS Art. 18 fraud-rate
dataset and changing its grain is a separate decision with reporting consequences. The hold
aggregate is a new table with its own lifecycle. Where a hold originates from a scored transaction,
the party is resolved once, at hold creation, not on the scoring hot path.

**D5 — A hold suppresses marketing; it does not by itself restrict banking.** Consumers of this
event may withhold promotional content. Blocking payments, cards or access remains the business of
the services that own those decisions, driven by their own controls. Conflating the two would let a
marketing-suppression signal acquire the power to deny someone their money, which is a far larger
decision than this ADR is making, and would make the state impossible to widen later without a
review of everything reading it.

**D6 — The customer-facing consequences are deliberately out of scope here.** Whether and how a
person is told they are under a hold is a conduct and legal question (GDPR Art. 15 access, tipping-off
constraints where an investigation is AML-adjacent) that must not be settled as a side effect of an
engagement-service requirement. Until it is decided, the hold changes what the bank *sends*, never
what the customer *sees about themselves*.

## Alternatives considered

- **Promote the `REVIEW` verdict to a party state.** No new decision, no new table: treat any party
  with a recent `REVIEW` row as held. Rejected on two counts. `fraud_scores` has no `party_id`, so
  the mapping does not exist today; and more importantly a reviewed payment is a common, routine
  outcome, so the exclusion would fire for large numbers of ordinary customers and would carry no
  information. It would also make an operational threshold — how aggressively the scorer flags —
  silently determine who receives marketing.
- **Reuse an existing blocking concept from another service.** Rejected because there is none: the
  search across `*/src/main/kotlin` for `BLOCKED`, `FROZEN` and `SUSPENDED` finds no party-level
  state anywhere in the fleet. Card-level blocks exist as card state, which is a different subject
  (a card, not a person) and would under- and over-count in both directions.
- **Let engagement-service call fraud-service on render.** No new event, no new topic. Rejected on
  ADR-0220 D1's own design: eligibility is a pre-computed snapshot precisely so the app-open path is
  never a live query, and a synchronous call to a scorer on every render adds a dependency to the
  hot path that must fail open — turning a compliance control into something that disappears under
  load.
- **Leave `FRAUD_HOLD` unimplemented and ship the exclusion with three of four states.** Genuinely
  considered, and it remains the fallback if this ADR is not accepted. Rejected as the target state
  because a partially-wired exclusion reads as complete: the code, the type and the metric all say
  the adverse-state check ran. If it is taken as an interim step, the gap must be explicit in the
  snapshot and visible in monitoring, never implied by an empty set.

## Consequences

**Positive**
- ADR-0220 D1's exclusion can be honest about all four states, and D3.5's gamification exclusion
  inherits it without further work.
- The bank gains a reviewable answer to "who decided this customer was under suspicion, on what
  grounds, and when does that end" — today no such record exists in any form.
- The hold/release pair is reusable: any future consumer needing a vulnerability signal reads the
  same stream rather than inventing a private notion.

**Negative**
- A new stateful aggregate in a service that is currently stateless-by-design for its scoring path,
  with its own table, lifecycle and expiry sweep. That is real operational surface in a
  money-adjacent service.
- An expiry sweep is a scheduled job, and this repository has a documented history of schedulers
  that never ran (#2148/#2187). It must be built with that failure mode in mind and monitored on
  the count of releases, not on the job's own success.
- Someone must own hold creation operationally — an analyst workflow that does not exist yet. An
  event nobody ever emits is the same launch blocker in a new place.

**Neutral**
- `fraud_scores` and the RTS Art. 18 dataset are untouched; this adds a table beside them.
- The topic is new, so no consumer is affected until one subscribes.

## Compliance impact

- PCI DSS: not applicable — no cardholder data; the hold carries a party identifier and a reason
  code.
- DORA:    not applicable — no change to ICT risk posture; a new internal topic between existing
  services.
- GDPR:    engages Art. 5(1)(c) data minimisation in the choice to add `partyId` only to the hold
  aggregate rather than to the scoring dataset, and Art. 5(1)(e) storage limitation in D2's
  mandatory expiry. Whether the hold is disclosable to the data subject under Art. 15 is explicitly
  deferred by D6 and must be decided before the state carries any customer-visible consequence.
- PSD2:    not applicable — D5 keeps this out of authorisation and payment-blocking decisions, which
  remain where they are today.
- CNB:     not applicable — the RTS Art. 18 fraud-rate dataset is `fraud_scores`, which D4 leaves
  unchanged.

## References

- ADR-0220 — in-app engagement surfaces (D1 eligibility snapshot, D3.5 targeting exclusion, D5 the
  refusal to ship inauthentic placeholders)
- ADR-0200 D6 — the adverse-state set this is a member of
- ADR-0245 — campaign conversion as an observed product event; the same "define the concept before
  emitting it" shape
- Issue #4070 — survey of the four adverse states and their producers
- PR #4071 — `partyId` on `loan.stage_changed` (arrears)
- PR #4087 — `dispute.opened` (dispute)
