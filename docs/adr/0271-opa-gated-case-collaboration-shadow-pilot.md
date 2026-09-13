---
date: 2026-08-22
decision-status: proposed
delivery-status: planned
followup: "#6426 — explicit approval required before writing the charter/rules authorization grant"
authors: [Jiri Raska]
supersedes: []
superseded-by: []
delivery-repos: []
tags: [ai-agents, governance, security, resilience]
summary: "Case collaboration is a separately governed, OPA-gated Temporal signal path piloted only by rca-investigator on incident-response SHADOW cases, with distinct authorization, invocation, consumption and persistence evidence."
---

# ADR-0271 — OPA-gated case collaboration shadow pilot

## Context

ADR-0244 defines `case.join` and `case.contribute`, and
`openbank-case-coordinator-agent` already has typed Temporal signal handlers and a REST
signal ingress. That is not a production grant. Today the ingress checks an in-process
configuration list; no non-coordinator charter holds either capability, the OPA bundle
does not decide case capabilities, and accepted/rejected signal authorization is not a
separate durable evidence stage.

The Agent Control Room is deliberately read-only. Granting collaboration changes who may
influence a running workflow and must not ride inside its UI PR.

## Decision

**D1 — One bounded principal and one case class.** Subject to explicit authorization
approval, the first pilot grants only `rca-investigator` the capabilities `case.join` and
`case.contribute`, only for `incident-response`, only when delivery mode is `SHADOW`.
The grant has a kill switch, rollout id and a maximum of eight signals per case. Fraud,
AML, payment and every other case class remain denied.

**D2 — Charter and rules matrix must both allow.** `agents.yaml.case_capabilities` is a
necessary charter claim. `rules.yaml.agent_case_capability_matrix` independently bounds
principal, capability, case class, delivery mode, quota, rollout and kill switch. OPA
intersects both. Missing or malformed data denies. Neither file alone grants access.

**D3 — The case coordinator is the PEP.** Before invoking a Temporal signal, the REST
ingress proves the asserted agent identity, reads case class and delivery mode from the
server-owned case record, evaluates the OPA case decision, and applies a local fail-safe
charter check. Caller-supplied case class, mode or policy result is never trusted.

**D4 — Four evidence stages remain distinct.** The system records:

1. `AUTHORIZED` or `DENIED`: OPA decision id, policy reason and principal;
2. `INVOKED`: the Temporal signal call was accepted by the client;
3. `CONSUMED`: the workflow handler incorporated the typed signal;
4. `PERSISTED`: the contribution activity committed the durable read model.

An invocation is never presented as consumption or completion. Every record carries the
case/correlation id, signal id, agent id, capability, observation timestamp and rollout
id. Rejected signals never reach synthesis.

**D5 — Audit is part of the grant.** Allow and deny decisions emit the canonical
AI-attributed audit envelope. The operation names the exact capability, the case is the
resource id, and the result distinguishes `SUCCESS`, `DENIED` and `FAILURE`. No raw case
summary, evidence content or PII enters policy logs.

**D6 — The pilot cannot dispose.** SHADOW outcomes are persisted for evaluation but are
not relayed to the HITL proposal topic. The participant cannot synthesize, pre-empt,
pause, cancel, kill, change a deadline, mutate a business record, or call a money tool.
The existing exactly-one terminal outcome invariant remains owned by the coordinator.

**D7 — Promotion requires measured evidence.** Replay determinism, deny-path tests,
signal-storm/budget tests and a seven-day shadow report must show zero unauthorized
signals, zero duplicate terminal outcomes and complete stage correlation. Promotion to
another principal, case class or delivery mode is a new rules/charter review, not a
configuration toggle hidden from governance.

**D8 — The case policy bundle is service-scoped.** The case decision uses a dedicated
`openbank/case-collaboration/decision` policy and a generated projection containing only
charter case capabilities plus the case-capability rules matrix. Only the case-coordinator
bundle embeds and hashes those artifacts. Adding case policy must not restamp or roll every
unrelated service that consumes the shared MCP/REST `agents.rego` bundle.

## Alternatives considered

- **Reuse the MCP tool decision:** rejected; tool invocation and workflow participation
  are different authority surfaces and need different inputs and audit semantics.
- **Trust the in-process allow-list:** rejected; it can drift from charters and cannot
  provide an OPA decision id or policy evidence.
- **Grant all control-plane agents:** rejected; it creates an unmeasured swarm and makes
  attribution, rate limits and rollback ambiguous.
- **Add LangGraph/LangChain orchestration:** rejected; Temporal remains the workflow
  authority and a second control plane would drift.

## Consequences

- The pilot is useful but intentionally narrow: one RCA agent may add observability
  evidence to one non-money-path shadow workflow.
- The dedicated generated case-policy projection and case-coordinator bundle become
  release artifacts and must pass differential policy tests without changing the shared
  fleet MCP/REST bundle checksum.
- The Control Room can later render each stage from evidence without inferring a solid
  edge from a charter declaration.
- Operator mutations remain a later ADR after this collaboration path is proven.

## Compliance impact

The change strengthens DORA incident reconstruction and EU AI Act human-oversight
evidence by making machine participation attributable and deny-by-default. It processes
masked observability and audit metadata only. It does not enter a money path. The threat
model is `docs/threat-models/openbank-case-coordinator-agent.md`.

## References

- ADR-0031 (agent governance)
- ADR-0244 (Temporal case workflows)
- ADR-0246 (read-only swarm thread view)
- issue #6426
