---
date: 2026-07-29
decision-status: proposed
delivery-status: planned
authors: [Jiri Raska]
supersedes: []
superseded-by: []
delivery-repos: []
tags: [privacy-gdpr, notifications, compliance]
summary: "One ContactPolicyGate in libs-runtime wraps consent, frequency caps, quiet hours and suppression behind three contact classes; cap counters are rebuilt from the event log, so a cache flush can never cause a contact burst."
---

# ADR-0211 — Platform contact-policy gate: contact classes, durable counters, suppression

## Context

ADR-0198 made marketing consent real and put its check at the notification-service choke point;
ADR-0200 D6 added campaign-scoped suppression (a frequency cap, a quiet period, adverse-state
exclusions evaluated from the ADR-0199/0210 view). Reading both against the tree, four gaps remain —
and they widen with every sender type the estate adds:

**Gap 1 — the suppression logic is scoped to one sender, but the senders are multiplying.** ADR-0200
D6's caps live in the campaign concept. Yet ADR-0176 already sends operator-initiated messages, ADR-0203
D5's finance-coach produces proactive insights *"gated by ADR-0198's consent and ADR-0200's suppression
rules"* — a rule that exists only inside campaign-service today — and ADR-0212 adds in-app surfaces and
RM-initiated contact. "ADR-0200's suppression rules" is currently a citation, not a callable thing.
Three senders re-implementing "2 per week" is the fleet's duplicate-config defect class (#1170/#1193)
applied to the one control a customer feels directly.

**Gap 2 — nobody has decided where frequency counters live, and the obvious answer is silently
unsafe.** A cap counter in Valkey (the online store already in the stack) is a cache: a flush, a
failover or a namespace eviction mid-week zeroes every customer's contact-fatigue state, and the next
campaign batch re-sends to people already at cap. Fail-closed covers *unavailability*; it does nothing
for a *silent reset*. The consent state itself is Postgres-backed in consent-service; the cap state is
exactly as load-bearing and currently has no durability story at all.

**Gap 3 — "impression" and "send" are different things, and nothing distinguishes them.** ADR-0212's
surfaces render promotional content on every app open. If a surface render consumes the weekly send
cap, a real user exhausts their protection in a day and every subsequent open is either degraded or
uncounted — the cap either breaks the product or becomes a fiction. A customer-initiated impression is
not a bank-initiated contact; Act 480/2004 §7 governs the bank *sending* commercial communications,
not the customer opening their own app. The two need separate budgets with separate semantics, decided
once, not per sender.

**Gap 4 — there is no suppression that is not a consent revocation.** GDPR Art. 21(2) objection and
Art. 7(3) withdrawal are covered by ADR-0198's scopes. What is missing is the operational list: a
customer who says "do not contact me about loans" without revoking their email consent, a
complaint-linked block (ADR-0085), an RM-managed relationship hold. Today the only model for "stop" is
all-or-nothing scope revocation, which forces customers who want one narrow stop to switch everything
off — and then be re-asked, because the consent is gone rather than the topic suppressed.

## Decision

We will introduce a **`ContactPolicyGate` as a shared primitive in `openbank-libs-runtime`** that every
customer-originating touch must pass, and we will make its state reconstructable from the event log.
It wraps — in one call — the ADR-0198 consent check, the ADR-0200 D6 frequency cap and quiet period,
and a new suppression list, behind an explicit contact-class taxonomy. It extends those ADRs; it does
not replace their mechanisms.

**D1 — Three contact classes, decided once.** (1) **OUTBOUND_SEND** — bank-initiated delivery (email,
push, SMS, operator message, finance-coach insight): counted against the per-party cap and the quiet
period (defaults as ADR-0200 D6, configurable only by a platform admin). (2) **PROMOTIONAL_IMPRESSION** —
rendering personalised promotional content on a customer-initiated surface (ADR-0212): counted against
a *separate, higher* impression budget (default 1 promotional surface/day), never against the send cap;
budget exhaustion degrades the surface to default content, never blocks app functionality. (3)
**SERVICE_EXEMPT** — transactional/security/service content and non-personalised default surfaces:
never counted, never gated, exactly as ADR-0198 D4 leaves the SECURITY category untouched.

