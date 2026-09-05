---
date: 2026-09-05
decision-status: proposed
delivery-status: planned
authors: [Jiri Raska]
supersedes: []
superseded-by: []
delivery-repos: [openbank-app]
tags: [product-catalog, analytics, fees-billing, mobile-app]
summary: "Lístky reward financial health, never credit or spend: a closed-loop loyalty-service ledger with IFRS 15 provisioning redeems into fee waivers, rate tiers and per-party offering overlays chosen by feature-store micro-segments."
---

# ADR-0282 — Lípa: a financial-health loyalty ecosystem tying rewards, micro-segments, personal offerings and Customer 360 together

## Context

The platform has every building block of a loyalty ecosystem and almost none of the joins
between them. Measured on `main` on 2026-09-05:

- **Gamification** (`openbank-engagement-service`, ADR-0220 D3, ADR-0261) has an opaque,
  deliberately non-monetary `Points` counter, a sealed `EarnSource` with four variants of which one
  (`EducationalContentCompletion`) has a producing consumer, a `ChallengeCatalog` with one entry,
  `Badge` and `Streak` types with no catalogue, an opt-in rewards hub, consent gating and the
  adverse-state exclusion. Points cannot be exchanged for anything. The `Points` KDoc says where
  that exchange must live when a business wants one: outside the domain layer, as an explicit,
  reviewed, rate-limited call into the ADR-0143 billing path — never a method on the type.
- **Monetary bonuses** exist twice, both as cash: `openbank-referral-service` (fixed reward with a
  ledger handoff, ADR-0266) and `openbank-incentive-service` (promo offers with limits and
  maker-checker).
