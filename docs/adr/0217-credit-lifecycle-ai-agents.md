---
date: 2026-07-29
decision-status: proposed
delivery-status: planned
authors: [Jiri Raska]
supersedes: []
superseded-by: []
delivery-repos: []
tags: [ai-agents, lending, compliance]
summary: "Credit AI agents (officer copilot, customer explainer) run on ADR-0031 machinery as MCP-exposed, OPA-gated assistants that never transition the state machine and never decide credit; every artefact is AI-attributed evidence."
---

# ADR-0217 — AI agents in the credit lifecycle: MCP-exposed, policy-gated, human-deciding

## Context

The credit process defined by ADR-0211…0215 is deterministic where the law demands it
(state machine, policy engine, settlement math) — but around those deterministic cores
sits a large amount of *judgement-shaped toil* that decides whether the platform is
merely correct or actually the best: reading and summarising a 40-page application
dossier, chasing missing documents, drafting the adverse-action letter with the right
principal reasons, briefing the checker before a four-eyes review, explaining a
decision to a customer in plain Czech. This is exactly the workload AI agents are good
at — and exactly where an uncontrolled agent is catastrophic (a hallucinated reason in
an adverse-action letter is a ČNB finding; a prompt-injected agent reading applicant
documents is an exfiltration path).

The platform already decided **how** agents run: ADR-0031 (agents-as-code in
`agents.yaml` with a charter, OPA policy gate in front of every MCP `tools/call`,
SPIFFE identity, AI-attributed tamper-evident audit, Temporal + LangGraph durable
agent loop, model-gateway with guardrails, budgets and kill switch) and ADR-0102
(tool-use banking agent, LLM-assisted KYC precedent). What is undecided is **which**
agents exist in the credit lifecycle, what they may *never* do, and how their outputs
enter the lawful process. This ADR decides that — per ADR-0031's per-agent AI-Act
scoping and ADR-0216's GPAI/LLM boundary.

## Decision

**D1 — Agent roster (assistants, not deciders).** Two agents, each registered in
`agents.yaml` with a charter, running on the ADR-0031 stack:

- **Credit Officer Copilot** (internal, operator-facing): application-dossier
  summarisation against the pack's checklist (ADR-0212); document-completeness and
  staleness review with a *drafted* chasing request; four-eyes pre-brief (policy
  outcome, matched rules, anomalies, comparable precedents); **draft** adverse-action
  and offer letters rendering the ADR-0213 reason codes into jurisdiction-correct
  human language. The officer edits and sends; nothing is auto-sent.
- **Customer Explanation Agent** (customer-facing, via `openbank-copilot-service`):
  explains the customer's own application status, decision reasons and options in
  plain language, localised, jurisdiction-aware from the pinned pack. It never
  negotiates terms, never promises outcomes, never collects data beyond the session.

**D2 — Hard invariants (fail-closed, tested, not policy).**

1. **Agents never transition the state machine.** ADR-0211 is unamended: the state
   machine is the law, and only authenticated human/system commands move it. Agents
   produce *artefacts* (summaries, drafts, briefs) consumed by humans or by
   deterministic code — they hold no transition capability in their OPA policy.
2. **Agents never decide credit.** No path from an LLM to approve/decline/price
   (ADR-0142, ADR-0216 D6). The copilot *reads* the ADR-0213/0142 decision; it does
   not make one.
3. **Untrusted-content containment.** Agents read applicant documents — classified
   untrusted input. ADR-0031 D6 guardrails (injection filter + output guard) are
   mandatory, and tool results from document parsing are sandboxed data, never
   instructions.
4. **AI-attributed evidence.** Every agent call that produces an artefact entering the
   loan file emits an ADR-0214 evidence event (agent id + SVID, model + version,
   prompt hash, artefact hash, human accept/edit outcome) — AI assistance is visible
   in the same tamper-evident trail as everything else, per ADR-0031 D5.

**D3 — MCP-negotiable exposure.** Agent capabilities are MCP tools (`credit.dossier.
summarize`, `credit.documents.review`, `credit.fourEyes.brief`, `credit.letter.draft`,
`credit.decision.explain`) behind the unified OPA sidecar (ADR-0034): discoverable via
`tools/list`, contracts versioned like APIs, role-scoped (`ROLE_LENDING_OFFICER`,
customer session for the explanation agent). Any MCP client — admin UI, officer
workbench, future third parties under PSD2-style consent — negotiates the same
contract; no bespoke agent APIs.

