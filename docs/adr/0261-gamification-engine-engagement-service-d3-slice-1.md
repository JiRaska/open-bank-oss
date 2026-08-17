---
date: 2026-08-16
decision-status: accepted
delivery-status: partial
authors: [Jiri Raska]
supersedes: []
superseded-by: []
delivery-repos: []
tags: [mobile-app, privacy-gdpr, lending, compliance]
summary: "First real slice of ADR-0220 D3: a closed EarnSource catalogue, an opaque non-monetary Points type, opt-out as an explicit domain operation, an attributed GamificationAward event, and a detekt rule keeping reward state out of lending."
followup: "#3701 — REST surface for opt-in/opt-out and challenge targeting, and the savings/login/repayment earn sources ADR-0220 D3 names but this slice does not wire"
---

# ADR-0261 — Gamification engine — engagement service D3 slice 1

## Context

ADR-0220 D3 already decided that `openbank-engagement-service` gets a gamification engine —
`Challenge`, `Streak`, `Badge`, `Points`, evaluated event-driven, with five hard invariants
("no challenge may reward credit uptake", "opt-in and one-tap opt-out that keeps earned value",
"no fake urgency", "reward economics capped and billed, never marketing cash",
"vulnerable customers excluded from targeting"). D1/D2 of that ADR shipped
(`ResolveSurfaceUseCase`, `RecordEngagementEventUseCase`, the `EligibilitySnapshot`/`AdverseState`
materialisation) — D3 itself had zero code: no `EarnSource`/`Streak`/`Badge`/`Points`/
`EngagementProfile` vocabulary existed anywhere in the fleet before this ADR (verified by grep with
a known-positive control against `origin/main`, issue #3701). This ADR is the first slice that
makes D3 real rather than restating it.

Three things make "just build the ADR-0220 D3 bullet list" insufficient on its own:

- **A reward mechanism sits one step from the exact conduct risk ADR-0220 itself names.** Its own
  "Alternatives considered" rejects "gamify engagement with credit products (take a loan, earn
  points)" *absolutely* — "the textbook mis-selling pattern, and no conversion figure justifies a
  conduct finding." A closed `EarnSource` catalogue makes the *forward* direction (rewarding credit
  uptake) a compile error. Nothing in ADR-0220 addresses the *reverse* direction — gamification
  state feeding back INTO a lending or credit-decisioning eligibility calculation, which would
  recreate the same risk with no catalogue entry for review to catch. That gap is this ADR's D6.
