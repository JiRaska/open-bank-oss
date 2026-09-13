---
date: 2026-09-13
decision-status: proposed
delivery-status: planned
authors: [Jiri Raska]
supersedes: []
superseded-by: []
delivery-repos: [openbank-app]
tags: [customer-edge, mobile-app, kafka, ledger]
summary: "Referrals qualify from recorded account-opened facts at event or attribution time, referrers earn Lístky from referral.qualified, engines confirm benefits, the edge publishes capability availability; reward posting awaits approval."
---

# ADR-0310 — Growth lifecycle: referral qualification, reward settlement, loyalty earn and benefit application

## Context

The customer app ships member-get-member ("Pozvi přátele") and Lístky screens against the
customer-edge routes added in edge OpenAPI 1.65.0 (`CustomerReferralResource`,
`CustomerLoyaltyResource`). It keeps both in a labelled demo mode, because the lifecycle behind
the routes does not exist end to end. Measured on `main` on 2026-09-13:

1. **A referral never qualifies.** `ReferralService.qualifyInvite` is reachable only through
   `POST /api/v1/referrals/invites/{token}/qualify`, and nothing calls it. The published programme's
   `qualifyingEvent` is the key `account.opened`; account-service publishes `AccountCreated`
   (`AccountCreatedEvent`, `eventType = "AccountCreated"`) on `openbank.accounts.account.created`
   through `KafkaAccountEventPublisher`, and referral-service has no consumer of it. The ordering
   is the harder half: a referee opens the account during onboarding, *before* they can sign in and
   redeem the invite at `POST /customer/v1/referrals/attributions`. A consumer that qualifies only
   invites already `ATTRIBUTED` misses almost every real referral.
2. **The reward is never posted.** `RewardRequested` has no consumer and nothing produces
   `RewardOutcome`; `applyLedgerOutcome` is documented as "contract boundary only". On `main` the
   publisher is `UnwiredReferralEventPublisher`, which drops every event; open PR #8859 replaces it
   with a transactional outbox relaying to `openbank.referral.qualified.v1`,
   `openbank.referral.reward-requested.v1` and `openbank.referral.reward-outcome.v1`. ADR-0266
   §3 makes ledger/account authoritative for posting and its front-matter requires "a separate
   money-path ADR ... before reward posting". No such decision exists.
3. **No Lístky are earned for a referral.** `LeafEarnSource.QualifiedReferral` (250 Lístky in
   `EarnCatalog`) has no producer; loyalty-service has no `@Incoming` channel at all.
4. **A benefit never becomes active.** `BenefitGrantStatus.GRANTED` means "owed and published",
   never applied (`BenefitGrant.kt`, loyalty-service `CLAUDE.md`). The three delivering engines
   named by `BenefitEngine` — billing's `WaiverEvaluator` (ADR-0143), an `InterestTier` read by
   interest-service, and fx-service's reference-rate conversion — contain no loyalty code, and
   `WaiveCondition` can test only BALANCE, MONTHLY_TURNOVER, AGGREGATE_POCKET_BALANCE, SEGMENT and
   CURRENCY. The app therefore can never truthfully say "aktivní".