**D4 — Phasing (ADR-0031 D9 discipline).** Phase 1: officer copilot read-only
(summarise/review/brief) in shadow on historical applications, quality measured
against officer judgement. Phase 2: drafting artefacts (letters, chasing requests)
with human send. Phase 3: customer explanation agent behind Art. 50 disclosure, after
guardrail red-teaming. Each phase reversible by the agent kill switch (ADR-0031 D7).
**Agent quality is measured, not assumed**: draft acceptance rate, officer edit
distance per artefact, explanation-agent CSAT, and guardrail-trigger rate are
DomainMetrics with per-phase promotion thresholds (recorded with the ADR-0218 funnel
metrics) — an agent that officers silently rewrite does not get promoted, and
sustained rubber-stamping (acceptance without review) triggers an oversight review,
the ADR-0031 warning made a metric.

## Alternatives considered

- **Let the agent drive the workflow** (agent calls transition tools under
  supervision). Maximally "agentic", and it hands a non-deterministic component the
  keys to a lawful process — GDPR Art. 22 / AI Act Art. 14 exposure by design.
  Rejected; D2-1 is the platform's answer to "agentic but lawful".
- **No agents in credit** (deterministic only). Lawful, and leaves the officer-toil —
  the actual cost centre of origination — untouched, ceding the experience gap to
  AI-native lenders. Rejected; assistance without decision authority captures most of
  the value at a fraction of the risk.
- **Vendor copilot SaaS embedded in the officer UI.** Fast, but exports applicant PII
  to a third country, breaks the in-cluster OSS story (ADR-0027) and the AI-attributed
  audit chain. Rejected; the model gateway (ADR-0031 D6) already gives hybrid
  self-hosted/external routing under our guardrails.
- **One general bank agent with credit tools.** Rejected per ADR-0031's per-agent
  charter/scoping — credit's high-risk classification (ADR-0216) demands a narrow,
  separately-governed agent, not a broader one.

## Consequences

**Positive**
- Officer throughput and decision-explanation quality — the two human bottlenecks of
  origination — improve without touching the lawful core of ADR-0211…0215.
- Credit joins the platform's agentic-differentiation story (ADR-0102) with the
  tightest governance in the fleet, because it must.
- MCP-first exposure makes the capabilities composable (admin UI today, any client
  tomorrow) with zero new API surface to govern.

**Negative**
- Draft-artefact quality is a liability surface: a wrong drafted reason that an
  officer rubber-stamps is still the bank's letter. The edit/accept telemetry (D2-4)
  exists precisely to detect rubber-stamping before a supervisor does.
- Prompt injection via applicant documents is the primary residual risk (ADR-0031
  already names it); red-teaming is a phase-gate, not a nice-to-have.

**Neutral**
- Runs entirely on existing agent infrastructure (agent-service, copilot-service,
  gateway, Langfuse); no new deployable.
- AI Act: assistant agents are scoped per ADR-0031/0216 (Art. 50 transparency for the
  customer-facing one); the *decision* remains the ADR-0142 high-risk system.

## Compliance impact

- PCI DSS: not applicable.
- DORA:    agents are ICT assets with budgets, kill switch and durable replay
           (ADR-0031 D7); no new third-party dependency (in-cluster gateway).
- GDPR:    applicant documents processed in-cluster; artefacts evidence-bound;
           Art. 22 untouched (agents never decide).
- PSD2:    customer explanation agent inherits the customer-edge auth (ADR-0065).
- CNB:     adverse-action letters remain human-approved; AI assistance is visible in
           the audit trail an examiner reads.
- **EU AI Act:** Art. 50 transparency (customer-facing agent disclosure); GPAI
  provider documentation via the gateway; ADR-0216 D6 boundary preserved.

## References

- ADR-0031 — agent governance (charters, OPA-gated MCP, SPIFFE, HITL, audit, stack,
  budgets/kill switch, phasing)
- ADR-0034 — unified OPA authz for MCP and REST
- ADR-0102 — agentic AI differentiation (tool-use agent; LLM-assisted KYC precedent)
- ADR-0216 — AI Act high-risk compliance (the D6 GPAI/LLM boundary)
- ADR-0211 — state machine is the law (D2-1); ADR-0212 — packs (jurisdiction-aware
  language/checklists); ADR-0213 — reason codes the letters render; ADR-0214 —
  AI-attributed evidence events
- ADR-0142 — no-LLM-underwriting rule
- ADR-0065 — customer edge (customer-facing agent path)
- Regulation (EU) 2024/1689 — Art. 50; GPAI Chapter V
