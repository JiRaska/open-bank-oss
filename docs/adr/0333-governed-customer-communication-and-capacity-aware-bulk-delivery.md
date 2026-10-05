---
date: 2026-10-05
decision-status: proposed
delivery-status: planned
authors: [OpenBank contributors]
supersedes: []
superseded-by: []
delivery-repos: []
tags: [notifications, capacity, compliance]
summary: "Govern bank-initiated customer contact through a durable decision trail and capacity-aware release plan; bulk audiences enter resumable waves while critical notices retain reserved capacity."
---

# ADR-0333 — Governed customer communication and capacity-aware bulk delivery

## Context

Campaigns have approval, journeys, consent checks, quiet hours, local caps, suppression, outcomes
and pause (ADRs 0200, 0219, 0221, 0239). Operator messages, transactional notifications and in-app
placements use other entry points. Communication Studio (ADR-0285) governs conversational style,
not traffic; Customer 360 (ADR-0210) is a read view, not a contact ledger.

Ordinary notification templates are currently enum entries with a closed variable set and
hard-coded render branches in notification-service. Most older branches have English copy only.
These compile-time bounds protect secrets, but a routine wording or translation change requires a
service release. Communication-service's existing style/playbook database is for conversational
AI and human scripts; moving security or payment templates there would add an agent-plane runtime
dependency to a banking notice. A raw `BalanceUpdated` event is also not a notification intent:
ledger projection, holds and value-date rolls can each change it, potentially many times a day.
Notification-service already persists notification records and exposes a customer feed,
party-scoped detail and read/unread operations through customer-edge. There is no inbox-only
`NotificationChannel` or outcome for a message that never uses email or push.

The current campaign segment evaluator materialises all party ids in a `List<UUID>`, then
`CampaignService.enrol` starts one Temporal workflow per party in one request. ContactPolicyGate
producers count from distinct local logs; check and send are not an atomic reservation. Concurrent
campaign, operator and app contacts can disagree about fatigue. There is no demonstrated limit on
workflow admission, provider dispatch or the burst when recipients tap a link. A halt API with no
dispatch-path call site is not a proved halt.

"Inform everyone at once" means one publication time and a measured completion deadline, not
simultaneous provider or device acceptance. If safe capacity cannot meet the deadline, activation
must fail before contacting anyone or enter an explicit degraded state.

## Decision

**D1 — Contact identity and evidence.** Every bank-initiated touch will carry a stable intent id,
party id, purpose, class, priority, channel, content and destination revisions, source, approval
reference and idempotency key. Notification-service retains email/push transport ownership;
engagement-service retains in-app impressions; consent-service retains consent and suppression.
A privacy-minimised projection joins decisions and outcomes. Handoff, provider acceptance,
impression, click and conversion are separate facts. Servicing roles may inspect one party's
chronology; campaign roles receive aggregates only. Message bodies and raw audience export are
excluded.

**D2 — Atomic contact reservation.** The shared ContactPolicyGate vocabulary stays in
libs-runtime, while counted contacts use one durable reservation authority. It serialises
concurrent requests per party and rolling window, checks live consent and suppression, and
records reasoned ALLOWED, DENIED or UNAVAILABLE decisions. A reservation has a bounded lease
and stable idempotency key. Accepted sends consume it; known pre-handoff failures release it;
an ambiguous transport result stays reserved until reconciliation. Replays return the original
decision. Outbound marketing is counted across campaign, operator and agent origins; promotional
impressions have a separate budget. SERVICE_EXEMPT skips marketing consent and fatigue caps,
but not transport capacity, authentication, audit or an applicable legal suppression. Existing
local counters are migration fallbacks, never silently combined with the new authority. Cutover
requires parity evidence and fails closed for counted traffic.

**D3 — Durable bounded audience admission.** Campaign-service will create a run pinned to the
approved campaign revision and audience snapshot time. Ordered keyset pages replace the
unbounded segment response. The cursor and page outcomes are durable; `(run, party)` is unique.
A scheduler leases bounded pages, obtains capacity permits and starts journeys. It records
successes, skips and retryable failures separately. Crash, overlapping schedule and manual
retry resume without duplicate sends. Pausing stops new admissions; existing journeys re-check
campaign state and contact policy before each touch. Consent revocation still signals active
journeys.