5. **Neither service is really deployed.** loyalty-service has no gitops workload (#8793); the
   customer edge deliberately carries no loyalty URL and answers 503 `LOYALTY_UNAVAILABLE`;
   referral-service's Deployment runs `replicas: 0`.

**What the repository already has for paying a reward — searched before deciding.**

- `openbank-incentive-service` reserve/commit (`PromoReservation`) is budget and inventory
  bookkeeping. The reservation carries no amount, currency or account; the service has no ledger or
  transaction client; its contract says the topic "does not authorize pricing, settlement, account
  mutation or cash rewards".
- The only reward that moves money is account-service's welcome bonus (ADR-0267 decision 4): a
  `POST /api/v1/transactions` `CREDIT` whose journal debits `1100 Customer Cash Clearing` and
  credits `2100 Customer Deposit Control`. ADR-0267 itself calls it sandbox-only because it
  "conjures money that no funding leg backs". No expense is recognised.
- billing posts balanced journals to `POST /api/v1/journals` through its own ledger outbox
  (fee: debit `2100`, credit `4003 Fee Income`); a waiver moves no money.
- The ledger chart of accounts (V1–V26) has **no** marketing, promotion, referral, cashback, bonus
  or loyalty-provision account.

**There is no accounting treatment for marketing rewards in this repository.** This ADR does not
invent one as a decided fact; D2 below proposes one for human approval.

## Decision

### D1 — Referral qualification is decided from recorded account-opened facts, at whichever of the two moments comes second

1. **Naming.** The programme's `qualifyingEvent` stays the domain key `account.opened`. Published
   programme revisions are immutable (ADR-0266), and `AccountCreated` is a discriminator read
   verbatim by balance, document, statement and campaign consumers, so neither side is renamed.
   referral-service owns a single, closed translation table in its Kafka adapter:
   `AccountCreated` on `openbank.accounts.account.created` → `account.opened`. An event type not in
   the table is ignored.
2. **Record the fact first.** On `AccountCreated`, referral-service stores
   `referral_qualifying_fact(party_id, event_name, event_id, source_ref, occurred_at, recorded_at)`
   where `event_id` is the envelope `eventId`, `source_ref` the account id (`aggregateId`) and
   `occurred_at` the event's own `occurredAt`. `event_id` is unique (a Kafka redelivery is a
   no-op) and `(party_id, event_name)` is unique (the **first** account a party opens is the fact;
   a second account is not a second qualification). A record without a parseable `eventId`,
   `partyId` or `occurredAt` is logged and skipped, never guessed.
3. **Qualify at event time** when the party is already the referee on an `ATTRIBUTED` invite.
4. **Qualify at attribution time** when a fact already exists for the referee. This runs on a
   fresh attribution *and* on the idempotent same-referee replay, so a client retrying
   `POST /customer/v1/referrals/attributions` also retries a qualification that failed after the
   attribution committed. An *ineligible* fact never fails an attribution (it is audited); an
   *infrastructure* failure while qualifying surfaces as an error, precisely so that the client's
   idempotent retry completes the qualification instead of it being lost behind a 200.
5. **The eligibility rule is identical at both moments** and lives in one pure function: the
   programme is `PUBLISHED`, its `qualifyingEvent` equals the fact's `event_name`, and the fact's
   `occurred_at` lies in `[invite issued, invite expiresAt)`. "Invite issued" is the invite's
   `INVITE_ISSUED` audit instant — the only place it is recorded; when that row is missing the
   invite does **not** qualify. The lower bound is what stops an existing customer redeeming a code
   against an account opened long before the invite existed.
6. **Idempotency.** The qualification event id is the fact's `event_id`, so the existing
   `referral_reward unique(invite_id, qualification_event_id)` key deduplicates both paths against
   each other, and the reward reference stays `referral-<inviteId>-<eventId>`.
7. **Anti-abuse, limited to signals that exist.**
   - self-referral: the existing `referrerPartyId == refereePartyId` rejection (409 `SELF`);
   - one invite, one referee: the existing `ALREADY_ATTRIBUTED` rejection;
   - **one reward per referee per programme**: a unique index on
     `referral_reward(referee_party_id, program_id)`. A referee who redeems several codes is
     rewarded once; every later candidate is recorded as a `QUALIFICATION_REJECTED` audit row
     with `reason=REFEREE_ALREADY_REWARDED`, not silently dropped;
   - one fact per party (item 2) and the issue-time lower bound (item 5).
   **Same household and same device are not decided here.** No household or device signal reaches
   referral-service on `main`; delegation-service's household relationships (ADR-0282 D9) are the
   nearest candidate and are named as a follow-up, not assumed.