- **Segmentation** (`openbank-campaign-service`, ADR-0201) is two catalogued segments (`actives`,
  `actives-tenured-30d`) over a DSL of four rules, two of which are unsupported at construction
  time: `HasAccount`, because no ACCOUNT event in analytics carries a `partyId` (#2891), and
  `HasActiveConsentScope`, because consent events are not ingested. There is no behavioural or
  micro-segmentation of any kind.
- **Data** (`openbank-analytics-sink`, ADR-0210): twelve topics land in ClickHouse bronze; silver is
  the latest event per aggregate; gold views cover the onboarding funnel, screen feedback, campaign
  engagement and the credit funnel. The only transaction fact is `transaction.initiated`, with no
  category, MCC, merchant or amount band. Customer 360 (ADR-0210) is an admin-ui route querying
  silver directly. No per-party behavioural profile exists anywhere.
- **Feature store** (ADR-0140): `OnlineFeatureStore` on Valkey has one consumer, fraud. The offline
  half is unbuilt. Next-best-action (ADR-0201 D5) has no code.
- **Product catalog** (ADR-0257, ADR-0259): the specification / offering / revision kernel carries
  `EligibilityRule`s in the pack schemas, and the legacy `EligibilitySegment` is six coarse values
  (`RETAIL`, `STUDENT`, `SENIOR`, `BUSINESS`, `PREMIUM`, `ALL`). `WaiverEvaluator` and
  `InterestTier` in `openbank-libs-domain` already express conditional fee relief and tiered rates.
  Nothing produces a per-party variant of a product.

Loyalty, catalog, campaigns and Customer 360 are four islands that tell each other nothing about
the customer. The bank knows how a customer behaves and cannot act on it, and the only reward it can
give is cash — which ADR-0220 D3 rule 4 already forbids as "marketing cash".

The market context matters for the direction chosen below. Every large loyalty programme in retail
banking rewards **spend** (card cashback, points per transaction) or **credit** (sign-up bonuses on
credit products). ADR-0220 D3 rule 1 forbids rewarding credit uptake, utilisation or any
risk-increasing behaviour, and "Alternatives considered" there rejects gamified credit absolutely.
This ADR turns that prohibition into the product's identity rather than a limitation.

## Decision

We build a loyalty ecosystem named **Lípa** (the linden, the Czech national tree) whose unit is the
**Lístek** (leaf). The programme rewards **financial health**, never spend and never credit: the
customer's linden grows when the customer does. The decisions below are numbered D1–D9.

**D1 — Lístek is a closed-loop unit of bank obligation, not a currency.** A Lístek can be earned
from the bank and redeemed with the bank. It has **no cash-out**, **no transfer between customers**,
**no purchase for money**, and **no price in any fiat currency**. These four properties are domain
invariants of the new context, tested as such, and they are what keeps Lístek outside the definition
of electronic money (EMD2, limited-network reasoning) and outside MiCA (ADR-0188 keeps crypto-assets
and tokens out of scope; Lístek is neither). The single transfer permitted is D7's household gift,
which moves obligation between parties of one delegation-bound household and stays inside the bank.

**D2 — A new bounded context, `openbank-loyalty-service`, owns the Lístek ledger.** An append-only
`earn | burn | expire | reverse` ledger per party with its own transactional outbox, the same shape as
`openbank-referral-service`. The ledger is **not** `openbank-ledger-service` and never posts money;
it records obligation in Lístky. Every earn carries `earnSourceId`, a frozen `ruleVersion`, and the
id of the domain event that triggered it (the ADR-0261 `GamificationAward` discipline, reused).
Idempotency is keyed on `(party, earnSource, triggering event)`. Engagement's `Points` stay what they
are today — an activity counter; loyalty-service consumes `GamificationAward` and other domain events
and mints Lístky by a reviewed rule. `Points` gains no conversion method; the KDoc's boundary holds.

**D3 — Earn sources are a sealed catalogue of financial-health signals, all outside credit.** The
initial catalogue: savings rate over a rolling period, emergency buffer in months of outflow,
on-time repayment (the one credit-adjacent signal ADR-0220 D3 itself names, because it rewards
reducing risk), a savings goal set and reached (ADR-0153), currency diversification across pockets
(ADR-0109), educational-content completion, login streak, tenure anniversaries, screen feedback
given, and a qualified referral (ADR-0266). A new source is a pull request touching one sealed
class, reviewed against D3 rule 1 exactly as `EarnSource` is today; a `when` without `else` keeps
every consumer honest. **No earn source may reference spend volume, card usage, credit uptake,
credit utilisation, overdraft, or any product whose eligibility ADR-0142 decides.**

**D4 — Redemption is a governed catalogue of bank-side benefits, delivered by existing engines.**
A `BenefitCatalog` entry names a benefit, its Lístek price, its validity window and the engine that
delivers it. The first engines are the ones that already exist: a fee waiver via a new
`WaiveCondition` in `openbank-libs-domain` evaluated by `openbank-billing-service` (ADR-0143); a
temporary interest bonus tier via `InterestTier` in `openbank-interest-service`; a card tier unlock
via `CardTier` (ADR-0194); one FX conversion at the reference rate with no margin
(`openbank-fx-service`); and early access to a newly published catalog offering. Redemption is a
reservation then a commit or release, keyed by an idempotency token, the same semantics ADR-0266
gives promo codes. A benefit is a catalogue entry reviewed in a pull request — never free text, never
an admin-ui action.

**D5 — Lístky are a liability, provisioned through the billing path, capped per party per year.**
Each earn creates an obligation recognised under IFRS 15 (customer loyalty programmes are a
separate performance obligation). `openbank-loyalty-service` publishes a daily provisioning summary;
`openbank-billing-service` posts the balanced journal to a loyalty-provision account in
`openbank-ledger-service`, and an expiry or reversal releases it. ADR-0220 D3 rule 4's per-party
annual cap becomes a domain invariant of the earn use case (an earn that would exceed the cap is
recorded as `CAPPED`, never silently dropped — a no-op must not share a state with success). Lístky
expire after a fixed, published period (proposed 24 months, first-in-first-out), and the expiry
is shown in the app, never a surprise.

**D6 — Micro-segments are versioned feature-store rules, and the data gaps are closed first.** The
ADR-0201 segment DSL gains `FeatureAtLeast(featureName, threshold)` and `LoyaltyTierIs(tier)`,
evaluated against the ADR-0140 online store rather than silver, and `FeatureDefinition`s for
campaign-relevant behaviour join the catalogue in `openbank-libs-domain` computed by the same pure
function (ADR-0201 D3). This decision explicitly takes on the data work that ADR-0201 left open:

1. ACCOUNT events carry the owning `partyId` (#2891), so account holding becomes evaluable.
2. A post-settlement transaction event carries category, MCC, a merchant hash, an amount band and
   the rail — never the counterparty name or the free-text message.
3. Consent, card, lending-repayment, standing-order and FX events are ingested into analytics.
4. Silver gains a materialised per-party profile table beside `silver_current_state`, so Customer
   360 stops re-deriving it per request.
5. ADR-0140 phase 2 (the offline snapshotter) ships before any segment is backtested or any
   ranking model is trained; deterministic segments do not wait for it.

Segments stay code, reviewed and versioned (ADR-0201 D1). NBA, when it exists, ranks catalogue
messages only and is barred from credit outcomes (ADR-0201 D5); it never selects a benefit price or a
personal offering term.

**D7 — Personal offerings are catalog revisions with a per-party overlay, produced by deterministic
policy.** The ADR-0257 kernel gains a `PersonalOfferingOverlay`: a bounded set of term deltas (fee,
rate tier, limit, card tier, benefit bundle) applied to a published offering revision for one party,
with a validity window and the micro-segment version that justified it. A deterministic policy over
D6 segments and D5 tier proposes the overlay; ADR-0259's AI may draft and explain but never
decides; maker-checker publishes; the customer accepts in the app. An overlay may only ever
**improve** a term relative to the published revision (a monotonic invariant), so personalisation
can never become discriminatory pricing. Credit products are excluded from overlays entirely:
their price and eligibility remain ADR-0142's, and ADR-0220 D4 governs any pre-approved offer.

**D8 — Reciprocal transparency: what the operator sees, the customer sees.** Customer 360 gains a
Lípa panel (tree, tier, earn history, active benefits, micro-segment memberships with their
versions, active overlay, and the explanation of why each was chosen, from ADR-0222's
explanation agent over reason codes). The app renders the same panel to the customer, with a
one-tap opt-out of personalisation that keeps earned value (ADR-0220 D3 rule 2, extended from the
hub to segments and overlays). Profiling under GDPR Art. 22 is answered by design — deterministic
rules, a visible reason, a working opt-out — not by a disclaimer. Vulnerable customers (the ADR-0200
D6 adverse-state set) are excluded from targeting and overlays at the eligibility stage, exactly as
ADR-0220 D3 rule 5 already does for challenges; they keep earning and redeeming on their own
initiative.

**D9 — The linden grows beyond one customer: household and community, both closed-loop.** *Háj* (the
grove): parties bound by `openbank-delegation-service` may gift Lístky within the household, so a
grandparent can grow a grandchild's tree; the gift is the only transfer and it stays inside the bank
(D1). *Les* (the forest): a customer may donate Lístky to a community tree per municipality, and
the bank converts the community balance into real trees planted, with the planting evidence
published (a CSR outcome with a verifiable record, not a marketing claim). Neither creates a
transferable instrument: a donated Lístek is burned, and the bank's planting obligation is the
bank's, not the customer's.

### Delivery phases

1. **Data first** (D6 items 1–4, plus feature definitions). Without this the rest is a demo, and
   ADR-0220 D5's honesty rule applies: no fabricated profiles, no placeholder segments.
2. **Loyalty-service**: Lístek ledger, IFRS provisioning via billing, three benefits (fee waiver,
   interest tier, FX at reference rate), the tree in the app, the 360 panel.
3. **Micro-segments** in the campaign DSL, `PersonalOfferingOverlay` in the catalog kernel, the
   explanation surface, opt-out of personalisation.
4. **Háj and Les**, partner benefits behind the same catalogue, NBA in shadow per ADR-0201 D7.

Each phase is its own implementation issue; a phase that touches a money-path service (billing,
interest, fx, ledger) carries the ADR-0030 threat-model obligation and two approvals.

## Alternatives considered

- **Make `Points` redeemable directly.** Rejected: `Points` was designed as an opaque activity
  counter and its KDoc names exactly this leap as the thing to prevent. A separate context with its
  own ledger keeps the exchange explicit, reviewed and rate-limited, as that KDoc requires.
- **Cashback or spend-based points, the industry default.** Rejected by ADR-0220 D3 rule 1 and its
  "Alternatives considered". Beyond the rule, spend rewards are undifferentiated: every competitor
  has them, and they pull the customer toward the behaviour the bank's own risk function wants less
  of. Rewarding health is the one position nobody occupies.
- **An open-loop or transferable token, or a partner-redeemable currency.** Rejected: transferability
  and cash-out are the two properties that turn a loyalty unit into e-money, and ADR-0188 keeps
  tokens out of scope. Partner benefits enter as catalogue entries delivered by the bank, never as a
  Lístek leaving the bank.
- **Personalisation as discounts computed at request time by a model.** Rejected: an overlay is a
  reviewed, versioned, monotonic term delta with a maker-checker trail, so pricing stays explainable
  and non-discriminatory. A model may rank messages (ADR-0201 D5); it does not set terms.
- **Extend `openbank-engagement-service` instead of a new service.** Rejected: engagement owns
  surfaces and challenges; a liability ledger with provisioning, expiry and reversal is a different
  aggregate with money-path adjacency, and it deserves the referral-service shape.
- **Wait for the offline feature store before shipping any of this.** Rejected for phases 1–2:
  deterministic segments and the ledger need the online store and the data fixes only. Backtesting
  and any model wait (D6 item 5), which is ADR-0201 D4's partition, kept.

## Consequences

- One new service (`openbank-loyalty-service`), one new `WaiveCondition`, one new `InterestTier`
  source, a `PersonalOfferingOverlay` in the catalog kernel, two new segment rules, five new event
  producers or enrichments, a Lípa panel in admin-ui and the app.
- Lístky appear on the balance sheet as a provision; finance owns the recognition policy and the
  expiry period, and the daily provisioning journal is reconcilable against the loyalty ledger the
  same way ADR-0026 reconciles other sources.
- Every earn source and benefit is a pull request; product marketing gains no runtime lever, which is
  the same trade ADR-0220 and ADR-0221 already made for surfaces and templates.
- The data work in D6 improves fraud, credit-funnel and campaign analytics as a side effect, since
  it is the same silver layer.
- The annual cap, the expiry, the closed loop and the exclusion of credit bound the economic exposure
  and the regulatory surface; loosening any of them is a new ADR.

## Compliance impact

- **EMD2 / PSD2**: no cash-out, no P2P transfer, no fiat price (D1) keeps Lístek outside e-money; the
  household gift (D9) is intra-bank and non-monetary. A legal review confirms the limited-network
  reasoning before phase 2 ships.
- **MiCA**: out of scope per ADR-0188; Lístek is not a crypto-asset and is not on a distributed
  ledger.
- **IFRS 15**: loyalty obligations are recognised and released through billing and the ledger (D5).
- **GDPR**: profiling is deterministic, explained and opt-out-able (D8); the transaction enrichment
  carries no counterparty name or message text (D6); erasure follows the `PARTY_ERASED`
  anonymise-in-place pattern in every new store.
- **EU AI Act**: no AI decides a term, a benefit, an eligibility or a price (D7); any ranking model
  stays under ADR-0201 D5/D8 with its registry entry generated.
- **Consumer protection (Act No. 257/2016 Coll.)**: credit products are excluded from earn (D3) and
  from overlays (D7); pre-approved credit offers remain ADR-0220 D4's.
- **Vulnerable customers**: excluded from targeting and overlays, never from earning or redeeming
  (D8).

## References

- ADR-0220 In-app engagement surfaces, gamification and pre-approved offers
- ADR-0261 Gamification engine, engagement service D3 slice 1
- ADR-0266 MGM fixed-reward growth incentives
- ADR-0201 Customer segmentation and next-best-action
- ADR-0140 Feature store topology
- ADR-0210 Customer 360 as a query over the analytics silver layer
- ADR-0257 Industry-neutral product catalog kernel
- ADR-0259 AI-assisted product-catalog authoring and offer intelligence
- ADR-0143 Product fees from openbank-billing-service
- ADR-0142 Credit decisioning (high-risk controls)
- ADR-0188 Crypto-assets (MiCA) and CBDC out of scope
- ADR-0222 Offer-explanation agent
- Issue #2891 — ACCOUNT events carry no partyId; consent not ingested
