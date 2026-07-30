---
date: 2026-07-29
decision-status: proposed
delivery-status: planned
authors: [Jiri Raska]
supersedes: []
superseded-by: []
delivery-repos: []
tags: [ai-agents, privacy-gdpr, compliance]
summary: "Two business-plane agents fill ADR-0203's gaps: an offer-explanation tool on customer-copilot rendering decision reason codes to customers, and an rm-copilot briefing relationship managers, sending only via the contact gate."
---

# ADR-0222 — Offer-explanation and relationship-manager agents

## Context

ADR-0203 fields six business-plane agents and leaves two customer-adjacent gaps visible once the
commercial stack (ADR-0200/0201/0212) exists:

**Gap 1 — nobody can tell the customer *why*.** The estate produces decisions with reasons attached:
ADR-0201's NBA ranks with a model card, ADR-0220 D4 renders pre-approved offers carrying reason codes
from a standing ADR-0142 decision, and 0201's own compliance note commits to *"Art. 13/14 transparency
for profiling"*. Transparency duties are discharged by *telling the customer*, not by recording the
reason internally. Today the only customer-facing assistant (customer-copilot, ADR-0089) can narrate
balances and products but has no tool to answer "proč mi banka poslala/nabídla tohle?" from the
governed record — and an LLM answering that question *without* the record is exactly the hallucinated-
reason incident ADR-0148's assurance gate exists to prevent.

**Gap 2 — the relationship manager has no preparation agent.** ADR-0203's D1 rationale — *"the human
is already doing the work and the agent's only job is preparation"* — describes the RM's day
precisely: reading the ADR-0210 360 view, recent events and open cases before a client conversation,
then writing follow-ups. That is manual today, and it is the lowest-risk agent shape there is
(read-only, human disposition, no customer contact). Note the RM's outbound messages are a real
contact class: ADR-0176 covers operator-initiated messaging with its own four-eyes and template
catalogue, and ADR-0219 makes every such send pass the contact-policy gate — an agent that drafts for
an RM must produce output that flows *into* that governed path, never around it.

The constraints are inherited and non-negotiable: ADR-0203 D7's common constraints (no write/execute
tools, honest `data_scope.pii`, `requires_human`, measured token limits, kill switch, full audit
capture), D8's sequencing gates (no customer-data agent before the ADR-0195/#2206 MCP authz fallback
reads zero; LiteLLM gateway before the second agent, so token cost has a denominator), and ADR-0197's
licensing (agent-plane ⇒ AGPL). The ADR-0201 D5 boundary applies with full force to gap 1: the
explanation agent may *render* a credit decision's reason codes; it may never re-derive, negotiate or
soften them.

## Decision

We will add two agents on the ADR-0031 stack, adopting ADR-0203's D7/D8 constraints verbatim.

