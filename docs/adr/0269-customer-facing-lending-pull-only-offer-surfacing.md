---
date: 2026-08-21
decision-status: proposed
delivery-status: planned
authors: [Jiri Raska]
supersedes: []
superseded-by: []
delivery-repos: [openbank-app]
tags: [lending, mobile-app, customer-edge, privacy-gdpr]
summary: "Customer lending is pull-only: no credit offer reaches a customer who did not switch it on, the edge exposes journey state and server-priced quotes as the only price source, and AI may explain but never decide."
---

# ADR-0269 — Customer-facing lending: pull-only offer surfacing, one credit journey, priced only by the server

## Context

The supply side of lending is decided and partly built. ADR-0028 draws the bounded context,
ADR-0211 makes origination a persisted state machine, ADR-0213 gives it a deterministic policy
engine with reason codes, ADR-0142 adds ML strictly inside that floor, ADR-0264 puts loan products
in the catalog kernel, ADR-0214 records lifecycle evidence, ADR-0216 binds the EU AI Act obligations
and ADR-0217 scopes credit AI agents. Customer 360 ships as a query over the silver layer
(ADR-0210), campaign journeys run as consent-gated Temporal workflows (ADR-0200/0263), and the
customer assistant exists behind the edge (ADR-0089).

None of that decides what a customer *experiences*. Today the mobile app has a read-only loan list
and a bare intake form (`LoanApplyScreen` → `POST /customer/v1/loan-applications`); the flow ends at
submission. There is no journey state, no offer, no APRC, no signature, no drawdown, no secured or
revolving product, and no route from Customer 360 or the campaign engine to anything a customer can
act on. The pieces that would connect them — a 360 read model, a campaign engine, an assistant with
tool calls, push notifications, a product catalog — all exist and all point at the same customer.

That is precisely the dangerous moment. Wiring cohorts, a next-best-action model, push delivery and
a credit product together produces, by default, a bank that sells debt to whoever looks most likely
to accept it. The distance between "helpful" and "harmful" on a credit product is one nudge, and a
customer under cashflow stress is simultaneously the most responsive target and the worst borrower.
Consumer-credit law already forbids some of this; the rest is a reputational and prudential
liability that no consent checkbox retroactively repairs.

The forcing question for this ADR is therefore not "how do we surface credit offers" but "under what
constraint may credit ever reach a customer at all", decided before the first offer exists rather
than retrofitted after the funnel is optimised.

## Decision

We will make the customer-facing half of lending **pull-only, one journey, server-priced, and
AI-explained but never AI-decided**. Five binding rules.

**1. Pull-only offer surfacing, enforced on the server.**
A `credit_offers` consent, default **off**, gates every credit-marketing path. It is not a delivery
preference and not a frequency setting: with it off, `customer-edge` returns no pre-approved limit,
`campaign-service` refuses enrolment into any journey whose product kind is credit, and no credit
push, in-app card or email is composed. The check lives at the edge and in the campaign step
gate — never only in the client — so a client bug cannot become a marketing act. Revocation is a
workflow signal (ADR-0200 D2) and stops a journey mid-flight. Consent never re-arms itself: no
expiry-driven re-prompt, no re-enrolment on release.

**2. A hard suppression floor that consent cannot lift.**
Even with `credit_offers` on, `lending-service` refuses to surface an offer while the customer is in
financial distress: arrears on any facility, a negative or overdrawn balance, an enforcement or
insolvency marker, a hardship flag, a failed affordability assessment inside the cooling window, or
a 360-derived buffer below the configured floor. Distress suppression is evaluated server-side on
every offer read and every campaign step, is versioned like a policy table (ADR-0213), and emits a
reason code. Consent is permission to be *offered*; it is not permission to be *targeted while
drowning*. A frequency cap of one credit-marketing contact per 30 days, and only on a changed input,
applies on top.

**3. One credit journey, three product shapes.**
`CreditApplication` carries `productKind ∈ {UNSECURED, SECURED, REVOLVING}` and a `requirements[]`
list, over the single ADR-0211 state machine, rather than three parallel origination flows. Secured
credit adds collateral, valuation and tranche states; revolving collapses disbursement into limit
activation. The edge exposes journey state as a customer-readable projection —
`GET /customer/v1/credit-applications[/{id}]` with steps, outstanding requirements and reason-coded
outcomes — so the client renders server state and never infers a decision.

**4. Price comes only from the server, and only as a quote.**
`POST /credit/v1/quotes` returns an indicative, non-binding price resolved from the catalog revision
(ADR-0264): rate, instalment, APRC, total payable, fees, validity. No client, no campaign template
and no model may compute or cache a price. A binding offer is a distinct object with `offerId` and
`expiresAt`, produced by the decision engine, and acceptance references it. Pre-contractual
information is generated and archived (documents-service) before signature, and signature is the
existing SCA ceremony.