8. **Transport** follows #8859's outbox: `Qualified` and `RewardRequested` are written in the same
   transaction as the reward row. The consumer channel is `account-created-in`, group
   `openbank-referral-service`, `failure-strategy: dead-letter-queue` with the **nested**
   `dead-letter-queue: topic: openbank.dlq.referral.account-created-in` form, a DLQ `KafkaTopic`,
   and KafkaUser ACLs Read+Describe on the topic, Read on the group, Write+Describe on the DLQ.
   A deterministic failure (bad data) is acked; a transient one is retried with `EventRetry` and
   then rethrown to dead-letter.

### D2 — Reward settlement: billing-service posts it to a new expense account (**Proposed — needs human approval; not implemented**)

No existing rail recognises a marketing expense (Context). The proposal, for a human decision:

1. **Owner: `openbank-billing-service`.** It already posts balanced journals through an idempotent
   ledger outbox, it is the owner ADR-0282 D5 names for the loyalty provision journal, and it is
   money-path with a threat model — so reward posting adds no new money-path service. Rejected
   owners: transaction-service's `CREDIT` (the welcome-bonus shape debits clearing and recognises no
   expense), ledger-service consuming events directly (no service sends ledger commands over Kafka
   today), incentive-service (not money-path, holds no amounts).
2. **Journal:** debit a new EXPENSE account **`5200 Referral and marketing reward expense`**
   (code proposed; unused in V1–V26), credit `2100 Customer Deposit Control` with `subAccountId` =
   the referrer's reward account. Idempotency key = `rewardReference`. A reversal is the ledger's
   existing `POST /api/v1/journals/{id}/reverse`.
3. **Contract changes it needs (additive):** `RewardRequested` gains `referrerPartyId` — the event
   today carries no party, so no consumer could find an account. billing resolves the party's
   primary `CURRENT` account the way fee assessment already does.
4. **Outcome:** billing publishes `ReferralRewardPosted { eventId, rewardReference, outcome:
   ACCEPTED|REJECTED|REVERSED, journalId?, reasonCode? }`; referral-service consumes it and calls the
   existing `applyLedgerOutcome`, which alone moves the reward to `REWARDED`/`RETRYABLE`/`REVERSED`.
5. **Stub interfaces only until approval.** The boundary is `applyLedgerOutcome(reference,
   outcome, actor)` in referral-service (exists) and, in billing, a port shaped
   `ReferralRewardPostingPort.post(rewardReference, referrerPartyId, amount, currency): PostingOutcome`
   — written into the implementing PR's design, not merged as code, until this item is accepted.
