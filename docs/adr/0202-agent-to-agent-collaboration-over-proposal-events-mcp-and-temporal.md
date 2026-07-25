---
date: 2026-07-25
decision-status: proposed
delivery-status: planned
authors: [Jiri Raska]
supersedes: []
superseded-by: []
delivery-repos: []
tags: [ai-agents, authz, kafka, governance]
summary: "Agents collaborate over an agent.proposal.v1 outbox topic, existing MCP read tools and a Temporal orchestrator instead of an A2A framework; a delegating call is identified by principal id, never by the unreachable SERVICE principal type."
---

# ADR-0202 — Agent-to-agent collaboration over proposal events, MCP and Temporal

## Context

Fifteen agents are declared in `openbank-libs/governance/agents.yaml` with matching Markdown charters
under `docs/agents/` (parity enforced by `.github/scripts/check-agent-charter-registry.sh`). Every one
of them is a soloist: it wakes on its own schedule, reads its own `data_scope`, and emits its own
proposal. Nothing lets one agent's finding become another agent's input.

That is a real limitation and not merely an aesthetic one. ADR-0163's control-liveness-sentinel exists
because *"each mechanism only pages for itself"* — the same shape one level up. A liveness finding about
an uninstalled watchdog, a governance-auditor finding about a PR that merged without approvals, and a
release-steward finding about a manifest drift are frequently the same incident seen three ways, and
today three agents each file a partial ticket. ADR-0203 adds six business-plane agents where the
coupling is tighter still: a fraud-triage verdict is exactly what a dispute evidence pack needs.

Four constraints shape the answer, and three of them are traps this repo has already paid for.