A release plan is either ASAP with a completion deadline or explicit waves. Each wave has a
size, observation window and ceiling on admitted recipients per interval. Whole-audience
activation requires preflight evidence that the deadline can be met. Health failure holds
the run automatically. An operator may halt immediately; independently approved resume
continues the same run, cursor and idempotency keys.

**D4 — Three capacity boundaries.** Permits apply at audience admission into Temporal, message
dispatch by channel/provider, and any bank-owned landing path linked from the message.
Marketing uses spare capacity; security and required service notices have a reserved pool.
Channel workers use bounded concurrency, jittered retry for retryable faults and a reconciled
dead-letter path. Provider acceptance never proves device delivery. Destinations are allow-listed
authenticated app routes with a measured traffic budget; release slows or holds when their
p95 latency, error rate or saturation crosses the approved limit. Marketing never takes
capacity from ordinary banking reads and writes.

No fleet-wide send-rate or click-through number is invented in this ADR. The run record
contains measured safe rate, expected click fraction, burst factor, completion deadline
and headroom from a reproducible load test in the target environment. Missing evidence
prevents mass activation. Mocked mail and disabled push remain SKIPPED, not success.

**D5 — Operator view and content quality.** Admin UI shows upcoming, running, held and
completed runs: approval, wave plan and actual progress, remaining audience, queue age,
handoff and provider outcomes, suppression, click and destination health. Unknown and stale
readings are explicit. Hold and resume show their evidence. Per-party history joins the same
intent without exposing bodies to campaign roles.

Maker and checker review exact audience, content and destination revisions, purpose,
languages, channel previews, link behaviour, estimated reach, deadline and capacity plan.
Automated checks cover prohibited claims, unsafe links, accessibility, template variables
and policy requirements; synthetic journeys verify landing behaviour. Communication
Studio style review remains a separate approval. After launch, quality is measured through
complaints, opt-outs, failed links, suppression and product outcomes.

**D6 — Governed everyday templates.** Notification-service owns a versioned template registry
for deterministic email, push and authenticated-inbox copy. Its code-owned template identity,
purpose, category, sensitivity, allowed variables, channel eligibility, fallback and deep-link
policy remain closed and reviewed in git. Business editors can draft locale-specific subject,
body and call-to-action copy only within those bounds. The Communication Studio UI is the shared
editorial surface, but its API routes template writes to notification-service; delivery never
calls communication-service or an LLM. A maker and a different checker approve an immutable
revision after sample rendering, placeholder completeness, HTML/URL allow-list, localisation,
accessibility, content-safety and synthetic delivery checks. The published revision is pinned
to each notification row and outcome. Runtime uses a locally available last-known published
revision, then an engineer-reviewed built-in baseline if the template store is unavailable;
it never falls back to raw variables or an unreviewed draft. Secret material is never persisted
in rendered bodies and lock-screen push remains generic.

The catalogue distinguishes security/authorisation, required service notices, account/payment
activity, optional financial alerts and marketing. Customer preference applies by category
and channel; security and required legal notices are not reclassified as marketing to consume
spare capacity. New examples include settlement received, payment failed, statement ready,
consent changed and delegation changed. `BALANCE_UPDATED` itself will not fan out to customers.
A low-balance alert is customer-opted-in for an account and threshold, emitted only on a
downward threshold crossing with hysteresis, a cool-down and coalescing of rapid movements.
The notification contains no amount on a lock screen and opens the authenticated account view;
the current authoritative balance is fetched on tap. No alert is sent when source state or
account ownership is uncertain. Reversal, late event and replay cases get deterministic
idempotency keys and tests. Digests are a later distinct template, not an implicit fallback.

