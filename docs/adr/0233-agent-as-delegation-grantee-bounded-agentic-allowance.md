---
date: 2026-08-01
decision-status: proposed
delivery-status: planned
authors: [Jiri Raska]
supersedes: []
superseded-by: []
delivery-repos: []
tags: [ai-agents, authz, sca, payments]
summary: "Chartered AI agents become delegation grantees: bounded agentic allowance with hard ceilings, propose-only default above them, no sub-delegation ever — one mandate home shared with the AP2 verification line."
---

# ADR-0233 — Agent as delegation grantee: bounded agentic allowance

Relates: ADR-0232 (delegation model), ADR-0031 (agent governance),
ADR-0089 (customer copilot), ADR-0102 (agentic AI), ADR-0193 (AP2
mandate verification and liability), ADR-0225 (policy-filtered tools),
ADR-0227 (approval inbox), ADR-0155 (four-eyes).

## Context

ADR-0232 gives customers delegation grants over products and objects
with capabilities, ceilings and approval policies — but the grantee must
be a KYC'd *party* (D5). Meanwhile the platform is building agentic
banking on three converging lines: the customer copilot (ADR-0089), the
agent governance plane (ADR-0031: chartered `agent:` principals,
policy-gated tools, human-in-the-loop, AI-attributed audit), and AP2
agent-payment mandate verification (ADR-0193: an agent may pay within a
cryptographically verifiable mandate, with a defined liability
position).

Customers will ask for the obvious product: "let my AI agent pay the
family groceries up to X, top up the transit card when low, sweep
leftover change into savings." Two systems would then answer the same
question — delegation grants (party grantees) and AP2 mandates (agent
principals) — with two lifecycles, two liability stories and two audit
shapes. That is the #2404 drift pattern in a domain where drift is a
liability incident. The risk is equally real: an agent with execution
rights has a larger blast radius than a human delegate (prompt
injection, model error, tool-chain compromise), so the grant model must
contain agents *structurally*, not by policy promises.

## Decision

We will make chartered AI agents first-class delegation grantees —
**bounded agentic allowance** — inside the ADR-0232 model, not beside
it.

**D1 — `granteeType: PARTY | AGENT` on `DelegationGrant`.** An AGENT
grantee references a chartered agent identity (`agent:` principal,
agents.yaml charter, ADR-0031) owned by a KYC'd party. The D5
eligibility gate applies to the agent's *owner*: KycLevel.FULL is
required for any agent grant that carries execution capability, and the
grantor must own the resource or hold `delegation.manage` on it.

**D2 — Ceilings are the control surface; above them, propose-only.**
An agent grant MUST carry per-transaction + daily + monthly ceilings
(no unlimited agent grants — the aggregate rejects them). Within
ceilings the agent may execute; any action crossing a ceiling becomes a
proposal in the owner's approval inbox (ADR-0227) requiring the owner's
SCA — the D8 maker-checker machinery, with the agent as permanent
maker.

**D3 — Agents never sub-delegate and never self-widen.**
`DELEGATION_MANAGE` is structurally forbidden on AGENT grants (aggregate
invariant, same class as "no execution on object grants"). An agent
cannot create, accept, widen or revoke grants — containment survives
prompt injection by construction.

**D4 — One mandate home: delegation-service backs the AP2 line.**
AP2 mandate verification (ADR-0193) sources its constraints from
AGENT-grant records instead of a parallel mandate store — one
lifecycle, one revocation path, one audit shape. The AP2 verify
endpoint becomes a read projection of the same aggregate. Liability
follows ADR-0193's position: the agent acts as the owner's tool under
the framework contract, bounded by the mandate.

**D5 — Launch gates, not vibes.** Before the first AGENT grant with
execution capability ships: (a) the delegation fraud/anomaly agent
(issue #3000) is live and consuming AGENT grants as a first-class risk
signal; (b) agent tool surface is policy-filtered (ADR-0225) with every
money-mutation hard_denied by default in the charter; (c) velocity
limits below the grant ceilings exist at the agent plane (a compromised
agent cannot drain at ceiling speed); (d) threat model updated for
prompt-injection and tool-chain compromise scenarios.

## Alternatives considered

- **consent-service CUSTOMER_AGENT consent instead** — rejected:
  PSD2-style consents carry validity caps and data-access scopes, not
  limits/approvalPolicy; agent payment rights are mandates, not
  consents — the same regime-mixing defect ADR-0232 rejected for TPP vs
  party delegation.
- **Copilot drafts only, agents never execute (status quo)** —
  rejected as the end state (it is the correct phase 0 and ships
  independently): customers will adopt agentic payments regardless; a
  governed mandate beats an ungoverned workaround (credential sharing,
  full-access delegation to a family member "who runs the bot").
- **Separate agent-mandate service** — rejected: it would re-implement
  the ADR-0232 lifecycle (dual-consent, SCA, outbox, expiry, audit) for
  one new grantee type; D4 shows the convergence is one aggregate.

## Consequences

**Positive**
- First-mover agentic banking with structural containment: ceilings,
  propose-only escalation, no sub-delegation — marketable as "AI that
  can spend, within walls you set and can tear down in one tap."
- AP2 and delegation converge on one mandate aggregate, one audit
  chain, one revocation UX — no drift class.
- Every agent action is doubly attributed (agent identity + onBehalfOf
  owner) — investigation-ready by default.

**Negative**
- New attack surface (prompt injection against a spending agent); the
  D5 launch gates are mandatory scope, not hardening extras — they
  delay the feature.
- Two grantee types complicate the aggregate and every projection
  consumer (PARTY vs AGENT semantics in enforcement guards).
- Liability language for agent spending must be written into customer
  terms before launch (legal engagement, not just engineering).

**Neutral**
- Phase 0 (copilot drafts, humans sign — issue #3001) ships without
  this ADR and is unaffected by its outcome.

## Compliance impact

- PCI DSS: not applicable — no cardholder-data environment change.
- DORA: agent execution path is a new ICT component on the money path;
  enters the risk register and BCP; charter changes are reviewed
  configuration (ADR-0031).
- GDPR: agent acts on the owner's instruction under the framework
  contract; no new purpose, no new data categories. AI-attributed audit
  is the accountability mechanism.
- PSD2: the agent is the PSU's tool; execution above ceilings carries
  the owner's SCA — no new exemption surface. Liability wording per
  ADR-0193 must ship in customer terms before launch.
- CNB: AI Act transparency applies to customer-facing agent behavior
  (ADR-0148 mapping); anomaly-agent oversight (D5a) is the AML
  compensating control for machine-speed spending.

## References

- ADR-0031, ADR-0089, ADR-0102, ADR-0148, ADR-0155, ADR-0193, ADR-0202,
  ADR-0225, ADR-0226, ADR-0227, ADR-0232
- Issues #2990 (enforcement projections), #3000 (delegation
  fraud/anomaly agent), #3001 (copilot delegation assistant)