**Constraint 1 — an agent-to-agent call cannot be authorized by principal type.** The obvious rego rule
is `input.principal.type == "SERVICE"`. That rule can never fire. `AuthorizeInterceptor` only emits
`ANONYMOUS`, `AI_AGENT` and `HUMAN`; an M2M caller presenting a Keycloak client_credentials JWT is
classified `HUMAN`, and no realm client is granted `ROLE_SERVICE`. A rule gated on a `SERVICE` principal
is structurally unreachable dead code that silently denies its intended caller the moment
`AUTHZ_ENFORCE` is true — found live in the shared `rest.rego` `edge-service-notification` rule (issue
#266) and now blocked by `.github/scripts/check-no-service-principal-type.sh`. Any design where agent A
calls agent B's surface walks straight into this.

**Constraint 2 — the MCP caller identity is not yet real.** ADR-0195 is `proposed/planned`:
mcp-service's `resolveContext()` returns a hardcoded constant and ignores the headers its own KDoc
claims to read, and `StubReadPorts` is the only thing keeping that harmless (blocker #2206). An
agent-to-agent MCP call today would be authorized as whatever that constant says, for every caller.

**Constraint 3 — there is no shared orchestration library to build on.** Temporal is the fleet standard
with 14 workflow implementations, but the wiring is duplicated per service as
`infrastructure/temporal/TemporalClientProducer.kt`; `openbank-libs*` contains no `io.temporal` import at
all. "Reuse Temporal" means copying the established per-service pattern, not importing a module.

**Constraint 4 — the LLM gateway is not deployed.** ADR-0174 states plainly that ADR-0031's
LiteLLM/vLLM/Anthropic gateway topology *is not deployed at all*. Multi-agent traffic multiplies token
spend and there is currently no place that measures it per agent, and no egress control over prompts
leaving for a US provider (the ADR-0175 open exposure). `agents.yaml` already caps `tokens_per_run` and
`runs_per_day` per agent, but a collaboration that fans out has no aggregate cap.

Why now: ADR-0203 is the first set of agents whose value depends on handing work to each other, and the
sequencing question — framework first or capability first — should be settled before six agents each
invent a private convention.

## Decision

We will build agent collaboration from three mechanisms the platform already runs, and add no new
protocol, broker or framework.

**D1 — Findings are published as events, not calls.** A new topic family `agent.proposal.v1` carries a
fixed, PII-free envelope: emitting agent id, subject reference (an id, never a copied record), finding
class, severity, confidence, evidence pointers, and the charter under which it was produced. Published
through each agent's existing transactional outbox, consumed by any agent whose charter declares an
interest. The schema is closed and allow-listed in the ADR-0059 sense: an allow-list of *meanings*
rather than a scrub of a free-form payload, which is the same reasoning ADR-0176 D1 and ADR-0059 D2 both
used. Because it is a published event and not a call, constraint 1 does not arise — there is no
cross-agent request to authorize, and Kafka topic ACLs plus mTLS are the access control.

**D2 — Reading another service's data stays an MCP tool-call, authorized as `AI_AGENT`.** An agent that
needs a fact fetches it from the owning service through mcp-service, exactly as today, and the ADR-0034
sidecar authorizes it against the shared `/openbank/rest/allow` bundle. Agents do not query each other's
databases and do not expose private read APIs to each other. This is blocked on ADR-0195: until
`resolveContext()` resolves a real caller, an agent-to-agent read is unauthorizable, so **D2 does not
ship before ADR-0195 / #2206**.

**D3 — Where a delegating identity is unavoidable, identify it by `principal.id`.** Any rule
distinguishing one agent-originated caller from another matches on `input.principal.id` using Keycloak's
`service-account-<clientId>` convention, never on a principal type, and never on `ROLE_OPERATOR` alone —
real staff also carry `ROLE_OPERATOR`, so that would over-grant. This restates the #266 lesson at the
point where a new design would otherwise repeat it, and the existing CI guard backstops it.

**D4 — Multi-agent sequences are Temporal workflows owned by one agent.** A chain (triage, then evidence
assembly, then notification) is a workflow in the initiating agent's service, with each participating
step as an activity and every human checkpoint as a signal. No agent invokes another agent's *reasoning*
synchronously; it either consumes a published finding (D1) or calls a tool (D2). Retries, timeouts,
per-step audit and visibility come from Temporal. The per-service `TemporalClientProducer` pattern is
copied, per constraint 3.

**D5 — Charters declare collaboration explicitly, and the schema grows two fields.** `agents.yaml` gains
`collaboration.publishes` and `collaboration.consumes` (lists of finding classes) per agent, so who may
hear whom is reviewable configuration rather than emergent behaviour. The existing parity gate extends to
them: a consumed class nobody publishes, or a published class with no consumer, fails CI — an
unreachable subscription is the same dead-code defect class as an unreachable rego rule. `requires_human`
is unchanged and still applies per agent: a chain of three agents that each propose still ends at one
human approval, and a collaboration must never become a way to launder an autonomous action through
several proposals.

**D6 — Aggregate cost and kill-switch semantics are defined at the chain level.** A workflow carries a
chain token budget in addition to each agent's `tokens_per_run`, and the existing
`global_controls.kill_switch_enabled` terminates in-flight chains, not merely future runs. Before D2
ships, the LiteLLM gateway of ADR-0031/0174 should be deployed, because a fan-out topology with no
per-agent token accounting is a cost with no denominator — and the ADR-0175 prompt-egress exposure gets
worse, not better, with more agents.

**D7 — Licensing.** All of this is agent-plane and moves no money, so it satisfies the ADR-0197 property
test and is AGPL-3.0-only, in `rules.yaml agpl_modules`. The `agent.proposal.v1` envelope itself, if it
lands in `openbank-libs-domain`, stays Apache-2.0 with the rest of that module — a shared schema is not
the agent plane.

## Alternatives considered

- **Adopt an A2A framework (Google A2A, AutoGen, CrewAI, LangGraph).** Purpose-built, and it would give
  message formats and orchestration primitives immediately. Rejected on governance rather than on
  capability: this platform's differentiation is that every agent action passes an OPA decision, lands in
  an AI-attributed audit record, and is declared in a reviewable charter. A framework with its own
  message bus and its own agent registry becomes a second control plane beside `agents.yaml`, and the
  authoritative answer to "what may this agent do" would live in two places — the exact drift class
  ADR-0197 and ADR-0198 both exist to close. It would also be a new ADR-0174 ICT dependency for a
  capability three running systems already provide.
- **Direct synchronous agent-to-agent HTTP or MCP calls between agent services.** Simplest to reason
  about and lowest latency. Rejected: it forces the delegating-identity problem of constraints 1 and 2
  into the critical path immediately, couples agent availability (a chain fails if any agent is scaled to
  zero, which on this KEDA-scaled sandbox is the normal state), and makes each agent a service surface
  that must be threat-modelled. D1's event indirection avoids all three.
- **A shared "orchestrator agent" that plans and dispatches to the others.** Conceptually clean and a
  common pattern. Rejected because the orchestrator would need the union of every agent's `data_scope`
  and tool grants to plan usefully, which is a least-privilege violation with a very large blast radius —
  the same objection already recorded against agent-service's blanket `ROLE_OPERATOR` privilege
  escalation. D4 keeps orchestration per-chain and per-owner instead.
- **A shared agent database or blackboard.** Cheap and easy. Rejected: a mutable shared store with
  fifteen writers has no owner, no schema authority and no audit story, and it would become a source of
  truth by accident. An append-only event topic with a closed envelope has all three.
- **Do nothing; keep agents as soloists and let humans correlate.** The status quo, zero cost, and the
  honest baseline. Rejected for the reason ADR-0163 was accepted one level down: correlation is real work
  that currently falls on whoever reads three tickets, and the sentinel agent already established that
  cross-mechanism correlation is worth automating.

## Consequences

**Positive**
- Reuses three mechanisms already in production (outbox topics, MCP with OPA, Temporal), so the marginal
  infrastructure cost is a topic family and two `agents.yaml` fields.
- "Which agent may hear which" becomes reviewable configuration with a CI parity gate, rather than
  emergent behaviour discovered in a log.
- The #266 unreachable-principal-type trap is named at the point a new design would repeat it, and the
  existing guard already blocks it.
- Correlated findings become possible without any agent gaining another's privileges.

**Negative**
- D2 is hard-blocked on ADR-0195 / #2206. An implementation that shipped D1 and D2 together without
  fixing `resolveContext()` would authorize every agent as one constant identity, which is worse than no
  collaboration at all. D1 can ship alone; D2 cannot.
- Chains multiply token spend, and with the LiteLLM gateway undeployed there is no per-agent cost
  denominator today. D6 mitigates by policy, not by measurement, until the gateway lands.
- A closed envelope means a new finding class needs a schema change and a release. Accepted for the same
  reason ADR-0176 accepted it for templates, and it will be under the same pressure to relax.
- More agents publishing into a shared topic family widens the ADR-0175 prompt-egress exposure in
  aggregate, even though each envelope is PII-free — evidence *pointers* are still a map of what exists.
- Two new `agents.yaml` fields mean the schema and its gate must be updated in lockstep, and a
  half-updated gate would pass a malformed charter.

**Neutral**
- Whether the human review surface for a chain is the existing admin-ui HITL queue or a new one is not
  decided here.
- Nothing prevents a chain of length one; a soloist agent remains a valid configuration and most stay
  that way.

## Compliance impact

- PCI DSS: not applicable — the envelope carries no cardholder data, by schema.
- DORA: rejecting an external A2A framework is an ICT third-party position for the ADR-0174 register.
  Chain-level kill-switch semantics (D6) are an operational-resilience control, and a collaboration
  topology that can be stopped mid-flight is materially different from one that can only be stopped
  before the next run.
- GDPR: Art. 5(1)(c) minimisation is the reason the envelope carries references rather than copied
  records; Art. 32 for the topic's mTLS and ACLs. No new personal-data processing purpose is created — an
  agent reading customer data still does so under the purpose of the service whose tool it calls.
- PSD2: not applicable — no account access or initiation is created by agents talking to each other; an
  agent's PSD2-scoped access remains consent-bound through psd2-service and ADR-0195.
- CNB: not applicable.

EU AI Act: composing multiple limited-risk systems does not itself create a high-risk system, but the
*chain* is what a reviewer will assess, so `requires_human` at each agent (D5) and the prohibition on
laundering an autonomous action through successive proposals are the controls that keep the composition
inside Art. 14 human oversight. ADR-0031's AI-attributed audit already records each step's actor.

## References

- [ADR-0031](0031-ai-agent-governance-and-operations.md) — agents-as-code, policy-gated MCP,
  human-in-the-loop, AI-attributed audit; the substrate this extends.
- [ADR-0034](0034-unified-opa-authz-mcp-and-rest.md) — one sidecar for MCP tool-calls and REST.
- [ADR-0156](0156-agent-charters-as-markdown-alongside-agents-yaml.md) — the charter layer and the parity
  gate D5 extends.
- [ADR-0195](0195-mcp-server-caller-authentication-and-psd2-consent-binding.md) — blocking for D2;
  blocker #2206.
- [ADR-0163](0163-control-liveness-sentinel-ai-agent.md) — the "each mechanism only pages for itself"
  argument, one level down.
- [ADR-0059](0059-outbound-oversight-webhooks-slack-teams.md) — the fixed-safe-schema principle behind
  D1's envelope.
- [ADR-0174](0174-ict-third-party-dependencies-and-exit-strategy.md) and
  [ADR-0175](0175-data-residency-and-sovereignty.md) — the undeployed LLM gateway and the prompt-egress
  exposure D6 responds to.
- [ADR-0197](0197-agpl-open-core-boundary-covers-the-whole-agent-plane.md) — the licence property test.
- [ADR-0203](0203-business-plane-ai-agents.md) — the agents that need this.
- Issue #266 and `.github/scripts/check-no-service-principal-type.sh` — why D3 matches on principal id.
- `openbank-libs/governance/agents.yaml` and `docs/agents/` — the fifteen current agents and charters.
