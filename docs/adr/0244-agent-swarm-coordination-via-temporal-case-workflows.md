---
date: 2026-08-05
decision-status: proposed
delivery-status: planned
authors: [Jiri Raska]
supersedes: []
superseded-by: []
delivery-repos: []
tags: [ai-agents, architecture, resilience, governance]
summary: "Real-time agent swarm = one Temporal case workflow per disposition target: chartered agents join mid-run via OPA-gated signals, a dedicated case-coordinator owns budget and convergence, and the swarm emits exactly one HITL proposal."
---

# ADR-0244 — Agent swarm coordination via Temporal case workflows

## Context

ADR-0202 defines agent-to-agent collaboration as a *pipeline*: findings published on
`agent.proposal.v1`, read tools over MCP, and multi-agent sequences as Temporal
workflows owned by a single agent. That covers hand-offs. It does not cover the
requirement this ADR addresses: **several chartered agents working the same problem
concurrently, in real time** — entering a running investigation, commenting on each
other's contributions, correcting the direction mid-flight, and thereby improving
both the speed and the content of the result ("swarm" semantics).

Constraints inherited from the estate:

- **ADR-0031**: agents propose, governance disposes. Charters live in
  `agents.yaml`; every action is AI-attributed audit; kill switch per agent and
  global.
- **ADR-0034 / ADR-0202**: tool access is OPA-gated; a delegating call is identified
  by principal id, never by the structurally unreachable `SERVICE` principal type.
- **Temporal is the fleet orchestration standard** (ADR-0101/0120) and there is no
  shared orchestration library (ADR-0202 constraint 3) — reuse means the per-service
  `TemporalClientProducer` pattern.
- **The agent plane moves no money** and is AGPL-3.0-only (ADR-0136/0197).
- **Token cost is a governed quantity** (ADR-0112 finops dashboards); an unbounded
  swarm is an unbounded cost center.

## Decision

**D1 — A case is one long-running Temporal workflow.** The unit of swarm work is a
*case*, executed as a single durable workflow (the "case workflow") started via the
established per-service Temporal client pattern. Participating agents run as
activities or child workflows of that case. Durability, retry, per-step history and
visibility come from Temporal; no new orchestration substrate is introduced.

**D2 — Mid-run participation happens through Temporal signals, gated per charter.**
A chartered agent joins a running case by signalling the case workflow with a typed
contribution (`evidence`, `objection`, `correction`, `endorsement`). The signal
handler performs the signalling agent's charter capability check (OPA, principal id)
before accepting the contribution — the policy gate sits at the workflow boundary,
exactly as it does at the MCP boundary (ADR-0225 shapes discovery the same way:
an agent never learns about a case it cannot join). Signal rate is bounded by the
signalling agent's charter limits.

**D3 — Coordination is a dedicated chartered agent: `case-coordinator`.** The agent
that opened the case never coordinates it. Invitation of participants, budget
enforcement, convergence judgement and synthesis are a separate capability with its
own charter in `agents.yaml`, its own rate limits and its own kill-switch scope.
Audit attribution stays clean: who detected is never who coordinated.

**D4 — A case is bounded to one disposition target.** A case is opened for one
alert, incident or proposal lineage — never a free-form topic — and must terminate
in exactly one outcome: a single synthesized proposal, or an explicit no-action
finding. Both go to the human disposition path. This preserves the ADR-0031
invariant and makes cost bounded by construction.

**D5 — Pre-emption is allowed and deterministic.** A contribution carrying decisive
new evidence may short-circuit pending steps: affected steps are marked
`superseded-by-evidence` and skipped by a deterministic workflow rule, so Temporal
replay stays consistent. Queue-only commenting was rejected as the sole mechanism —
it serializes away the speed benefit that justifies a swarm — and uncontrolled
interruption was rejected because it breaks replay determinism. Every pre-emption is
a history event naming the triggering signal id.

**D6 — Every case carries a budget and a stop-condition.** Token and wall-clock
budgets are declared at case open, with per-case-class defaults in `agents.yaml`.
The case ends on convergence (the coordinator's synthesis activity judges the
contribution set sufficient), on budget exhaustion, or on deadline. A case that does
not converge still produces one proposal, marked **contested**, with the dissenting
contributions attached. Non-convergence is a disposition input, never a silent
stall.

**D7 — The swarm's only output channel is the existing HITL path.** The synthesized
proposal enters the ADR-0031 proposal flow (and the unified approval inbox of
ADR-0227 when it lands); the swarm never writes to a business service directly.
Every contribution — signal accepted, signal rejected, pre-emption, synthesis — is
recorded as an AI-attributed audit event carrying the case id, the contributing
principal id, model id/version and prompt hash (the ADR-0031 D5 capture set), so the
case joins the cross-channel trail of ADR-0226 and one query answers "who
contributed what to this case" across every participant. The global and per-agent
kill switches cancel in-flight case workflows (Temporal cancel) without redeploy;
a halted case emits a no-action finding naming the kill switch as the cause, so a
governance action never reads as a swarm verdict.

**D8 — The coordinator lives in the agent plane.** `case-coordinator` is an
AGPL-3.0-only module registered in `rules.yaml: agpl_modules`, like every other
agent-plane component.

## Alternatives considered

- **Blackboard over a Kafka topic + materialized view**: an `agent.case.v1` topic
  with a materializing service. Flexible, but duplicates the orchestration Temporal
  already provides and makes the board eventually consistent where the workflow is
  authoritative.
- **Chat-room resource**: agents post to a case-thread entity like humans. Simplest
  mental model, but orchestration and convergence are implicit — nothing enforces a
  stop.
- **An external A2A framework**: rejected already by ADR-0202 (external dependency,
  contrary to the in-cluster OSS substrate principle).
- **Stay with the ADR-0202 pipeline**: does not meet the real-time collaboration
  requirement at all.

## Consequences

- The swarm requirement is met entirely on existing, production-proven mechanisms
  (Temporal workflows + signals, OPA, charters, AI-attributed audit); the marginal
  infrastructure cost is one new chartered agent and the `agents.yaml` schema
  extension for case budgets and the swarm-participation capability.
- Signal handlers must stay deterministic and replay-safe; signal storms are bounded
  by charter rate limits (D2) and case budgets (D6).
- An admin-ui "swarm thread" view becomes a pure projection of Temporal workflow
  history — no new data model; the UI surface is a separate follow-up ADR.
- Contested proposals add HITL cognitive load; the disposition UI must render
  dissent.
- Follow-ups: `agents.yaml` extension; `case-coordinator` charter + eval scenarios
  under the ADR-0148 evals gate; admin-ui thread-view ADR; registration in
  `rules.yaml: agpl_modules`.

## Compliance impact

No change of AI Act risk class: the swarm never decides — a human disposes every
case outcome, money-path dispositions keep SCA (ADR-0227), and the agent plane moves
no money (ADR-0197). Every contribution is AI-attributed and case-correlated
(ADR-0031 D5, ADR-0226); charters keep PII masked by default (ADR-0031). Temporal
durability supports DORA incident-reconstruction expectations.

## References

- ADR-0031 (agent governance), ADR-0034 (unified OPA), ADR-0101/0120 (Temporal),
  ADR-0148 (AI assurance/evals), ADR-0197 (agent-plane AGPL boundary),
  ADR-0202 (agent-to-agent pipeline), ADR-0225 (policy-filtered tool discovery),
  ADR-0226 (audit correlation), ADR-0227 (unified approval inbox)
- `openbank-libs/governance/agents.yaml`
