---
date: 2026-07-25
decision-status: proposed
delivery-status: planned
authors: [Jiri Raska]
supersedes: []
superseded-by: []
delivery-repos: []
tags: [notifications, privacy-gdpr, compliance, mobile-app]
summary: "A campaign-service ships the cohort capability ADR-0176 deferred: each enrolment is a Temporal workflow, consent is re-checked per step and revocation arrives as a workflow signal, so a journey stops mid-flight instead of at the next batch."
---

# ADR-0200 — Campaign journeys as Temporal workflows with consent-gated delivery

## Context

ADR-0176 shipped operator-initiated customer messaging and closed with an explicit deferral in its
rejected-alternatives list: *"A campaign / segment tool (send to a cohort). Out of scope by design: it
is a marketing capability, and D6 refuses marketing until consent is real. Revisit afterwards, as its
own ADR."* This is that ADR. The precondition it named — real consent — is what ADR-0198 decides, so
this ADR is only buildable on top of it, and its ordering constraint is hard rather than tidy.

ADR-0176 also predicted the demand: *"Every new message shape needs a code change and a release. This
is not a campaign tool, and operators will ask for one."* The forces now:

**Force 1 — the capability gap is structural, not cosmetic.** ADR-0176's catalogue design is correct
for its purpose and unusable for a cohort: a template is code, an operator sends to one customer at a
time through a maker-checker flow, and there is no notion of a recipient set, a schedule, a sequence,
or a stop condition. `docs/strategy/01-bian-service-domain-mapping.md` row 13 lists *Marketing Plan
Activity / Campaign management* as a missing BIAN Service Domain.

**Force 2 — a batch scheduler makes consent revocation arrive late by construction.** The obvious
implementation is a cron that materialises a cohort, filters by consent, and sends. Its failure is not
a bug but its design: a customer who revokes consent between materialisation and send is still sent
to, and a customer who revokes mid-sequence keeps receiving the remaining steps until the next batch
notices. GDPR Art. 7(3) requires withdrawal to be as easy as giving it; a control that takes effect at
the next batch boundary is weaker than the promise the UI makes when the customer flips the toggle.

