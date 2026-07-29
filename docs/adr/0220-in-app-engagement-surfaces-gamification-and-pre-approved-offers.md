---
date: 2026-07-29
decision-status: proposed
delivery-status: planned
authors: [Jiri Raska]
supersedes: []
superseded-by: []
delivery-repos: [openbank-app]
tags: [mobile-app, customer-edge, privacy-gdpr, lending]
summary: "Server-driven typed engagement surfaces with an impression budget and a feedback event loop; gamification with safety rules in domain code; pre-approved offers rendered only from standing ADR-0142 decisions with RPSN disclosure."
---

# ADR-0220 — In-app engagement surfaces, gamification and pre-approved offers

## Context

Outbound campaigns (ADR-0200) are half the engagement story. The other half is inbound: the customer
opens the app — what do they see? Today the answer is static screens compiled into `openbank-app`
releases. Three accepted decisions produce content that currently has *nowhere to be rendered*:
ADR-0203 D5's finance-coach generates proactive insights, ADR-0201's NBA will rank which catalogue
message is most relevant, and ADR-0199/0210's 360 view knows what the customer holds. The rendering
surface is the missing term in every one of those sentences.

What must not happen is equally explicit in the estate:

- **Surfaces are not the IN_APP channel reborn.** Issue #2372 removed `IN_APP` from
  `NotificationChannel` entirely, and ADR-0200 D7 records that re-adding it means a real
  terminal-status transition and wake-signal design. A *notification* is a delivered message with a
  lifecycle; a *surface* is a content slot the app renders when the customer arrives. Conflating them
  would smuggle the removed channel back in under a new name, with none of its required design.
- **The content discipline is already decided.** ADR-0176 D4's catalogue is an allow-list of
  meanings; ADR-0200 D4 re-accepted it under campaign pressure and called it "the single most
  important thing not to relax". Server-driven UI does not change that: typed, catalogue-backed
  payloads are the same control expressed in JSON; free-form server HTML would be the bypass ADR-0176
  exists to prevent.
- **The credit boundary is already decided.** ADR-0201 D5 bars NBA from credit outcomes by type.
  A "pre-approved" offer presented in-app is therefore only lawful as the *presentation of a real
  decision* — ADR-0142's engine (proposed/planned today) — never as a marketing claim and never as
  an NBA output. And Czech consumer-credit advertising rules attach disclosure duties to that
  presentation, which a banner cannot omit.
- **Gamification in finance is a conduct minefield.** Fake urgency, hard-to-dismiss prompts and —
  worst — mechanics that reward borrowing are dark patterns under the UCPD (Act No. 634/1992 Coll.)
  and mis-selling under consumer-credit conduct rules. The only gamification this platform may ship
  is one whose safety rules are *domain invariants*, not content policy.

## Decision

We will add **`openbank-engagement-service`** owning three capabilities — server-driven engagement
surfaces, a gamification engine, and pre-approved offer presentation — sequenced **after** the
ADR-0209 prerequisites (libs Temporal extraction, campaign-service first slice) and gated on ADR-0219's
contact-policy gate.

**D1 — Typed surfaces, server-decided, app-rendered.** The app registers named slots (`home_banner`,
`home_carousel`, `stories`, `product_feed`, `rewards_hub`). On render it queries the service through
the customer edge; resolution runs ContactPolicyGate (ADR-0219 — `MARKETING_COMMS_INAPP` consent plus
the PROMOTIONAL_IMPRESSION budget) → deterministic eligibility → ADR-0201 NBA ranking → a **typed
payload** (`banner | card | story | carousel | offer`) whose content references the ADR-0176-style
catalogue, never free-form markup. The KMP client owns theme, accessibility (ADR-0149) and motion.
Service outage or gate timeout ⇒ the app renders bundled default content (SERVICE_EXEMPT) —
engagement never blocks banking.