**D1 — `offer-explanation` as a tool extension of customer-copilot, not a new agent.** Following the
ADR-0203 D5 finance-coach precedent (same principal, no new charter): a new read tool,
`explain_offer(offerId | notificationId)`, which loads the governed record behind the artefact — the
standing decision's reason codes for a pre-approved offer (ADR-0220 D4), the campaign step's
template id and segment version for a marketing message (ADR-0200/0201) — and renders it in plain,
localised language, including how to object or withdraw consent (the ADR-0219 D3 suppression and
ADR-0198 revocation paths). Hard invariants: the tool answers **only** from the record (no record ⇒
"I can't verify that, here's how to reach a human"); it never quotes amounts, rates or terms beyond
what the record carries; it never collects data beyond the session. An `ml-systems.yaml` entry is
generated in the same PR (ADR-0203 D7's registry rule), with the ADR-0201 Art. 22 analysis carried
as its basis — and the ADR-0148 evals gate gains a **named eval suite for the no-re-derivation
invariant** (adversarial prompts asking for terms, amounts or reasons absent from the record must
produce the no-record fallback, never a completion), because this tool's entire safety case is that
single behaviour and a generic gate does not prove it.

**D2 — `rm-copilot` as a new business-plane agent.** Read-only over the ADR-0210 360 query,
interaction history and open cases; per client or per meeting it produces a briefing (holdings,
recent events, NBA list with reason codes, vulnerability flags per ADR-0220 D3.5 — an RM must see
what the targeting engine already respects) and drafts follow-up communications. Drafts are sent
**only** through ADR-0176's operator-initiated path — template catalogue, four-eyes where required,
and the ADR-0219 gate on send — never from a personal mail client or a new send capability in the
agent. `requires_human: every output`; no write/execute tools; charter under `docs/agents/` with
id-parity per the charter-registry gate. On cost, stated plainly rather than inherited silently:
ADR-0203 flags that the model gateway is not yet deployed and per-agent token accounting does not
exist — `rm-copilot` therefore ships **after** the gateway (ADR-0203 D8's gate, restated), and its
deployment shape is decided at implementation time against the measured marginal cost of a new
agent service versus colocating on an existing business-plane deployment; the charter, tools and
constraints in this ADR are invariant under either shape.

**D3 — Neither agent relaxes a boundary.** The explanation tool changes nothing about what may be
decided (ADR-0201 D5) or shown (ADR-0220 D4); the RM agent changes nothing about how customers are
contacted (ADR-0176/0211). Both are presentation and preparation layers over governed records —
which is also why they are safe to build on the same stack ADR-0203 already proved.

**D4 — Sequencing.** Both behind ADR-0203 D8's gates (MCP authz fallback metric at zero; LiteLLM
gateway deployed). `rm-copilot` additionally behind ADR-0210 being exercised by real RM usage — an
agent briefing over a view nobody trusts yet automates distrust. The explanation tool ships with the
first artefact that needs explaining (ADR-0220's first pre-approved surface, or the first ADR-0200
campaign, whichever lands first).

## Alternatives considered

- **Extend ADR-0203's campaign-copilot to cover both gaps.** One agent, less charter surface.
  Rejected on least privilege (the same objection ADR-0203 records against a general-purpose agent):
  the copilot's scope is the marketer's console; customer-facing explanation and RM data are two
  different `data_scope`s with different blast radii.
- **A static FAQ / "why this offer" link instead of an agent.** Cheaper, no LLM. Rejected as the
  whole answer: the honest answer is per-decision (these reason codes, this date, this segment
  version), which a static page cannot render — but a static page is the correct *fallback*, and the
  tool's no-record behaviour (D1) degrades to it deliberately.
- **Let the RM agent email the customer directly with the draft.** Rejected: it bypasses the
  catalogue, the four-eyes and the contact gate in one step — the exact defect ADR-0176 and
  ADR-0219 exist to close.

## Consequences

**Positive**
- Art. 13/14 transparency becomes a product feature staffed by a tool that can only quote governed
  records — the cheapest defensible form of "explain your automated processing".
- RM preparation time drops while every customer contact stays inside the governed path — the
  ADR-0203 D1 pattern applied to the last major manual queue the estate has.
- The explanation tool doubles as an audit demonstrator: every answer cites the record it used,
  which a supervisor can replay.

**Negative**
- Two more PII-reading agents on the cost and governance meter: token budgets measured per ADR-0203
  D7, honest `data_scope.pii`, and the LiteLLM gateway dependency made harder, not softer.
- The explanation tool's value is bounded by record quality: thin reason codes produce thin
  explanations, which pressures ADR-0142/0201 to keep reason vocabularies human-meaningful — a
  healthy pressure, but a real one.

**Neutral**
- No new principal for the explanation tool (ADR-0203 D5 precedent); `rm-copilot` is agent-plane and
  AGPL per ADR-0197, enumerated in `rules.yaml agpl_modules` in its implementation PR.

## Compliance impact

- PCI DSS: not applicable — no cardholder data in either tool's scope.
- DORA: agents are ICT components under the ADR-0031 governance stack; no new third-party dependency
  (gateway is in-cluster per ADR-0174's target topology).
- GDPR: Art. 13/14 — the explanation tool is the transparency mechanism for profiled content;
  Art. 21(2)/7(3) — every explanation surfaces the objection/withdrawal path; Art. 25 — the
  no-record fallback and session-only data collection are by-design minimisation.
- PSD2: not applicable.
- CNB: explanation of credit offers renders ADR-0142's reason codes verbatim — no re-derivation means
  no new adverse-action reasoning surface; consumer-communication accountability stays with the
  governed send paths (ADR-0176/0211), never with the agent.

## References

- [ADR-0203](0203-business-plane-ai-agents.md) — the roster this extends; D5's extension precedent
  (D1), D7/D8's constraints and gates (inherited verbatim).
- [ADR-0201](0201-customer-segmentation-and-next-best-action-on-the-ml-decisioning-platform.md) — the
  Art. 22/Annex III boundary the explanation tool renders but never crosses; Art. 13/14 commitment it
  discharges.
- [ADR-0220](0220-in-app-engagement-surfaces-gamification-and-pre-approved-offers.md) — the artefacts
  to explain (standing decisions, reason codes).
- [ADR-0210](0210-customer-360-as-a-query-over-the-analytics-silver-layer.md) — the rm-copilot's read
  surface.
- [ADR-0176](0176-operator-initiated-customer-messaging.md) and
  [ADR-0219](0219-platform-contact-policy-gate-contact-classes-durable-counters-suppression.md) —
  the only send paths an RM draft may take.
- [ADR-0089](0089-customer-facing-ai-assistant.md) — the customer-copilot being extended;
  [ADR-0148](0148-ai-assurance-prompt-registry-evals-gate-and-eu-ai-act-mapping.md) — the prompt
  registry and evals gate both tools register with.