- **Every prior fleet defect this repo has paid for in the audit/attribution space has the same
  shape**: a value that means "no person did this" sharing a boolean or a `null` with genuine
  attribution, an outbox event with no correlation back to what caused it, a sentinel default that
  reads fine until something depends on its magnitude. A `GamificationAward` — a reward record with
  real financial-adjacent weight (D3.4: "provisioned through the billing path, never marketing
  cash") — is exactly the kind of row that class of defect targets. This ADR requires the SAME
  `EventActor` SYSTEM-attribution vocabulary and a real correlation id from day one, not as a
  follow-up hardening pass.
- **`Points` is money-shaped enough to become money by accident.** A per-customer, per-year-capped,
  billing-provisioned counter is one convenience method away from being treated as a spendable
  balance by a call site that never meant to cross that line. ADR-0220 D3.4 already forbids the
  outcome ("never marketing cash"); this ADR forbids the *shape* that makes the mistake reachable.

## Decision

We build the first real slice of ADR-0220 D3 in `openbank-engagement-service`, reusing D1/D2's
already-shipped infrastructure (`ContactPolicyGate`, `EligibilitySnapshot`/`AdverseState`, the
`EngagementOutboxDispatcher`/`AbstractOutboxDispatcher` pair, `EngagementEvent`) rather than
forking a parallel mechanism. Slice 1 is deliberately scoped: it awards points for one real,
already-observable signal (educational-content completion, reusing this service's own `CONVERSION`
`EngagementEvent` stream) rather than fabricating Kafka consumers for savings deposits, logins or
on-time repayments — no such topics exist in this codebase yet, and inventing a consumer for a
topic that isn't there is exactly the "finrep called a ledger path that never existed" class of
defect this repo has already measured once (`.claude/rules/kotlin-quarkus.md`'s Pact section). What
IS built is real end-to-end: domain rules, Postgres persistence, an outbox event with full
attribution, and a compile-time module boundary — not a stub behind an unused interface.

**D1 — `EarnSource` is a closed sealed catalogue, not a string or an open enum.**
`EarnSource.EducationalContentCompletion` (wired), `SavingsGoalDeposit`, `LoginStreak`,
`OnTimeRepayment` (declared per ADR-0220 D3's named signal set, no consumer yet — same
"declared ≠ wired" convention `AdverseState`'s KDoc already established for D1). A `when` over
`EarnSource` with no `else` branch fails to compile the moment a variant is added without updating
every consumer, and no variant naming a credit-uptake reason can exist without a reviewed PR
touching this one file.

**D2 — `Points` is an opaque, non-negative counter with NO path toward a monetary type.** A
`value class` wrapping `Int`, private constructor, `Points.of(n)` the only construction path. It
carries `plus` and `compareTo` and nothing that resembles `toMoney()`, an FX conversion, or any
method whose name mentions money/currency/amount/ledger — verified by a reflection-based test over
the compiled type, not merely absent from a code review. The conversion this platform will
eventually want (per D3.4's billing provisioning) belongs entirely outside the domain layer, as an
explicit, independently reviewed call into the ADR-0143 billing path — never a method on this type.

**D3 — Opt-out is an explicit domain state transition, never a mutable flag.**
`RewardsHubMembership` is a sealed `OptedIn`/`OptedOut` state; the only way to move between them is
`RewardsHubMembershipTransitions.optIn`/`optOut`, each producing a fresh, independent value. There
is no path anywhere in the gamification package that reads a stored `OptedOut` and silently returns
it as `OptedIn` — reversing an opt-out requires a second, equally explicit `optIn` call, proven by a
test that performs both transitions in sequence and asserts each intermediate state, not only the
final one. Per D3.2, opting out changes ONLY future targeting eligibility
(`EvaluateChallengeTargetingUseCase`); nothing in this package has a handle on the award ledger, so
it is structurally incapable of touching already-earned `Points`.

**D4 — Reused, not re-derived: `EligibilitySnapshot`/`AdverseState` for vulnerable-customer
exclusion, `ContactPolicyGate` for consent.** `EvaluateChallengeTargetingUseCase` — the ONE
`@MarketingCallSite` in this slice, wired exactly like `ResolveSurfaceUseCase` and enforced by the
same `MarketingCallSiteWiringRule` (ADR-0219 D4) — decides whether a party may be proactively
INVITED into a new challenge. It reads the same `EligibilitySnapshot`/`AdverseState` contract D1
already materialises (never a second, gamification-local copy of the adverse-state rule) and the
same `ContactPolicyGate.check(...)` every other marketing touch in this service goes through.
`AwardGamificationPointsUseCase` — which pays out `Points` for something a party ALREADY did — is
deliberately NOT annotated and does NOT consult adverse state: ADR-0220 D3.5 excludes vulnerable
customers from *targeting*, "while remaining free to use the hub on their own initiative," and this
use case only ever fires after an organic completion. Conflating the two would silently withhold an
already-earned reward from exactly the party the ADR says must keep receiving it.

**D5 — Every `GamificationAward` carries a real correlation id and explicit SYSTEM attribution.**
`correlationEventId` is the triggering `EngagementEvent` row's own generated id — `save()` now
returns it — never a freshly minted id at award time (a correlation id pointing at nothing durable
is decoration, not correlation). The outbox payload carries `earnSourceId`, `ruleVersion`
(`GamificationAwardRule.RULE_VERSION`, frozen per-award so a later rule change never silently
reattributes history), the correlation id, and `actorType=SYSTEM` /
`actorId=system:engagement:gamification-award-rule` via the fleet's shared
`com.openbank.libs.domain.event.EventActor` — never a locally invented sentinel. A unique
`(party_id, challenge_id)` database index (deliberately NOT including the correlation id — a
one-time challenge reported twice via two separate `EngagementEvent` rows must still award only
once, which the wider key failed to guarantee and `GamificationOutboxIT` caught) makes the award
idempotency guard a real constraint, not merely an application-level best effort.

**D6 — A compile-time module boundary: lending/credit-decisioning must never import gamification
state.** `GamificationModuleBoundaryRule` (new `openbank-module-boundaries` detekt ruleset,
`openbank-libs-detekt-rules`, same text-based / no-type-resolution style as
`MarketingCallSiteWiringRule`) fails the build if any file whose own package starts with
`com.openbank.lending` or `com.openbank.decisioning` imports anything under a `.gamification`
package. No fleet module can literally do this today — each `openbank-*-service` is its own Gradle
module with no compile dependency on another service's module — so this is deliberately
*preventive*: the guard that fires the day gamification types are ever promoted into a shared
module (`openbank-libs-*`) or a service gains a real compile path to them, closing the reverse
direction of the mis-selling risk D1's closed catalogue already closes going forward.

**D7 — No new REST surface in this slice.** Opting in/out and challenge-targeting evaluation are
real, unit-tested domain operations reachable today only from within the service (no
`@Path`-annotated endpoint). Adding a customer-facing endpoint is real work with its own consent/
authz review (an M2M-scoped customer-edge policy, `@Authorize` action wiring, an OpenAPI contract
bump) that this ADR does not do casually alongside the domain slice — the same "infrastructure
before the customer surface" ordering D1's `EligibilitySnapshot` materialisation already used, and
the same honest-dependency discipline ADR-0220 D5 applies to pre-approved offers. Tracked in
`followup`.

## Alternatives considered

- **Wire the full D3 signal set (savings deposits, logins, on-time repayments) in this slice.**
  Rejected: none of those producers exist in this codebase today (no `openbank-savings-service`, no
  login event topic, no repayment-completion event found by grep across the fleet). Building a
  consumer against a topic that doesn't exist is exactly the class of defect
  `.claude/rules/kotlin-quarkus.md`'s Pact section already measured once
  (finrep calling a ledger path that never existed, passing every mocked-port unit test). Slice 1
  awards from a signal this service can observe for real, end-to-end, today.
- **Model `Points` as a plain `Int` or a type alias.** Rejected: neither carries the non-negative
  invariant or blocks a future `+ Money` overload from compiling; an opaque `value class` with a
  private constructor makes both structural rather than a code-review reminder.
- **Model rewards-hub membership as a `Boolean` column.** Rejected: a boolean can be flipped in
  place by any code with a reference to the row; a sealed state plus an explicit transitions object
  is what makes "opt-out is a domain operation, not a mutation" checkable in a unit test rather than
  asserted in a comment.
- **Skip the module-boundary detekt rule since no module can import gamification state today
  anyway.** Rejected: that argument is exactly why the rule needs to exist NOW, while it's free — it
  is cheap to add before there is any real import to break, and the day a shared-module refactor
  makes the import possible is the wrong day to first ask whether it should be forbidden.
- **Ship a REST endpoint for opt-in/opt-out in this slice, since the domain operation already
  exists.** Rejected: a customer-facing endpoint needs its own consent/authz review and OpenAPI
  contract bump this ADR does not want to bundle unreviewed alongside the domain design; D7 records
  this as a deliberate sequencing choice, not an oversight.

## Consequences

**Positive**
- ADR-0220 D3 stops being a bullet list with zero code — one earn source, one challenge, is real
  end-to-end: domain rule, Postgres row, attributed outbox event, and a passing integration test
  that reads the actual committed row rather than a mock.
- The two module-boundary risks D1 and D6 close between them (catalogue forward, import boundary
  backward) make "gamification cannot touch credit decisions" a property CI enforces, not a
  reviewer's memory.
- The correlation-id and `EventActor` attribution discipline (D5) means a `GamificationAward` row is
  never the kind of "who did this" gap `.claude/rules/kotlin-quarkus.md` already catalogues at
  length for other producers in this fleet.

**Negative**
- Slice 1's earn source coverage is intentionally thin (one of four named signals). A reviewer
  expecting the full ADR-0220 D3 signal set will find three declared-but-unwired `EarnSource`
  variants — tracked, not hidden, but real remaining work.
- No REST surface means the KMP client cannot yet call opt-in/opt-out or receive a challenge
  invitation from this slice alone; that is D7's deliberate sequencing, but it does mean this ADR
  alone does not make `rewards_hub` customer-reachable.
- A second detekt ruleset (`openbank-module-boundaries`) restamps every service's forked-CLI
  classpath dependency the same way `openbank-contact-policy` already does — no OPA bundle impact
  (detekt config lives outside `rules.yaml`/`.rego`), but it is one more fleet-wide static-analysis
  rule every module's build now evaluates.

**Neutral**
- `openbank-engagement-service` is not in `rules.yaml: money_path_services` (confirmed against live
  `origin/main` before starting this slice) and this ADR's changes stay entirely within that
  service plus the shared `openbank-libs-detekt-rules` module — no money-path service is touched,
  so the standard single-review PR path applies, not the two-approval/threat-model path (ADR-0030).

## Compliance impact

- PCI DSS: not applicable — no cardholder data anywhere in this slice.
- DORA: not applicable on its own — no new customer-facing ICT service (D7 ships no new endpoint);
  the eventual REST surface's DORA posture is deferred to the follow-up that adds it.
- GDPR: Art. 7 — challenge targeting requires the same `MARKETING_COMMS_INAPP` consent scope
  ADR-0220 D1 already gates surfaces on, checked through the same `ContactPolicyGate`; no new
  consent scope is introduced. Retention of `gamification_award` rows follows the same ADR-0118
  policy already governing `engagement_event`.
- PSD2: not applicable — no account access, no payment initiation.
- CNB: Act No. 634/1992 Coll. / UCPD — this ADR's D1 (closed `EarnSource` catalogue) and D6 (module
  boundary) are the structural controls implementing ADR-0220's own D3 rule 1 ("no challenge may
  reward credit uptake") and its "Alternatives considered" rejection of gamifying credit products;
  D3's "no fake urgency" invariant is implemented one level down, in `Challenge`'s constructor
  invariant (a deadline cannot be constructed without `genuineExpiry=true`), not restated here as a
  new claim.

## References

- [ADR-0220](0220-in-app-engagement-surfaces-gamification-and-pre-approved-offers.md) — the parent
  decision this ADR delivers a first slice of (D3); D1/D2's `EligibilitySnapshot`/`AdverseState`/
  `ContactPolicyGate` infrastructure reused here rather than re-derived.
- [ADR-0219](0219-platform-contact-policy-gate-contact-classes-durable-counters-suppression.md) —
  `ContactPolicyGate` and `MarketingCallSiteWiringRule` (D4), the pattern D6's module-boundary rule
  follows.
- [ADR-0050](0050-transactional-outbox-with-a-fleet-wide-dispatcher.md) — the outbox mechanism D5's
  `GamificationAward` event reuses (`EngagementOutboxDispatcher`/`AbstractOutboxDispatcher`), no new
  dispatcher.
- [ADR-0143](0143-runtime-product-fee-posting-via-a-dedicated-billing-service.md) — the billing path
  D3.4/D2's "never marketing cash" invariant points any future Points-to-value conversion at.
- [ADR-0030](0030-money-path-two-person-review-and-threat-modeling.md) — confirms
  `openbank-engagement-service` is outside `money_path_services`, so this ADR's changes take the
  standard single-review path.