6. **Open for the approver:** the GL code and its FINREP mapping; whether a referral reward is
   taxable income for the customer (a CZ tax question this ADR does not answer); the funding and
   budget limit per programme (incentive-service's total/per-party limits are the natural control).

### D3 — Lístky for a qualified referral are earned by loyalty-service from `referral.qualified`

1. loyalty-service consumes `openbank.referral.qualified.v1` (channel `referral-qualified-in`,
   group `openbank-loyalty-service`, nested DLQ `openbank.dlq.loyalty.referral-qualified-in`) and
   calls the existing `EarnLeavesUseCase.earn(referrerPartyId, QualifiedReferral, correlation)`.
2. **The referrer earns; the referee does not.** Opening an account is not one of ADR-0282 D3's
   financial-health signals; bringing a customer is the catalogued achievement.
3. **Idempotency: `correlationEventId = inviteId`.** The existing partial unique index
   `(party_id, earn_source_id, correlation_event_id)` then makes "one invite earns at most once" a
   database property. It is strictly stronger than keying on the envelope `eventId`: it also holds
   if referral-service ever re-emits a `Qualified` for the same invite under a new event id.
4. **Cap interplay:** `EarnOutcome.Capped` writes nothing, counts
   `openbank_loyalty_earn_capped_total`, and the record is **acked** — the cap is an outcome, not a
   failure, and a retry would be refused identically. It is independent of the cash reward in D2:
   a capped referrer is still paid. A later `REVERSED` referral reward will map to a loyalty
   `REVERSE` entry (ADR-0282 D2); that consumer is not built by this ADR.
5. Loyalty earn does **not** wait for D2. Lístky are not money, and the earn is reversible.

### D4 — A benefit is "applied" only when its engine says so

1. Each delivering engine consumes `LeafBenefitGranted` (on `openbank.loyalty.events`, keyed by
   party) and publishes exactly one outcome per grant to a single topic,
   **`openbank.loyalty.benefit-outcomes.v1`**, keyed by `grantId`, with a Write ACL per engine
   (billing, interest, fx):
   `BenefitApplied { eventId, grantId, partyId, benefitId, engine, effectiveFrom, effectiveUntil,
   engineReference, occurredAt }` and
   `BenefitApplicationFailed { eventId, grantId, partyId, benefitId, engine, reasonCode, retryable,
   occurredAt }`. The schema is owned by loyalty-service's AsyncAPI as a consumed message.
2. loyalty-service consumes the topic and adds two **additive** `BenefitGrantStatus` values:
   `APPLIED` (from `BenefitApplied`) and `APPLICATION_FAILED` (from a non-retryable
   `BenefitApplicationFailed`, which also writes a `REVERSE` ledger entry returning the Lístky).
   `GRANTED` keeps its meaning. Transitions are idempotent on `eventId` and only move forward.
3. The edge passes `grantStatus` through (it already does) and publishes the two values in its
   spec. **The app shows "aktivní" only for `APPLIED` with `effectiveUntil` in the future**; an
   unknown status renders as pending, never as active.
4. The engine side is money-path in all three services and is **design only** here: each engine
   change carries the ADR-0030 threat-model obligation and two approvals. billing additionally
   needs a `WaiveCondition` over an active grant, which it cannot express today.

### D5 — The edge publishes per-capability availability, separate from marketing surfaces

`GET /customer/v1/surfaces/{slot}` is **not** extended. Surfaces are marketing: consent-gated,
impression-budgeted and dismissal-suppressed, and the app renders an unreachable surface as "not
eligible". Building availability on it would hide Lístky and MGM from every customer who withheld
marketing consent, and an engagement outage would silently remove a product. No other capability
endpoint exists (`/profiles` carries only `hasProducts`; `/cards/entitlements` is card-specific).

The edge adds **`GET /customer/v1/capabilities`**, authenticated like every other customer route,
identical for every caller (never party-specific — eligibility stays inside each service), and
cacheable (`Cache-Control: private, max-age=300`; the app reads it once per session and on
foreground):

```json
{
  "schemaVersion": 1,
  "capabilities": [
    { "id": "loyalty",              "state": "unavailable", "reason": "NOT_DEPLOYED" },
    { "id": "referrals",            "state": "unavailable", "reason": "DISABLED" },
    { "id": "offers_inbox",         "state": "unavailable", "reason": "NOT_BUILT" },
    { "id": "accept_payment",       "state": "unavailable", "reason": "NOT_BUILT" },
    { "id": "all_money",            "state": "unavailable", "reason": "NOT_BUILT" },
    { "id": "business_money_strip", "state": "unavailable", "reason": "NOT_BUILT" },
    { "id": "pending_approvals",    "state": "unavailable", "reason": "NOT_BUILT" },
    { "id": "game_points",          "state": "unavailable", "reason": "NOT_BUILT" }
  ]
}
```

- `id` is a closed enum in the spec; **clients must ignore ids they do not know**, and adding one is
  a MINOR spec change. `state` is `live | unavailable`; a client treats any other value as
  `unavailable`. `reason` is present only when `unavailable`: `NOT_BUILT` (no backend exists),
  `NOT_DEPLOYED` (the backend is not wired in this environment), `DISABLED` (an operator switched
  it off).
- **Computed from configuration only, never from a live health probe** — a flapping probe would make
  a product appear and disappear; runtime failures stay the per-route 502/503 they are today. A
  capability is `live` iff its operator switch `openbank.edge.capabilities.<id>.enabled` is true
  **and** its backend URL is configured. Switches default to `false`, so enabling a capability is a
  reviewed gitops change and never an app release.

### D6 — Rollout order, and what stays demo until each step lands

| Step | Lands | App state after it |
|---|---|---|
| 0 | This ADR; edge capabilities endpoint (every capability `unavailable`) | Unchanged demo, now driven by the endpoint |
| 1 | #8859 outbox relay → D1 qualification in referral-service → referral-service `replicas ≥ 1` with the consumer ACLs | Invites and attributions real; `referrals` stays `DISABLED` because no reward is paid |
| 2 | D3 loyalty earn → loyalty-service gitops deployment over TLS, edge `LOYALTY_SERVICE_URL`, `loyalty` switch on | Lístky balance, history and earning live; grants show pending, never "aktivní" |
| 3 | D2 approved → billing reward posting + `ReferralRewardPosted` consumer | `referrals` switched on |
| 4 | D4 per engine (fee waiver first) | That benefit can show "aktivní" |

A step that fails its gates stops the sequence at the previous row; nothing is switched on to
compensate.

## Alternatives considered

- **Qualify only invites already ATTRIBUTED when `AccountCreated` arrives.** Rejected: the referee
  opens the account before they can redeem the code, so this misses the common order.
- **Rename `account.opened` or `AccountCreated` to match.** Rejected: programme revisions are
  immutable and four consumers read `AccountCreated` verbatim; a one-entry translation table in the
  referral adapter is cheaper and local.
- **Call account-service at attribution time to ask whether the referee has an account.** Rejected:
  a synchronous dependency on the money-path account-opening service from a growth flow, no event
  time to apply the issue-time rule to, and no idempotency key.
- **Reuse the welcome-bonus `CREDIT` for the reward.** Rejected for production: it debits cash
  clearing and recognises no expense (ADR-0267 decision 4 calls it sandbox-only).
- **Build availability on `/surfaces` or the flagd `FeatureClient`.** Surfaces: rejected above
  (consent-gated marketing). flagd: the edge has no sidecar or `FeatureClient` today and a flag
  service outage would fail static to defaults; edge configuration delivered by gitops gives the same
  "no app release" property with nothing new to run. A later move to flagd is compatible with the
  JSON contract.
- **Key loyalty earn on the `Qualified` envelope `eventId`.** Considered; `inviteId` is chosen
  because it survives a re-emission under a new event id (D3.3).

## Consequences

**Positive**
- A referral qualifies in either order, exactly once, and cannot be farmed by redeeming a code
  against an old account or several codes against one account.
- Lístky for referrals are earned without waiting on the money decision.
- "aktivní" becomes a claim the platform can back with an engine's confirmation.
- The app moves from demo to live per capability by gitops change, with no release.

**Negative**
- referral-service stores a per-party fact for every account opening, not only for referees
  (retention: the service's 13-month policy applies).
- MGM stays switched off for customers until a human approves D2.
- One more topic (`benefit-outcomes`) with three producers to operate.

**Neutral**
- ADR-0266 and ADR-0282 are refined, not superseded; D2 is the money-path decision ADR-0266 said
  was required.

## Compliance impact

- PCI DSS: not applicable — no card data is introduced.
- DORA: the consumers dead-letter instead of stopping the channel, and capability state is
  configuration-driven so an outage of an optional service does not remove unrelated products.
- GDPR: referral-service gains a per-party account-opened fact (party id, account id, instant),
  retained under its existing 13-month policy; the capabilities response carries no personal data.
- PSD2: not applicable — no payment initiation or account-information surface changes.
- CNB: customer-facing referral terms and the accounting and tax treatment of a cash reward (D2)
  require product, finance and legal review before the `referrals` capability is enabled.

## References

- ADR-0006 — producers before consumers.
- ADR-0030 — money-path threat models and approvals.
- ADR-0143 — billing fee assessment and waivers.
- ADR-0220 — engagement surfaces; rewards never marketing cash.
- ADR-0266 — MGM fixed-reward incentives.
- ADR-0267 — event-driven onboarding account lifecycle (welcome bonus).
- ADR-0282 — Lípa loyalty ecosystem.
- #8859 — referral outbox relay; #8793 — loyalty-service phase 2.