**D2 — The feedback loop is the product.** The app posts `impression | click | dismiss | conversion`
to the service, which publishes `engagement.events` via outbox. This stream feeds the analytics
layer (ADR-0022), campaign measurement (ADR-0221's dashboards) and the ADR-0140 feature catalogue as
NBA training labels. Dismissal is a first-class negative signal: repeated dismissal of a content
class writes a topic-level suppression entry (ADR-0219 D3) rather than merely hiding one card.

**D3 — Gamification with safety rules in domain code.** Aggregates: `Challenge`, `Streak`, `Badge`,
`Points`, evaluated event-driven from existing domain events (savings deposits per ADR-0153's goal
metadata, logins, on-time repayments, educational-content completion). Hard invariants, tested as
domain rules, not stated as content policy:

1. No challenge may reward credit uptake, credit utilisation or any risk-increasing behaviour.
2. Opt-in (`rewards_hub` off by default); leaving is one tap and keeps earned value. The toggle
   controls *feature visibility only* — personalised challenges additionally require
   `MARKETING_COMMS_INAPP` consent, and consent wins every conflict.
3. No fake urgency: countdowns only on genuinely expiring offers; dismissal is always as easy as
   engagement.
4. Reward economics are capped per customer per year and provisioned through the billing path
   (ADR-0143), never marketing cash.
5. **Vulnerable customers are excluded from targeting** (arrears/collections contact, an open
   dispute, a fraud hold — the ADR-0200 D6 adverse-state set): excluded from challenge *targeting*
   and promotional surfaces at the eligibility stage, while remaining free to use the hub on their
   own initiative.

**D4 — Pre-approved offers are standing decisions, rendered — never invented, never ranked.** A
scheduled batch asks ADR-0142's engine for standing decisions per eligible party (amount, price,
reason codes) stored with a TTL (default 30 days). A surface may render a pre-approved offer **only**
from a non-expired standing decision — fail-closed hide otherwise — and every rendered credit payload
carries the **mandatory RPSN/APR representative example** (Act No. 257/2016 Coll.) verbatim from the
decision record; a payload without it is invalid and not rendered. NBA never touches these payloads
(ADR-0201 D5's type boundary applies). One-tap acceptance does not bypass origination: it enters the
credit flow, which re-verifies binding conditions at acceptance time. The offer-explanation agent
(ADR-0222) explains from the same reason codes.

**D5 — Honest dependency statement.** Surface infrastructure (D1/D2) and gamification (D3) depend on
ADR-0219 and on `MARKETING_COMMS_INAPP` consent (ADR-0198/0205 — shipped). Personalised ranking
depends on ADR-0201's NBA graduating from shadow. Pre-approved offers depend on ADR-0142 being built
at all — that slice cannot start as a demo with fabricated "pre-approvals", which is precisely the
mis-selling this ADR forbids; an inauthentic placeholder is worse than a missing feature.

## Alternatives considered

- **Static, release-coupled home-screen content.** Zero backend. Rejected: every insight ADR-0203 and
  every ranking ADR-0201 produce would wait for an app-store review cycle to reach a customer, and
  nothing would be measurable.
- **Re-add IN_APP to `NotificationChannel` and reuse notification-service for surfaces.** Rejected on
  the ADR-0200 D7 analysis: that channel was removed because a delivered-message lifecycle is the
  wrong shape for rendered slots; rebuilding it correctly is real work this ADR does not need, because
  a surface has no delivery, no terminal status and no tray presence.
- **Third-party gamification/personalisation SDK (Braze, Antavo, Talon.One).** Fast demo. Rejected on
  the ADR-0175 residency boundary and ADR-0174 exit position, as in ADR-0199/0200 — behavioural event
  streams are among the most sensitive data the bank holds.
- **Gamify engagement with credit products ("take a loan, earn points").** Rejected absolutely (D3.1)
  — it is the textbook mis-selling pattern, and no conversion figure justifies a conduct finding.
- **Let surfaces serve free-form CMS HTML.** Rejected as the ADR-0176 D4 bypass: an allow-list of
  typed payloads is auditable; arbitrary server HTML is a phishing template with a bank logo.

## Consequences

**Positive**
- ADR-0201's NBA and ADR-0203's coach gain a lawful, measured place to reach customers — the feedback
  loop that also trains the ranker and proves campaign attribution (ADR-0221).
- Gamification becomes a demonstrable conduct control (invariants in tested domain code) instead of a
  marketing claim — the difference a ČNB review can actually verify.
- Pre-approved offers convert a regulatory risk (advertising credit without a decision behind it)
  into a controlled feature with the disclosure built into the payload contract.

**Negative**
- A new customer-facing service on the app hot path: availability engineering (edge cache, default
  content), a second API contract coupled to the `openbank-app` release train (typed-payload
  versioning), and an event stream whose volume scales with engagement itself.
- Gamification is an ongoing content-operations duty (challenge design, reward budgets) — a business
  cost, not only engineering.
- D5's dependency chain means the flagship slice (pre-approved) lands last; sequencing pressure to
  fake it early will exist and must be refused.

**Neutral**
- `openbank-app` implements the typed renderers and rewards-hub UI; contracts are OpenAPI-first
  (ADR-0005) with typed-payload versioning. Licensing: engagement-service moves no money and is not
  agent-plane — Apache-2.0, per the ADR-0197 property test.

## Compliance impact

- PCI DSS: not applicable — surface payloads reference catalogue content; no cardholder data.
- DORA: a customer-facing ICT service added to the register; graceful-degradation design (default
  content) is its resilience note.
- GDPR: Art. 7 — `MARKETING_COMMS_INAPP` gates personalised surfaces; dismissal-as-objection feeds
  Art. 21(2) through ADR-0219 D3. Art. 13/14 — reason codes accompany personalised content (see
  ADR-0222 for the customer-facing rendering). Retention of `engagement.events` per ADR-0118.
- PSD2: not applicable — no account access, no initiation.
- CNB: Act No. 257/2016 Coll. — RPSN/APR representative example mandatory on every rendered credit
  offer (D4); Act No. 634/1992 Coll. / UCPD — dark-pattern prohibitions encoded as D3 invariants;
  vulnerable-customer targeting exclusions (D3.5).

## References

- [ADR-0200](0200-campaign-journeys-as-temporal-workflows-with-consent-gated-delivery.md) — D7's
  IN_APP removal analysis (surfaces are not that channel); D6's adverse-state set (reused in D3.5).
- [ADR-0201](0201-customer-segmentation-and-next-best-action-on-the-ml-decisioning-platform.md) — NBA
  ranking consumed here; D5's credit boundary enforced by payload contract.
- [ADR-0198](0198-marketing-consent-as-a-first-class-consent-service-scope.md) and
  [ADR-0205](0205-marketing-consent-forwarder-and-sca-exempt-activation.md) — the INAPP consent basis.
- [ADR-0219](0219-platform-contact-policy-gate-contact-classes-durable-counters-suppression.md) — the
  gate and the impression budget; dismissal-to-suppression path.
- [ADR-0210](0210-customer-360-as-a-query-over-the-analytics-silver-layer.md) — eligibility inputs.
- [ADR-0142](0142-credit-decisioning-engine.md) — standing decisions behind D4; blocking dependency.
- [ADR-0176](0176-operator-initiated-customer-messaging.md) — the catalogue discipline D1 extends.
- [ADR-0153](0153-savings-goal-metadata.md) — savings-goal events as challenge inputs.
- [ADR-0143](0143-runtime-product-fee-posting-via-a-dedicated-billing-service.md) — reward
  provisioning; [ADR-0149](0149-digital-accessibility-standard-wcag-2-2-aa-en-301-549.md) —
  surface accessibility.
- [ADR-0221](0221-campaign-studio-the-campaign-authoring-operator-experience.md) — the metrics
  consumer; [ADR-0222](0222-offer-explanation-and-relationship-manager-agents.md) — explanation of
  what D4 renders.
