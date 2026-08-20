---
date: 2026-08-20
decision-status: proposed
delivery-status: planned
authors: [jiri.raska]
supersedes: []
superseded-by: []
delivery-repos: []
tags: [mobile-app, admin-ui, ledger, governance]
summary: "MGM referrals and promo codes become governed incentive artifacts outside campaign-service; the first slice is a fixed-reward referral lifecycle with immutable attribution, anti-abuse and ledger handoff."
followup: "#5943 — implementation issue for the fixed-reward referral vertical slice; a separate money-path ADR is required before reward posting."
---

# ADR-0266 — MGM Fixed-Reward Growth Incentives

## Context

Campaign Studio now has governed audiences, catalogue-backed content, journey orchestration,
four-eyes activation and measured engagement. It can distribute an offer, but it cannot represent
the growth product that makes the offer valuable:

- **Member-get-member (MGM)** needs an invitation, durable referrer/referee attribution, a
  qualifying event, an eligibility decision, an idempotent reward and a reversal path. A campaign
  send-log is not an attribution ledger and cannot own those invariants.
- **Promo codes** need issuance, versioned terms, scope, expiry, redemption limits, reservation and
  commit semantics, replay protection and audit. A free-form campaign variable or template string
  cannot provide those guarantees.

The repository already contains adjacent but deliberately narrower primitives. Account activation can
issue a one-time welcome bonus, and engagement has an opt-in rewards hub with opaque points and
catalogued challenges. Neither is an MGM relationship, a redeemable code, or a financial reward
contract. The campaign service must therefore not grow a second reward ledger or a code parser.

The first production-shaped slice should be small enough to prove the hard invariants: one fixed
reward, one qualifying event, one invitation token, and one authoritative ledger handoff. It must
also be safe to stop, expire or reverse without making a campaign appear converted when no reward
was earned.

## Decision

We will model growth incentives as governed domain artifacts outside `openbank-campaign-service`.
Campaign Studio will select and distribute a reviewed incentive reference and consume its outcome
events; it will never mint codes, decide reward eligibility, or post money.

### Bounded-context ownership

1. **Referral/MGM context** owns the referral relationship and lifecycle:
   `INVITED → ATTRIBUTED → QUALIFIED → REWARD_REQUESTED → REWARDED`, with explicit `EXPIRED`, `REJECTED` and
   `REVERSED` outcomes. It owns opaque invitation tokens, immutable referrer/referee binding,
   attribution windows, self-referral and same-party checks, one-reward-per-qualification
   idempotency, and anti-abuse decisions. It stores no campaign copy.
2. **Incentive/Promo context** owns versioned offer definitions and code inventory. A promo offer
   has immutable terms, product scope, effective/expiry times, total and per-party limits,
   stacking policy and a maker/checker publication state. Redemption is a reservation followed by a
   commit (or release), keyed by an idempotency token; a retry cannot consume inventory twice.
3. **Ledger/account** remains authoritative for monetary reward posting and reversal. The referral
   context may request a posting with a stable reward reference, but cannot mark a reward as paid
   from its own database. The referral context alone owns its state machine: it records
   `REWARD_REQUESTED` first and moves to `REWARDED` only from an authenticated ledger-accepted
   outcome; a rejection or timeout records a retryable/rejected outcome and never a local payment
   success. Ledger/account owns only the posting outcome and reversal; it never writes the referral
   lifecycle. A reconciler correlates the durable request, ledger response and reversal event by the
   same reward reference. Non-monetary points may continue to use engagement's rewards hub, but
   points and money must not share a success state.
4. **Campaign Studio** owns audience selection, content, journey execution and measurement. A
   campaign stores an immutable `incentiveOfferRef`/`referralProgramRef` plus attribution metadata;
   it records exposure and conversion evidence from events, not a copied reward amount or a mutable
   code definition.

### First slice: fixed-reward MGM

The first implementation will deliver one fixed reward and no arbitrary discount expression:

- create a versioned referral program with a fixed reward reference and qualification rule;
- issue an opaque invite token/deep link without putting a customer identifier in the URL;
- bind the first accepted invite to exactly one referrer and referee, rejecting self-referral,
  duplicate attribution and expired programs;
- consume one named qualifying event (for example, an account activation) exactly once;
- emit `referral.qualified.v1` and `referral.reward.requested.v1` with a stable idempotency key;
- model the ledger/account handoff as a contract boundary: the first slice uses a test double or
  contract fixture for authoritative accepted/rejected outcomes; production posting remains blocked
  until the separate money-path ADR and threat model are approved;