**D2 — Counters are cache-backed but log-derived.** Cap and budget counters live in Valkey for
latency, but every gate decision that results in a counted contact is reflected in the sender's
outbox-published event (notification send records, ADR-0212's `engagement.events`). On cold start or
detected flush, the gate **rebuilds counters by replaying the trailing cap window of those events
before reopening** — and fails closed while rebuilding. A silent cache reset can therefore never
produce a contact burst: the anti-spam control is derivable from the log we already keep, not
dependent on cache survival.

**D3 — A suppression list with reason codes, distinct from consent.** A platform-level do-not-contact
entry carries `(partyId, scope|topic|ALL, reasonCode, source)` — reason codes: customer opt-out,
complaint (ADR-0085), RM-managed, legal hold, deceased. Sources: the customer preference centre, the
complaints flow, the RM workbench (ADR-0213/0214). The gate evaluates suppression *before* consent, on
the ADR-0200 D6 ordering principle: a customer who consented to email marketing can still be someone
the bank must not contact about a specific topic today.

**D4 — Mandatory call sites, CI-enforced.** The gate is invoked by: notification-service (the ADR-0198
D4 choke point — its consent call becomes this gate call), campaign journeys (ADR-0200 D2's per-step
check), finance-coach and any agent-proposed contact (ADR-0203/0214 — an agent's output becomes a
customer touch only through this gate), engagement surfaces (ADR-0212), and RM-initiated sends (ADR-0214).
Adherence is enforced by a **contract test in `openbank-libs`** that fails a service build when a
marketing-class touch path bypasses the gate — the same governance-as-code style as the fleet's other
cross-cutting invariants, because gap 1 showed "every sender checks" is a convention, not a control.

**D5 — The gate stays a library, not a service.** An in-process component over consent-service (live
per-call validation, per ADR-0195's rule that a cached consent survives its own revocation) and the
D2 counters. No network hop of its own beyond the consent call ADR-0198 already accepts; no new
failure domain on the send path. A gate outage fails closed for classes (1) and (2) and never affects
class (3).

## Alternatives considered

- **Leave suppression per sender (status quo after ADR-0200).** campaign-service implements D6 for
  itself, and each future sender is pointed at the ADR text. Rejected on gap 1: the estate already has
  three senders named in accepted ADRs and the finance-coach citation shows the drift has started —
  a rule that lives in one service but is cited by three is a copy waiting to diverge.
- **Extend notification-service into the policy owner.** It is the choke point for *sends*. Rejected:
  it cannot see impressions (ADR-0212 surfaces never pass through it) or agent/RM proposals, and
  ADR-0200's alternatives already rejected loading cohort/policy logic into the service every sender
  depends on for delivery.
- **Counters in Postgres only.** Durable by default, no rebuild path needed. Rejected: a synchronous
  Postgres write per gate decision on a campaign fan-out is the wrong latency/throughput profile for
  what is a hot read-mostly counter; the D2 log-rebuild gets durability without the hot-path write.
- **No impression budget at all — count only sends.** Simplest, and matches the statute's narrowest
  reading. Rejected: an app that shows a new promo card on every open is spam with extra steps, and
  "we only counted what the statute names" is not a defence a conduct reviewer accepts when the
  customer experience is identical to over-contacting.

## Consequences

**Positive**
- Every current and future sender inherits consent, caps, quiet hours and suppression from one
  reviewed component — adding a sender type stops being a compliance review of its politeness logic.
- The silent-reset burst scenario (gap 2) is eliminated by construction: counters are derivable, and
  the gate refuses to open until they are.
- The impression/send split lets ADR-0212 build surfaces without either spamming customers or
  degrading the app — the two failure modes of not deciding gap 3.
- Customers get granular stop ("not about loans") without all-or-nothing consent loss — measurable as
  retained consent coverage instead of revocations.

**Negative**
- consent-service and the event log become load-bearing for every touch type; D5's fail-closed
  semantics mean a consent outage pauses marketing entirely (never banking). Accepted, and it must be
  monitored as a campaign-affecting dependency, per ADR-0200's own consequence note.
- The rebuild-on-flush path adds a cold-start mode to every gated service that must be tested
  (including the "rebuild takes minutes during a big campaign" case).

**Neutral**
- ADR-0200 D6's cap/quiet-period defaults and ADR-0198's consent mechanism are unchanged; this ADR
  re-homes them behind the gate and adds what they did not cover.

## Compliance impact

- PCI DSS: not applicable — the gate carries identifiers and counters, no cardholder data.
- DORA: the gate is an ICT supporting component for customer communication; its fail-closed
  dependency on consent-service is an operational-resilience note for that service's register entry.
- GDPR: Art. 7(3) and Art. 21(2) — withdrawal/objection take effect at one point for every touch
  type, including the topic-level suppression of D3, which is the granular form of the direct-
  marketing objection. Art. 5(1)(c) — counters and suppression entries are the minimum state required
  to honour those rights.
- PSD2: not applicable — no account access; marketing scopes remain outside the RTS per ADR-0198.
- CNB: Act No. 480/2004 Coll. §7 governs the OUTBOUND_SEND class; D1's class taxonomy is the
  engineering reading of "bank sends" versus "customer opens", carried over from ADR-0198's analysis
  — to be confirmed with counsel before it is cited in a ROPA.

## References

- [ADR-0198](0198-marketing-consent-as-a-first-class-consent-service-scope.md) — the consent check
  this gate wraps; D4's choke point becomes a D4 call site here.
- [ADR-0200](0200-campaign-journeys-as-temporal-workflows-with-consent-gated-delivery.md) — D6's
  cap/quiet-period/exclusions, re-homed from campaign scope to platform scope.
- [ADR-0195](0195-mcp-server-caller-authentication-and-psd2-consent-binding.md) — live per-call
  validation over cached consent.
- [ADR-0085](0085-complaints-handling.md) — complaint-linked suppression reason codes.
- [ADR-0203](0203-business-plane-ai-agents.md) — finance-coach's "gated by ADR-0200's suppression
  rules" citation, made callable.
- [ADR-0212](0212-in-app-engagement-surfaces-gamification-and-pre-approved-offers.md) — the impression
  class's producer.
- [ADR-0213](0213-campaign-studio-the-campaign-authoring-operator-experience.md) and
  [ADR-0214](0214-offer-explanation-and-relationship-manager-agents.md) — suppression administration
  and RM-path call sites.