| Customer event | Intent owner | Default route | Consent and fatigue |
| --- | --- | --- | --- |
| Payment settled or failed | Payment/transaction domain | Authenticated inbox, optional generic push | Account activity preference; failures become required service notices only after compliance review |
| Statement ready | Document domain | Authenticated inbox with app deep link | Required delivery follows the product/legal contract; promotional cap does not apply |
| Consent or delegation changed | Owning consent/delegation domain | Generic push and authenticated detail | Security/authorisation; required controls cannot be muted inadvertently |
| Low balance threshold crossed | Balance domain with customer alert settings | Generic push and authenticated account view | Explicit account threshold opt-in, cool-down and coalescing |
| Service interruption | Incident communications owner | Inbox plus measured email/push waves | Service notice classification, separate approval and capacity plan |
| Product offer | Campaign domain | Approved campaign channels | Marketing consent, shared fatigue reservation and spare capacity |

The event owner decides *whether* a customer contact exists; notification-service decides the
approved wording and transport. Inbox-only delivery is an explicit `INBOX` channel and `VISIBLE`
outcome, not an alias for a push handoff: `IN_APP` was removed after it silently reported success
without delivery. Reuse the existing notification rows, customer feed, read/unread operations and
party-scoped read API; commit the row and visibility outcome in one transaction, with a mandatory
idempotency key. Visibility is not a read or a push acceptance. A technical balance event therefore
cannot become a customer message merely by adding an enum value.

## Delivery record

The first PR for this ADR adds bounded campaign admission with a database lease and a default-off
budget, a staff-managed notification copy registry with separate maker and publisher, and an
inbox-only notification outcome backed by the existing customer feed. Published copy is pinned to
the notification row. A bulk run now prepares its audience through one streamed silver query,
persists the ordered party ids in PostgreSQL, and admits only from the completed snapshot. Failed
preparation holds the run; an approved resume discards the partial extraction and restarts it.
The recipient ledger records starts, admissions, skips and failures. These are individual controls,
not proof of the full communication system. Provider dispatch and click destinations lack measured
shared capacity limits, and there is no cross-origin atomic contact reservation or single operator
evidence projection yet. The workflow-start/enrolment-write boundary also needs reconciliation
before a crash there can be called exactly once. Mass activation must remain disabled until those
controls and the D7 end-to-end/load evidence are complete.

The journey delivery activity now requires a committed ACTIVE enrolment before emitting a contact.
Temporal can run ahead of the enrolment write, so this guard makes that race fail closed and
retryable. A process crash after workflow start but before the write can still leave an orphan
execution. For a RUNNING bulk run, its STARTING recipient and unadvanced cursor cause the leased
page to retry; a focused crash-after-start test proves the later enrolment write completes against
the same running workflow. Triggered enrolment relies on Kafka redelivery, and a direct operator
request still needs a repeat request. Durable start intent and reconciliation across all three
entry paths remain required before claiming automatic recovery in every case.

Campaign handoffs derive a stable send-log id from campaign, party, step and dry-run mode. Temporal
activity retry and Kafka replay therefore use the same `correlationId` and `deduplicationKey` for
one logical notification. Repeated send-log writes preserve SENT and can advance FAILED to SENT.
When a SENT row already exists, activity retry returns that result before another handoff or
counter increment.
This protects the campaign producer only. Other notification producers still need stable keys
before the transport can make a general idempotency claim. The send-log write still follows the
broker handoff, so a crash in that window can leave an uncorrelated delivery outcome; the durable
dispatch/reconciliation work in D4 remains required.
Existing in-flight journeys created before this deterministic id change can still retry with a
new identity after deployment. Drain or reconcile those executions before relying on this guard;
the new key cannot retroactively identify their random send-log ids.