**Force 3 — the delivery channels a campaign needs are partly stubs.** This must be stated plainly
because it is the real cost. In `NotificationConsumer`, `IN_APP` is `log.infof("IN_APP stub: …")` and
the row stays `PENDING` — ADR-0176 already lists this as a consequence ("It has to become real delivery
before any of this works"). `SMS` is likewise a stub with no port or adapter. `PUSH` is real
(`ApnsPushSender`, `FcmPushSender` behind `PushSenderRouter`) but both adapters default to
`enabled=false` and return a skipped success. EMAIL is the only channel that both works and is enabled.
A campaign capability that assumed four working channels would be shipping against three fictions.

**Force 4 — ADR-0135 §3 is still unbuilt (#1182), and a campaign is what makes it urgent.** Push
payloads are meant to carry a wake signal and no content; the existing path ships rendered content.
ADR-0176 D3 made this true by construction for the operator path only. A marketing push about a
customer's specific product or balance situation is precisely the payload that must not sit
unencrypted in a notification tray.

Why now: the substrate is otherwise complete. Temporal is the fleet orchestration standard with 14
workflow implementations across the fleet; notification-service is the single delivery choke point;
ADR-0199 provides the customer view; ADR-0198 provides a consent that can be queried. The missing piece
is the journey model itself.

## Decision

We will add **`openbank-campaign-service`**, and make the unit of execution a *per-enrolment Temporal
workflow* rather than a batch.

**D1 — One workflow instance per enrolled customer.** A campaign is a definition (steps, delays,
branch conditions, stop conditions); an enrolment is a `CampaignJourneyWorkflow` instance keyed by
`campaignId + partyId`, which is also the idempotency key that makes double-enrolment impossible. Each
step is an activity. Delays are `Workflow.sleep`, not scheduler rows. This is the same shape as
`SepaPaymentWorkflow` and `DocsTruthWorkflow`, so it introduces no new orchestration concept — but note
that Temporal wiring is duplicated per service today (`infrastructure/temporal/TemporalClientProducer.kt`
in each service, no shared libs module), so campaign-service copies that pattern rather than reusing a
library that does not exist.

**D2 — Consent is re-checked immediately before every send, and revocation is a signal.** Two
mechanisms, deliberately both:
- **Pull:** each delivery activity calls consent-service for the ADR-0198 scope matching the step's
  channel. No cached consent, matching the ADR-0195 rule that a cached consent is one that survives its
  own revocation.
- **Push:** campaign-service consumes the ADR-0126 `consent.revoked` outbox event and signals every
  live workflow for that party, which terminates the journey at once rather than at its next step.

The pull alone would leave a customer inside a 30-day journey with no signal until the next step wakes.
The push alone would depend on an event arriving. Force 2 is closed by having both.

**D3 — Delivery goes through notification-service, never direct.** campaign-service publishes a
notification request; it holds no SMTP, APNs or FCM credentials and has no delivery adapter. This keeps
the ADR-0198 D4 consent gate on the single choke point every channel passes through, so a campaign
cannot bypass the gate even by mistake. It also means the channel stubs of force 3 are fixed once, in
the service that owns them, for every sender.

**D4 — Templates stay a catalogue; a campaign composes, it does not author.** ADR-0176's closed
per-template variable schema and its refusal of free-text bodies are kept unchanged and for the same
reason: a catalogue is an allow-list of *meanings*, and every filter over an operator-supplied string is
a bypass hunt. A campaign step references a template and supplies its declared variables. The cost
ADR-0176 accepted — a new message shape needs a code change and a release — is accepted again here. It
is the single most important thing not to relax under campaign-tool pressure.

**D5 — Marketing campaigns require four-eyes approval to activate.** Reuse the ADR-0176 D5 mechanism
(`rules.yaml four_eyes.actions`, `RedisApprovalStore`, `ApprovalResource` with its self-approval guard)
by adding `campaign.activate`. A campaign reaches a cohort, so its blast radius exceeds a single
operator message, which is what four-eyes is for. Note the ADR-0176 warning: `four_eyes.actions` and
`four_eyes.verbs` are two lists that gate approvals and have drifted before, so both must stay pinned in
`rest_test.rego`.

**D6 — Suppression rules are first-class and evaluated before consent, not after.** A frequency cap per
party per window, a global quiet period, and a hard exclusion for customers in an adverse state:
collections or arrears, an open dispute, a fraud hold, a bereavement marker. These are evaluated from
the ADR-0199 view. The ordering matters and is deliberate — a suppression is not a consent question, and
a customer who consented to marketing is still someone the bank must not send a product upsell to on the
day their card was frozen for fraud.

**D7 — Blocking dependencies, stated as blocking.** This ADR cannot ship before: ADR-0198 (a real
consent to check), `IN_APP` becoming real delivery, and ADR-0135 §3 / #1182 for any push step carrying
customer-specific content. Launching EMAIL-only against ADR-0198 is a legitimate first slice; SMS stays
out of scope until a port exists at all.

**D8 — Licensing.** campaign-service sends to customers under a consent basis and is not agent-plane, so
it is Apache-2.0 and is **not** added to `rules.yaml agpl_modules` (ADR-0197). The campaign-copilot agent
of ADR-0203 is a separate component and is.

## Alternatives considered

- **Keep the ADR-0176 position: no campaign tool, ever.** Zero build cost, zero new marketing risk, and
  the phishing surface stays closed by construction. Genuinely defensible, and it is the status quo.
  Rejected because the deferral was explicitly conditional ("Revisit afterwards, as its own ADR") on the
  consent precondition that ADR-0198 now satisfies — and because a reference banking platform that
  cannot demonstrate governed customer communication leaves the most-regulated part of marketing
  undemonstrated, which is where its differentiation lies.
- **A cron-driven batch campaign runner.** Far simpler: one scheduled job, a cohort query, a send loop.
  No workflow engine, no per-customer state. Rejected on force 2 — revocation latency is designed in, not
  incidental. Also note the fleet's own scheduler footgun: five `@Scheduled` methods, three money-path,
  had never once run because a plain scheduled method carries no Vert.x context and `runBlocking` threw
  `HR000068` silently. A batch runner would put a marketing capability on exactly that class of
  mechanism.
- **A commercial campaign platform (Braze, Salesforce Marketing Cloud, Iterable).** Buys journey
  tooling, deliverability and analytics immediately, and is what a real bank would likely do. Rejected
  for the same three reasons as the CRM in ADR-0199: identified customer data would leave the ADR-0175
  residency boundary, it becomes an ADR-0174 ICT third-party with an exit problem, and the consent
  enforcement that is the point here cannot be expressed inside it — the vendor would hold its own
  consent copy, which is the ADR-0198 defect re-created at a vendor boundary.
- **Put journeys in notification-service.** It already owns templates, preferences and every channel, so
  no new service. Rejected: notification-service is the delivery choke point and must stay simple enough
  that the consent gate in it is obviously correct. Adding cohort state, schedules and branch logic to
  the service every other service depends on for delivery couples campaign availability to notification
  availability.
- **Model a journey as a state machine in Postgres with an outbox tick.** Avoids a Temporal dependency
  for a non-money-path capability. Rejected as re-implementing Temporal's durable timers, retries and
  visibility, in a fleet where 14 services already run Temporal and the operational cost is already
  paid.

## Consequences

**Positive**
- Consent revocation stops a journey mid-flight, which is a stronger claim than any batch design can
  make, and it is demonstrable to a regulator as a running workflow that terminated on a signal.
- Every send is on the ADR-0198 gate at the single choke point, so the enforcement cannot be bypassed by
  adding a second sender later.
- Fixing the `IN_APP` stub and #1182 has an owner and a deadline instead of being inherited silently — the
  outcome ADR-0176 wanted for the same two items.
- Closes BIAN row 13 (Marketing Plan Activity / Campaign management).
- Retries, timeouts, per-step audit and journey visibility come from Temporal rather than being built.

**Negative**
- One workflow per enrolled customer is a large number of workflow instances for a large cohort. Temporal
  handles this, but the namespace sizing, history retention and worker capacity are real capacity work,
  and a cohort of 100k enrolments is not the shape the existing payment workflows sized the cluster for.
- A per-send consent call means consent-service is now on the campaign hot path. It must be sized for
  fan-out, and the failure mode must be fail-closed (no consent answer means no send), which makes a
  consent-service outage a campaign outage. That is the correct trade and it must be deliberate.
- This is a genuinely new marketing risk surface on a platform that had none. D4's catalogue discipline
  is the only thing holding the phishing surface closed, and it will be under continuous pressure to
  allow free text.
- Three blocking dependencies (D7) mean the first slice is EMAIL-only, which will look like an
  incomplete capability. Shipping the appearance of four channels would be worse.

**Neutral**
- Where campaigns are authored — an admin-ui section versus its own console — is not decided here.
- Personalising a campaign's visual presentation through the ADR-0191 ThemeSpec channel is possible but
  not part of this decision; note that ThemeSpec travels as raw JSON with the typed model and validator
  living in `openbank-app`, so any such step depends on out-of-tree work.

## Compliance impact

- PCI DSS: not applicable — no cardholder data in a campaign definition or a message variable; the
  ADR-0176 closed variable schema is what keeps it that way.
- DORA: choosing an in-house campaign capability over a vendor is an ICT third-party position for the
  ADR-0174 register; the fail-closed consent dependency is an operational-resilience consideration that
  belongs in that service's own resilience notes.
- GDPR: Art. 7(3) (withdrawal as easy as giving — D2's signal is what makes this true in-flight rather
  than at a batch boundary); Art. 21(2) (direct-marketing objection, honoured by the same mechanism);
  Art. 5(1)(b) purpose limitation, since a campaign must state its purpose and cannot reuse
  service-message consent; Art. 30 for the new processing purpose. Art. 22 is **not** engaged by this
  ADR: sending a message is not a decision producing legal effects. ADR-0201's targeting is where that
  question arises, and it is answered there.
- PSD2: not applicable — no account access and no payment initiation.
- CNB: not applicable.

Act 480/2004 §7 governs Czech commercial communications by electronic means and is the domestic
instrument the per-channel consent of ADR-0198 D1 exists to satisfy; the campaign step's channel
determines which scope is checked. As in ADR-0198, this is an engineering reading of the statute, not
advice.

## References

- [ADR-0176](0176-operator-initiated-customer-messaging.md) — the deferral this ADR takes up, and the
  catalogue, four-eyes and push-wake-signal decisions it reuses unchanged.
- [ADR-0198](0198-marketing-consent-as-a-first-class-consent-service-scope.md) — the consent this ADR is
  gated on; blocking.
- [ADR-0126](0126-unified-consent-lifecycle.md) — the `consent.revoked` outbox event D2 signals from.
- [ADR-0135](0135-push-notification-token-security.md) — §3 PII-free push payloads, unbuilt (#1182);
  blocking for any content-bearing push step.
- [ADR-0199](0199-customer-360-read-model-in-a-new-crm-service.md) — the view D6's suppression rules are
  evaluated from.
- [ADR-0201](0201-customer-segmentation-and-next-best-action-on-the-ml-decisioning-platform.md) — how a
  cohort is chosen, and where the Art. 22 question is answered.
- [ADR-0059](0059-outbound-oversight-webhooks-slack-teams.md) — the allow-listed-schema principle behind
  refusing free text.
- [ADR-0195](0195-mcp-server-caller-authentication-and-psd2-consent-binding.md) — live per-call consent
  validation.
- [ADR-0197](0197-agpl-open-core-boundary-covers-the-whole-agent-plane.md) — why campaign-service stays
  Apache-2.0.
- `openbank-notification-service/src/main/kotlin/com/openbank/notification/application/NotificationConsumer.kt`
  — the `IN_APP` and `SMS` stubs, and the per-channel dispatch.
- `docs/strategy/01-bian-service-domain-mapping.md` — row 13, the named gap.