- emit a reversal outcome for an invalidated qualification, without silently deleting history.

Promo-code issuance and redemption are a subsequent slice. They must not be represented by a
`promoCode` text field in campaign-service or by a direct discount mutation in account-service.

### Event and governance boundaries

Events carry opaque aggregate and program/revision references, schema versions and correlation IDs;
they do not carry campaign copy or unnecessary party data. Every externally visible state transition
is append-only/auditable and replay-safe. Program/offer revisions are immutable after publication;
runtime publication and any money-path reward policy use the existing maker/checker discipline.

Campaign activation remains the existing four-eyes gate. Incentive publication and reward-policy
changes are separate approval subjects, so approving a campaign cannot approve a financial reward
definition by implication. A future money-path implementation must add its own threat-model and
contract-test evidence before deployment.

### Required proof for the first implementation PR

The implementation is not complete until a real HTTP/integration path proves:

1. program draft → independent publication → invite issuance;
2. accepted invite attribution and rejection of self-referral, duplicate and expired invites under
   replay and concurrency;
3. exactly-once qualification and reward request under replay/concurrency;
4. the ledger boundary contract: an accepted/rejected/reversed handoff, duplicate-reference
   rejection and idempotent retry using a test double or contract fixture; production money-path
   posting itself remains blocked until the separate money-path ADR and threat model are approved;
5. Campaign Studio exposure/attribution projection without treating a click as a reward; and
6. authorization, audit evidence, retention/deletion handling and contract replay for every new event.

## Alternatives considered

- **Put MGM and promo codes in `campaign-service`.** Rejected: campaign orchestration would become a
  second incentive ledger, code inventory owner and financial reward authority, coupling retries and
  reversals to delivery workflows.
- **Extend engagement rewards hub for all incentives.** Rejected: its points/challenge model is
  intentionally non-monetary and opt-in; making it a money or referral authority would violate its
  current boundary and blur points with posted value.
- **Start with arbitrary percentage discounts.** Rejected: a fixed reward proves attribution,
  idempotency and ledger handoff first; an arbitrary discount engine would require evaluating
  pricing, stacking, tax and product scope before the core MGM invariant is proven. Stacking policy
  remains a governed field for later published offers, but is not evaluated by this first fixed-reward
  slice.
- **Use a signed customer identifier in the invite URL.** Rejected: an opaque server-owned token is
  the minimum necessary reference and keeps party identity out of the URL and ordinary client-side
  analytics. It does not prevent server-side correlation, so token values must still be redacted from
  application logs and analytics exports and the attribution store must enforce its retention policy.

## Consequences

**Positive**
- Campaign Studio stays an app-first orchestrator rather than becoming a financial subsystem.
- MGM attribution, code redemption and reward posting have explicit ownership and replay boundaries.
- The fixed-reward slice creates a real E2E path before broad promo or discount semantics are added.
- Existing rewards-hub and welcome-bonus behavior remain compatible and auditable.

**Negative**
- The first slice needs at least one new bounded context and cross-service event contracts.
- A campaign cannot promise a reward from its own local state; operators must inspect the incentive
  and ledger outcomes separately.
- Promo-code and discount features remain intentionally unavailable until their own governance is
  specified.

**Neutral**
- This ADR defines boundaries and proof obligations; it does not claim that MGM, promo codes or a
  full growth platform are shipped.

## Compliance impact

- PCI DSS: not applicable to the fixed-reward domain model; operators must assess any payment
  instrument or card data introduced by a concrete reward implementation.
- DORA: resilience, replay and reversal evidence are required for a deployed implementation; this
  ADR ships no runtime component.
- GDPR: invite URLs use opaque references and the first slice must define retention/deletion handling
  for attribution records before production use.
- PSD2: not applicable to campaign orchestration; any reward posting must preserve the bank's
  existing authorization and ledger controls.
- CNB: customer-facing incentive terms and disclosures require product/legal review before a
  published program; this ADR does not publish terms.

## References

- ADR-0200 — campaign journeys as Temporal workflows with consent-gated delivery.
- ADR-0220 — in-app engagement surfaces and the deliberately non-monetary rewards hub.
- ADR-0245 — conversion measurement and the distinction between engagement and product outcomes.
- ADR-0030 — threat-model and approval expectations for money-path changes.