The existing email/push path persists a `PENDING` row before provider handoff. If a failure occurs
after that insert but before a terminal outcome, Kafka redelivery sees the deduplication key.
It now refuses to acknowledge an unresolved duplicate and sends it to the configured dead-letter
path for reconciliation; a terminal duplicate remains an idempotent no-op. This stops the former
silent skip but does not prove whether the provider took the message. Provider ambiguity cannot be
resolved from a Kafka ack. D4 therefore requires a durable dispatch queue and reconciliation,
with an idempotent provider key where supported, before claiming resilient non-inbox delivery.
An always-registered gauge and alert now surface rows still PENDING after 15 minutes, including
ones whose terminal write failed after provider handoff and were acknowledged without replay.
The alert is evidence for investigation, not permission to resend an ambiguous message.

**D7 — Rollout and proof.** Deliver in reversible steps: instrument and exercise halt and
outcomes; add keyset pages and durable run/wave state; add capacity permits and automatic
hold; migrate to shared atomic reservations; add the unified operator projection; add
versioned everyday templates and customer-configured financial alerts. The legacy synchronous
campaign API keeps its response shape but must obtain the same global admission lease and reject
audiences above the measured one-interval budget *before* starting a journey. Existing scheduled
sweeps use that same bounded compatibility path until they can create durable release plans. No
call silently becomes a partial enrolment. A rollout must measure and configure the budget before
switching these guards on, or scheduled campaigns will correctly stop admitting parties.

Acceptance requires a reproducible end-to-end test with concurrent campaigns and operator
contact, crash mid-wave, consent withdrawal, provider rejection, stale outcomes and a click
burst into a throttled destination. It must show no duplicate contact or cap overrun, no
marketing dispatch while held, protected critical capacity, bounded work, and an auditable
result for every recipient. Load tests record the sustainable rate and limiting dependency.

## Alternatives considered

- **Scale the synchronous enrol loop and worker replicas.** Throughput may rise, but the
  unbounded response, missing cursor and cross-channel disagreement remain.
- **Start every Temporal workflow immediately.** This moves the burst to Temporal and the
  database. Admitted journeys need bounded admission first.
- **Put all contact in notification-service.** It cannot own in-app impressions, campaign
  decisions or destination capacity; those owners stay separate.
- **Put everyday templates in communication-service.** Its current responsibility and
  deployment are conversational prompts and playbooks. Making authorisation notices depend
  on that agent-plane service adds a failure domain and weakens the code-owned schema boundary.
- **Notify on every balance update.** One business movement can produce several technical
  updates, and even a genuine balance change is not necessarily useful. This would create
  notification fatigue and reveal financial activity without a customer request.
- **Use only a Valkey bucket and cached fatigue counters.** It is fast but failover, expiry
  and parallel checks can erase or overrun customer protection.

## Consequences

**Positive**
- Mass communication has a measured deadline and bounded blast radius.
- Contact and non-contact decisions are explainable across channels.
- A failing destination can stop further invites before the app is overwhelmed.

**Negative**
- Reservation and run state become availability-critical for marketing and need recovery tests.
- The evidence projection adds storage and privacy obligations.
- A capacity plan can reject an urgent request; approval cannot override physical limits.

**Neutral**
- Transactional/security notices retain business ownership and receive transport reservations.
- SMS remains unsupported until a provider adapter, preference and outcome contract exist.
- Conversation style and deterministic notification templates share an editorial UI, not
  a runtime service or an arbitrary prompt-to-message transformation.

## Compliance impact

- PCI DSS: no card data enters content, telemetry or the contact projection.
- DORA: run recovery, capacity proof, halt/resume evidence and dependency failures become controls.
- GDPR: purpose, consent, objection and data minimisation apply per touch; history needs
  role-based access, retention and erasure.
- PSD2: ordinary authenticated banking flows retain capacity ahead of promotional traffic.
- CNB: communication and incident-notice evidence become reconstructible; classification of
  each contact class remains subject to compliance review.

## References

- ADR-0176 — operator-initiated customer messaging.
- ADR-0198 — marketing consent.
- ADR-0200 — Temporal campaign journeys.
- ADR-0210 — Customer 360 over analytics silver.
- ADR-0219 — platform contact-policy gate.
- ADR-0221 — Campaign Studio.
- ADR-0239 — notification delivery outcomes.
- ADR-0285 — governed conversational style and playbooks.