**5. AI explains and prepares; it never decides, submits or signs.**
Three consent-scoped levels, on the ADR-0089/0217 machinery. **L0 explainer** (on by default) answers
questions the customer asks, receives no 360 profile and never speaks first. **L1 advisor**
(opt-in) reads the 360 credit profile on request to answer affordability with its workings — and is
required to be able to answer "no". **L2 agent** (opt-in, capability by capability) may watch and
prepare — refinancing watch, fixation-end watch, application pre-fill, instalment-risk warning,
consolidation scan — and its output is always a draft plus a proposal the customer confirms. No AI
level may transition the state machine, accept an offer, raise a limit or draw funds; a credit
decision is the deterministic engine's, is reason-coded, and carries a right to human review
(ADR-0216). Model access is scoped to the level the customer consented to.

We will also build these in order: **consent switches and suppression rules ship before the first
offer object exists.** A funnel that is measured before it is constrained never gets constrained
afterwards.

## Alternatives considered

- **Opt-out offers with a frequency cap** — the industry default: surface pre-approved credit to
  everyone, let the customer mute it. Highest reach and the easiest to measure. Rejected: it makes
  the distressed customer the most-contacted customer, since responsiveness and distress correlate,
  and no cap fixes a targeting rule that is wrong at n=1.
- **Next-best-action model chooses when to show credit** — hand timing to the NBA model ADR-0209
  sequences. Rejected for now: ADR-0209 explicitly forbids starting the NBA model before ADR-0140
  phase 2, and an optimiser whose objective is acceptance will rediscover distress targeting as a
  feature. A model may rank offers the customer already asked to see; it may not decide that they
  see them.
- **Separate flows per product kind** — three origination pipelines, each tuned to its product.
  Rejected: three state machines means three audit trails, three sets of reason codes and three
  places for the suppression rule to be forgotten. The requirements list carries the variance more
  cheaply.
- **Client-side pricing from catalog parameters** — let the app compute the instalment for
  responsiveness. Rejected: an instalment a customer plans a year around must not come from a copy
  of pricing rules that can drift, and the app already carries this rule for loan intake today.
- **A single "AI features" switch** — one toggle for the whole assistant. Rejected: it is either so
  coarse that consent is meaningless or so scary that nobody enables the genuinely useful
  affordability advisor. Levels let a customer take the explainer without the agent.

## Consequences

**Positive**
- The offer path has one server-side gate and one suppression floor, both reason-coded and testable;
  "was this customer offered credit without asking" is a query, not an audit interview.
- One journey and one price source mean the app, the campaign engine and the officer console all
  read the same state, so a customer cannot be told two different numbers.
- Financial health has standalone value for customers who never borrow, which is what keeps the
  surface honest rather than a disguised funnel.
- AI levels bound model data access to consent, which is what ADR-0216's Art. 9-15 mapping needs
  evidence for anyway.

**Negative**
- Default-off consent means low reach and, initially, near-zero credit origination through the app.
  That is the intended trade and must not be re-litigated as an adoption bug.
- The suppression floor needs live 360 and arrears signals on the read path, adding a dependency and
  latency to an otherwise cacheable offer read.
- Secured credit remains mostly outside the app; the journey exposes state for steps the customer
  completes elsewhere, which is honest but visibly incomplete.

**Neutral**
- Existing `POST /customer/v1/loan-applications` becomes the unsecured entry point into the unified
  journey; the route stays, its response gains journey state.
- Campaign templates for credit gain a mandatory product-kind field so the step gate can refuse
  them.

## Compliance impact

- PCI DSS: not applicable — no cardholder data is introduced by this decision.
- DORA: not applicable — no change to ICT risk, continuity or third-party arrangements beyond
  existing lending services.
- GDPR: engaged. Consent is granular, revocable and non-self-renewing; the 360 credit profile is
  processed for affordability assessment and, separately by consent, for offer eligibility;
  revocation deletes derived offer state, not merely a flag. Automated decision-making in credit
  carries the reason codes and human-review route recorded in ADR-0214/0216.
- PSD2: not applicable — lending is outside the payment-services scope; the account data used is the
  bank's own.
- CNB: engaged in plain terms — consumer-credit conduct obligations (creditworthiness assessment
  before granting, pre-contractual information, right of withdrawal, early repayment) are what rules
  3-4 exist to make structurally possible. No specific provision is cited here; the jurisdictional
  mapping belongs in ADR-0212's packs.

## References

- ADR-0028 lending bounded context; ADR-0211 origination orchestration; ADR-0213 deterministic
  credit policy engine; ADR-0142 credit decisioning engine; ADR-0214 credit lifecycle audit evidence
- ADR-0264 loan products in the catalog kernel; ADR-0257 catalog kernel
- ADR-0210 Customer 360 over the silver layer; ADR-0209 CRM/campaign sequencing
- ADR-0200 / ADR-0263 campaign journeys as Temporal workflows with consent-gated delivery
- ADR-0089 customer-facing AI assistant; ADR-0217 credit lifecycle AI agents; ADR-0216 AI Act
  high-risk credit compliance; ADR-0031 AI agent governance
- ADR-0212 jurisdictional credit compliance packs
- openbank-app: `docs/adr/APP-0001-lending-experience-in-the-client.md` (client-side realisation)
