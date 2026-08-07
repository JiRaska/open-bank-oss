---
date: 2026-08-03
decision-status: proposed
delivery-status: planned
authors: [Jiri Raska]
supersedes: []
superseded-by: []
delivery-repos: []
tags: [notifications, kafka, api-contract, privacy-gdpr]
summary: "Notification-service will emit a versioned delivery-outcome event correlated by a producer-supplied correlation id, so campaign-service can tell an accepted handoff from a delivered, suppressed or failed message."
---

# ADR-0239 — Delivery-outcome events for notification requests

## Context

Campaign-service's send log records `SendOutcome.SENT` at the moment notification-service accepts
a request onto `openbank.notification.requests`. That is a handoff acknowledgement, not a delivery
— and the two routinely diverge on the notification side for reasons that are entirely normal
(issue #3663):

- the ADR-0198 D4 marketing consent gate suppresses the send (`SUPPRESSED`),
- the party has no resolvable e-mail address or the fail-closed path fires (`FAILED`, #3662),
- the mailer refuses the message or it bounces (`FAILED` / `BOUNCED`).

In each case the campaign console still shows SENT and the notification row that disagrees is never
read back. The suppression trail is what the marketing-consent compliance claims rest on
(ADR-0198), and the campaign funnel is read as a measurement — so the quiet side being the only
correct one is a compliance-evidence defect, not a cosmetic one. #3581 fixed the two shipping
defects (a party UUID as the SMTP envelope, a refused handoff leaving no row) and deliberately left
this design question open, because the correlation half is a shared contract that outlives the one
consumer that needs it today.

The request payload today is `NotificationRequest(partyId, channel, template, recipient,
variables)` — nothing in it identifies the campaign, the journey step, or the send-log row. And
delivery is at-least-once: a redelivery persists a fresh notification row, so any consumer of
outcomes must tolerate more than one outcome per attempt.

## Decision

**D1 — Correlation by producer-supplied id.** We will add an optional `correlationId` to
`NotificationRequest` (nullable, additive, backward-compatible per ADR-0006). A producer that
needs outcome correlation sets it to an identifier it owns — campaign-service uses the send-log
row's `SendRecord.id`. Notification-service propagates it unchanged onto the persisted notification
row's metadata and onto every outcome event for that request. Producers that do not care (account,
onboarding, …) omit it; nothing changes for them.

**D2 — A versioned outcome event.** Notification-service will emit
`openbank.notification.outcomes.v1` via its transactional outbox (ADR-0003, ADR-0050):
`{notificationId, correlationId?, partyId, channel, template, outcome, reason?, occurredAt}` with
`outcome ∈ {SENT, SUPPRESSED, FAILED, BOUNCED}` mirroring the persisted status vocabulary, and
`reason` carrying the distinguishable suppression/failure causes already audited today
(`no_active_consent`, `consent_check_unavailable`, unresolvable recipient, mailer refusal). The
contract is declared in AsyncAPI (ADR-0006) and evolves additively only. The event is emitted for
every terminal status transition, not only for correlated requests — anything, not only campaign,
may consume it.

**D3 — Campaign keeps SENT, gains a separate delivery state.** We will not rename
`SendOutcome.SENT` — it is an accurate record of "accepted onto the topic" and renaming ripples
through the admin-ui funnel and the campaign `openapi.yaml` for no new information. Instead the
send-log row gains a `deliveryStatus` (`PENDING` while no outcome arrived, then `CONFIRMED`,
`SUPPRESSED`, `FAILED`), populated by a campaign-side consumer of D2 keyed on `correlationId`.
An outcome with an unknown or absent `correlationId` is consumed and logged but updates no row.
This is an additive field on the send-log API (minor bump per ADR-0048) plus an admin-ui funnel
column.

**D4 — Monotonic, duplicate-tolerant transitions.** Delivery status moves `PENDING →
CONFIRMED | SUPPRESSED | FAILED`; the first terminal outcome wins and later outcomes for the same
`correlationId` are recorded in the row's history but do not move the state — except `BOUNCED`
after `CONFIRMED`, which is a genuine later refinement (SMTP accept, then a bounce) and moves
`CONFIRMED → FAILED` with reason `bounced`. Because a redelivered request persists a fresh
notification row, each outcome event carries its own `notificationId`, so duplicates are
detectable rather than ambiguous.

## Alternatives considered

- **Heuristic correlation** — the outcome event carries `(partyId, template, occurredAt)` and
  campaign finds its own row by matching. Rejected: two campaigns can send the same template to
  the same party within the same minute, and journey re-sends make the match ambiguous exactly
  when the funnel matters. A correlation id costs one nullable field and removes the guesswork.
- **Rename `SENT` to `ACCEPTED`** in the campaign vocabulary. Rejected: a breaking contract change
  (admin-ui `OUTCOMES`, campaign openapi) that buys a label, not the missing delivery signal; the
  separate `deliveryStatus` conveys both halves without invalidating existing rows.
- **Synchronous read-back** — campaign queries notification-service for the status of its sends
  instead of consuming events. Rejected: couples the two services' availability on a read path,
  duplicates a query API per consumer, and does not scale to the other outcome consumers
  (analytics, operator messaging) that the same event serves for free.

## Consequences

**Positive**
- The campaign funnel and the suppression/consent trail describe the same reality; a marketing
  dashboard can no longer read a consent-gated suppression as a delivered send.
- The outcome event is a shared, versioned contract — analytics and operator messaging
  (ADR-0176) get delivery truth without per-consumer query APIs.
- Fully additive: request payload, event topic, send-log field — no existing producer, consumer,
  or row is invalidated.

**Negative**
- Two services now own halves of one truth; a lag or gap on the outcomes topic shows as sends
  stuck in `PENDING`, which needs its own staleness signal (the ADR-0237 liveness-heartbeat
  pattern applies).
- `correlationId` is trust-by-convention: nothing forces a producer to set it, so the funnel
  degrades silently for a producer that forgets — mitigated by making it the only way to get
  outcomes at all.

**Neutral**
- Notification-service gains an outbox entity and one more topic; campaign-service gains a
  consumer. Both follow existing fleet patterns (outbox, idempotent consumer).

## Compliance impact

- PCI DSS: not applicable — no card data on the notification path.
- DORA: not applicable — no new ICT dependency; the change removes a silent-divergence failure mode between two existing services.
- GDPR: supports the consent-evidence trail — a suppressed-for-no-consent send becomes visible to the campaign side that claimed the send, keeping the ADR-0198 marketing-consent record consistent end to end (Art. 6(1)(a) basis per ADR-0198's own mapping).
- PSD2: not applicable — marketing/notification traffic, no payment initiation.
- CNB: not applicable — no prudential or conduct obligation engages a marketing delivery funnel directly.

## References

- Issue #3663 (this decision), #3581 (the shipping fixes that scoped it out), #3662 (fail-closed recipient resolution)
- ADR-0003 (transactional outbox), ADR-0006 (AsyncAPI), ADR-0048 (version axes), ADR-0050 (regulatory-grade outbox dispatch)
- ADR-0176 (operator-initiated messaging), ADR-0198 (marketing consent), ADR-0200 (campaign journeys), ADR-0237 (liveness heartbeat)
